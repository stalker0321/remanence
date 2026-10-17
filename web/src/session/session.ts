import type { Language } from '../i18n/catalog'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

export const phases = [
  'signed-out',
  'authenticated',
  'keys-unlocked',
  'postcard-matched',
  'presentation-admitted',
] as const

export type Phase = (typeof phases)[number]

export class SessionError extends Error {
  constructor(readonly code: 'STALE' | 'ILLEGAL_PHASE' | 'INVALID_ACCOUNT') {
    super(code)
    this.name = 'SessionError'
  }
}

export interface SignInInput {
  accountId: string
  bundleId: string | null
  handle: string | null
  accessToken: string
  refreshToken: string
  accessExpiresAt: string
  refreshExpiresAt: string
}

export interface PublicSession {
  generation: number
  phase: Phase
  accountId: string | null
  bundleId: string | null
  handle: string | null
  language: Language
}

interface SecretSession {
  accessToken: string
  refreshToken: string
  accessExpiresAt: string
  refreshExpiresAt: string
}

function requireAccount(input: SignInInput): void {
  if (!UUID.test(input.accountId)) throw new SessionError('INVALID_ACCOUNT')
  if (input.bundleId !== null && !UUID.test(input.bundleId)) throw new SessionError('INVALID_ACCOUNT')
  if (input.accessToken.length === 0 || input.refreshToken.length === 0) {
    throw new SessionError('INVALID_ACCOUNT')
  }
}

export class SessionStore {
  #secrets: SecretSession | null = null
  #view: PublicSession
  #listeners = new Set<() => void>()

  constructor(language: Language = 'en') {
    this.#view = {
      generation: 1,
      phase: 'signed-out',
      accountId: null,
      bundleId: null,
      handle: null,
      language,
    }
  }

  subscribe = (listener: () => void): (() => void) => {
    this.#listeners.add(listener)
    return () => {
      this.#listeners.delete(listener)
    }
  }

  getSnapshot = (): PublicSession => this.#view

  bind(generation: number): () => void {
    return () => {
      if (this.#view.generation !== generation) throw new SessionError('STALE')
    }
  }

  /**
   * Transport-only read. Returns null when the caller generation is stale
   * or the visit has no session. The returned object is not part of the UI snapshot.
   */
  secretsFor(generation: number): SecretSession | null {
    if (this.#view.generation !== generation || this.#secrets === null) return null
    return this.#secrets
  }

  signIn(input: SignInInput): void {
    requireAccount(input)
    this.#secrets = {
      accessToken: input.accessToken,
      refreshToken: input.refreshToken,
      accessExpiresAt: input.accessExpiresAt,
      refreshExpiresAt: input.refreshExpiresAt,
    }
    this.#replace({
      phase: 'authenticated',
      accountId: input.accountId,
      bundleId: input.bundleId,
      handle: input.handle,
    })
  }

  unlock(): void {
    this.#require('authenticated')
    this.#replace({ phase: 'keys-unlocked' })
  }

  matchPostcard(): void {
    this.#require('keys-unlocked')
    this.#replace({ phase: 'postcard-matched' })
  }

  admitPresentation(): void {
    this.#require('postcard-matched')
    this.#replace({ phase: 'presentation-admitted' })
  }

  lock(): void {
    if (this.#view.phase === 'signed-out') throw new SessionError('ILLEGAL_PHASE')
    if (this.#view.phase === 'authenticated') return
    this.#replace({ phase: 'authenticated' })
  }

  signOut(): void {
    this.#secrets = null
    this.#replace({
      phase: 'signed-out',
      accountId: null,
      bundleId: null,
      handle: null,
    })
  }

  setLanguage(language: Language): void {
    if (this.#view.language === language) return
    this.#view = { ...this.#view, language }
    this.#emit()
  }

  #require(phase: Phase): void {
    if (this.#view.phase !== phase) throw new SessionError('ILLEGAL_PHASE')
  }

  #replace(patch: Partial<PublicSession>): void {
    this.#view = {
      ...this.#view,
      ...patch,
      generation: this.#view.generation + 1,
    }
    this.#emit()
  }

  #emit(): void {
    for (const listener of this.#listeners) listener()
  }
}
