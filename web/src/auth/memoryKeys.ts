import type { KeyCapabilities } from '../crypto/keyOwner'
import type { PublicSession, SessionStore } from '../session/session'

/**
 * Capabilities for the signed-in visit. Lock and sign-out drop them.
 * The public session snapshot does not carry this object.
 */
export class MemoryKeys {
  #caps: KeyCapabilities | null = null
  #accountId: string | null = null

  hold(view: PublicSession, caps: KeyCapabilities): void {
    if (view.accountId === null || view.phase === 'signed-out' || view.phase === 'authenticated') {
      caps.lock()
      throw new Error('ILLEGAL_PHASE')
    }
    this.drop()
    this.#caps = caps
    this.#accountId = view.accountId
  }

  capabilities(view: PublicSession): KeyCapabilities | null {
    if (this.#caps === null || view.accountId !== this.#accountId) return null
    if (view.phase === 'signed-out' || view.phase === 'authenticated') return null
    return this.#caps
  }

  drop(): void {
    const current = this.#caps
    this.#caps = null
    this.#accountId = null
    current?.lock()
  }

  attach(session: SessionStore): () => void {
    return session.subscribe(() => {
      if (this.#caps === null) return
      const view = session.getSnapshot()
      if (view.phase === 'signed-out' || view.phase === 'authenticated' || view.accountId !== this.#accountId) {
        this.drop()
      }
    })
  }
}
