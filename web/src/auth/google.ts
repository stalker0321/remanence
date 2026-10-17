import type { SessionStore } from '../session/session'
import { AuthError } from './errors'
import type { ApiTransport } from './http'

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
  return applyVerify(session, verified.status, verified.body)
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
  if (status !== 200 || root?.status !== 'SIGNED_IN') throw new AuthError('GOOGLE_REJECTED')
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
  if (session.getSnapshot().phase !== 'authenticated') throw new AuthError('GOOGLE_REJECTED')
  return { kind: 'signed-in' }
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
