import type { SessionStore } from '../session/session'
import { AuthError } from './errors'
import type { ApiTransport } from './http'
import type { RegistrationDraft } from './register'

export interface GoogleIdentityPort {
  requestIdToken(clientId: string, nonce: string, signal?: AbortSignal): Promise<string>
}

export type GoogleOutcome =
  | { kind: 'signed-in' }
  | { kind: 'existing-account-requires-link' }
  | { kind: 'client-upgrade-required' }
  | { kind: 'needs-handle' }

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const NONCE = /^[A-Za-z0-9_-]{43}$/

/**
 * One options call and one verify call. The server status chooses the route.
 * An existing account is signed in. A new or conflicting account is not registered here.
 */
export async function continueWithGoogle(
  session: SessionStore,
  transport: ApiTransport,
  identity: GoogleIdentityPort,
  signal?: AbortSignal,
  draft?: RegistrationDraft,
): Promise<GoogleOutcome> {
  if (session.getSnapshot().phase !== 'signed-out') throw new AuthError('ILLEGAL_PHASE')
  const options = await transport.request({
    path: '/v1/auth/google/options',
    method: 'POST',
    body: {},
    signal,
  })
  if (signal?.aborted) throw new AuthError('CANCELLED')
  if (options.status === 404) throw new AuthError('GOOGLE_CLIENT_UNAVAILABLE')
  const challenge = readChallenge(options.status === 200 ? options.body : null)
  if (!challenge) throw new AuthError('GOOGLE_REJECTED')
  const idToken = await identity.requestIdToken(challenge.clientId, challenge.nonce, signal)
  if (typeof idToken !== 'string' || idToken.length < 1 || idToken.length > 16384) {
    throw new AuthError('GOOGLE_REJECTED')
  }
  const verified = await transport.request({
    path: '/v1/auth/google/verify',
    method: 'POST',
    body: { flow: challenge.flow, nonce: challenge.nonce, id_token: idToken },
    signal,
  })
  if (signal?.aborted) throw new AuthError('CANCELLED')
  const outcome = applyVerify(session, verified.status, verified.body)
  if (outcome.kind === 'needs-handle') {
    if (draft) {
      const ticket = readTicket(verified.body)
      if (!ticket) throw new AuthError('GOOGLE_REJECTED')
      draft.replace(ticket.flow, ticket.token)
    }
  } else {
    draft?.wipe()
  }
  return outcome
}

function applyVerify(session: SessionStore, status: number, body: unknown): GoogleOutcome {
  const root = record(body)
  const reason = typeof root?.reason === 'string' ? root.reason : ''
  if (status === 409 && reason === 'EXISTING_ACCOUNT_REQUIRES_LINK') {
    return { kind: 'existing-account-requires-link' }
  }
  if (status === 426 && reason === 'CLIENT_UPGRADE_REQUIRED') {
    return { kind: 'client-upgrade-required' }
  }
  if (status === 200 && root?.status === 'NEEDS_HANDLE') return { kind: 'needs-handle' }
  if (!acceptSignedIn(session, status, body)) throw new AuthError('GOOGLE_REJECTED')
  return { kind: 'signed-in' }
}

/** Signs in only a server `SIGNED_IN` login. Returns false for every other body. */
export function acceptSignedIn(session: SessionStore, status: number, body: unknown): boolean {
  const root = record(body)
  if (status !== 200 || root?.status !== 'SIGNED_IN') return false
  const login = record(root.login)
  const user = record(login?.user)
  const bundle = record(login?.active_key_bundle)
  const accountId = user?.user_id
  const bundleId = bundle?.key_bundle_id
  const handle = user?.handle
  if (typeof accountId !== 'string' || !UUID.test(accountId)) throw new AuthError('GOOGLE_REJECTED')
  if (typeof bundleId !== 'string' || !UUID.test(bundleId)) throw new AuthError('GOOGLE_REJECTED')
  if (!(handle === null || (typeof handle === 'string' && handle.length > 0 && handle.length <= 32))) {
    throw new AuthError('GOOGLE_REJECTED')
  }
  if (typeof login?.access_token !== 'string' || typeof login.refresh_token !== 'string') {
    throw new AuthError('GOOGLE_REJECTED')
  }
  if (typeof login.access_expires_at !== 'string' || typeof login.refresh_expires_at !== 'string') {
    throw new AuthError('GOOGLE_REJECTED')
  }
  session.signIn({
    accountId,
    bundleId,
    handle,
    accessToken: login.access_token,
    refreshToken: login.refresh_token,
    accessExpiresAt: login.access_expires_at,
    refreshExpiresAt: login.refresh_expires_at,
  })
  return session.getSnapshot().phase === 'authenticated'
}

function readTicket(body: unknown): { flow: string; token: string } | null {
  const root = record(body)
  const flow = root?.flow
  const token = root?.registration_token
  if (typeof flow !== 'string' || !UUID.test(flow)) return null
  if (typeof token !== 'string' || !NONCE.test(token)) return null
  return { flow, token }
}

function readChallenge(body: unknown): { flow: string; nonce: string; clientId: string } | null {
  const root = record(body)
  const flow = root?.flow
  const nonce = root?.nonce
  const clientId = root?.client_id
  if (typeof flow !== 'string' || !UUID.test(flow)) return null
  if (typeof nonce !== 'string' || !NONCE.test(nonce)) return null
  if (typeof clientId !== 'string' || clientId.length < 1 || clientId.length > 200 || /\s/.test(clientId)) return null
  return { flow, nonce, clientId }
}

function record(value: unknown): Record<string, unknown> | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null
  return value as Record<string, unknown>
}
