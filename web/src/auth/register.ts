import { generateLocalIdentity, type LocalIdentity } from '../crypto/identity'
import type { SessionStore } from '../session/session'
import { AuthError } from './errors'
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
  #epoch = 0
  #pending: Promise<LocalIdentity> | null = null

  constructor(private readonly generate: () => Promise<LocalIdentity> = generateLocalIdentity) {}

  get epoch(): number { return this.#epoch }

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
    if (this.#pending) return this.#pending
    const epoch = this.#epoch
    const pending = this.generate().then((identity) => {
      if (this.#epoch !== epoch || !this.hasTicket()) {
        identity.wipe()
        throw new AuthError('STALE')
      }
      this.#identity = identity
      return identity
    }).finally(() => { if (this.#pending === pending) this.#pending = null })
    this.#pending = pending
    return pending
  }

  consumeTicket(): void {
    this.#flow = null
    this.#token = null
  }

  wipe(): void {
    this.#epoch += 1
    this.#pending = null
    this.#flow = null
    this.#token = null
    this.#identity?.wipe()
    this.#identity = null
  }
}

/**
 * Fail closed until a separately approved resumable registration protocol can
 * protect/read back the identity before an irreversible public-bundle commit.
 * Existing recovery requires an already authenticated account: warnings or a
 * navigation guard cannot bridge reload/ambiguous registration. No POST or
 * identity generation here. Existing-account login/restore is unaffected.
 */
export async function registerNewAccount(
  session: SessionStore,
  _transport: ApiTransport,
  draft: RegistrationDraft,
  rawHandle: string,
  signal?: AbortSignal,
): Promise<void> {
  if (session.getSnapshot().phase !== 'signed-out') throw new AuthError('ILLEGAL_PHASE')
  normalizeHandle(rawHandle)
  if (signal?.aborted) throw new AuthError('CANCELLED')
  draft.wipe()
  throw new AuthError('REGISTRATION_PAUSED')
}
