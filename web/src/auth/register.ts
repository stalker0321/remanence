import { generateLocalIdentity, type LocalIdentity } from '../crypto/identity'
import type { SessionStore } from '../session/session'
import { AuthError } from './errors'
import { acceptSignedIn } from './google'
import { normalizeHandle } from './handle'
import type { ApiTransport } from './http'

/**
 * The server registration ticket and the one local identity created for it.
 * Neither is written to storage. A new Google ceremony replaces both.
 * An existing account wipes them and does not receive this identity.
 */
export class RegistrationDraft {
  #flow: string | null = null
  #token: string | null = null
  #identity: LocalIdentity | null = null

  replace(flow: string, token: string): void {
    this.wipe()
    this.#flow = flow
    this.#token = token
  }

  hasTicket(): boolean {
    return this.#flow !== null && this.#token !== null
  }

  bundleId(): string | null {
    return this.#identity && !this.#identity.wiped ? this.#identity.bundleId : null
  }

  ticket(): { flow: string; token: string } | null {
    if (this.#flow === null || this.#token === null) return null
    return { flow: this.#flow, token: this.#token }
  }

  async identity(): Promise<LocalIdentity> {
    if (this.#identity && !this.#identity.wiped) return this.#identity
    if (!this.hasTicket()) throw new AuthError('IDENTITY_ABSENT')
    this.#identity = await generateLocalIdentity()
    return this.#identity
  }

  consumeTicket(): void {
    this.#flow = null
    this.#token = null
  }

  wipe(): void {
    this.#flow = null
    this.#token = null
    this.#identity?.wipe()
    this.#identity = null
  }
}

/**
 * Posts the held ticket and the one identity. A taken handle keeps both.
 * Success signs in and drops the ticket. The identity stays for enrollment.
 */
export async function registerNewAccount(
  session: SessionStore,
  transport: ApiTransport,
  draft: RegistrationDraft,
  rawHandle: string,
  signal?: AbortSignal,
): Promise<void> {
  if (session.getSnapshot().phase !== 'signed-out') throw new AuthError('ILLEGAL_PHASE')
  const ticket = draft.ticket()
  if (!ticket) throw new AuthError('REGISTRATION_REJECTED')
  const handle = normalizeHandle(rawHandle)
  if (signal?.aborted) throw new AuthError('CANCELLED')
  const identity = await draft.identity()
  if (signal?.aborted) throw new AuthError('CANCELLED')
  const verified = await transport.request({
    path: '/v1/auth/google/register',
    method: 'POST',
    body: {
      flow: ticket.flow,
      registration_token: ticket.token,
      handle,
      key_bundle: identity.publicBundle(),
    },
    signal,
  })
  if (signal?.aborted) throw new AuthError('CANCELLED')
  const root = record(verified.body)
  const reason = typeof root?.reason === 'string' ? root.reason : ''
  if (verified.status === 409 && reason === 'REGISTRATION_CONFLICT') throw new AuthError('HANDLE_TAKEN')
  if (!acceptSignedIn(session, verified.status, verified.body)) throw new AuthError('REGISTRATION_REJECTED')
  const view = session.getSnapshot()
  if (view.bundleId !== identity.bundleId || view.handle !== handle) {
    session.signOut()
    draft.wipe()
    throw new AuthError('REGISTRATION_REJECTED')
  }
  draft.consumeTicket()
}

function record(value: unknown): Record<string, unknown> | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null
  return value as Record<string, unknown>
}
