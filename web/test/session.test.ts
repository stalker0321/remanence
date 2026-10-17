import { describe, expect, it } from 'vitest'
import { SessionError, SessionStore, type SignInInput } from '../src/session/session'

const account = '11111111-2222-4333-8444-555555555555'
const other = '66666666-7777-4888-8999-aaaaaaaaaaaa'
const bundle = 'cccccccc-dddd-4eee-8fff-111122223333'

function sign(id = account, token = 'access-token-a'): SignInInput {
  return {
    accountId: id,
    bundleId: bundle,
    handle: 'ada',
    accessToken: token,
    refreshToken: 'refresh-token-a',
    accessExpiresAt: '2026-10-17T12:00:00Z',
    refreshExpiresAt: '2026-11-17T12:00:00Z',
  }
}

describe('session boundaries', () => {
  it('keeps sign-in, unlock, match, and admission as different phases', () => {
    const store = new SessionStore()
    store.signIn(sign())
    expect(store.getSnapshot().phase).toBe('authenticated')
    expect(() => store.matchPostcard()).toThrow(SessionError)
    expect(() => store.admitPresentation()).toThrow(SessionError)
    store.unlock()
    expect(store.getSnapshot().phase).toBe('keys-unlocked')
    expect(() => store.admitPresentation()).toThrow(SessionError)
    store.matchPostcard()
    expect(store.getSnapshot().phase).toBe('postcard-matched')
    store.admitPresentation()
    expect(store.getSnapshot().phase).toBe('presentation-admitted')
  })

  it('drops keys and secrets on sign-out and rejects a late callback', () => {
    const store = new SessionStore()
    store.signIn(sign())
    store.unlock()
    const generation = store.getSnapshot().generation
    const check = store.bind(generation)
    expect(store.secretsFor(generation)?.accessToken).toBe('access-token-a')
    store.signOut()
    expect(store.getSnapshot().phase).toBe('signed-out')
    expect(store.getSnapshot().accountId).toBeNull()
    expect(store.secretsFor(generation)).toBeNull()
    expect(store.secretsFor(store.getSnapshot().generation)).toBeNull()
    expect(check).toThrow(SessionError)
    expect(() => store.unlock()).toThrow(SessionError)
  })

  it('switches A to B without keeping the previous unlock', () => {
    const store = new SessionStore()
    store.signIn(sign())
    store.unlock()
    const previous = store.getSnapshot().generation
    store.signIn(sign(other, 'access-token-b'))
    const view = store.getSnapshot()
    expect(view.phase).toBe('authenticated')
    expect(view.accountId).toBe(other)
    expect(view.generation).not.toBe(previous)
    expect(store.secretsFor(previous)).toBeNull()
    expect(store.secretsFor(view.generation)?.accessToken).toBe('access-token-b')
  })

  it('does not publish tokens on the UI snapshot', () => {
    const store = new SessionStore()
    store.signIn(sign(account, 'super-secret-token'))
    expect(JSON.stringify(store.getSnapshot())).not.toContain('super-secret-token')
    expect(JSON.stringify(store.getSnapshot())).not.toContain('refresh-token')
  })

  it('rejects a malformed account id', () => {
    const store = new SessionStore()
    expect(() => store.signIn(sign('not-a-uuid'))).toThrow(SessionError)
    expect(store.getSnapshot().phase).toBe('signed-out')
  })

  it('changes language without rotating the security generation', () => {
    const store = new SessionStore()
    store.signIn(sign())
    const generation = store.getSnapshot().generation
    store.setLanguage('uk')
    expect(store.getSnapshot().language).toBe('uk')
    expect(store.getSnapshot().generation).toBe(generation)
    expect(store.bind(generation)).not.toThrow()
  })
})
