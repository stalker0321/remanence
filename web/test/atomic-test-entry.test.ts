import { afterEach, describe, expect, it, vi } from 'vitest'
import mainSource from '../src/main.tsx?raw'
import testEntrySource from '../src/atomicTest/main.tsx?raw'
import { configuredAtomicTestAdapters } from '../src/atomicTest/adapters'
import { AtomicTestConfigurationError, loadAtomicTestConfig, parseAtomicTestConfig } from '../src/atomicTest/config'
import type { PasskeyAssertion, PasskeyPort } from '../src/auth/passkey'
import { AuthError } from '../src/auth/errors'

const config = { environment: 'TEST', appOrigin: 'https://test.example.invalid', apiOrigin: 'https://api.example.invalid', rpId: 'example.invalid' }
const parsed = () => parseAtomicTestConfig(config, config.appOrigin, true)
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('dedicated TEST configuration, no ambient/public opt-in', () => {
  it('accepts explicit public origins and existing parent RP without mutating input', () => {
    const result = parsed()
    expect(result).toEqual(config); expect(Object.isFrozen(result)).toBe(true)
    expect(parseAtomicTestConfig({ ...config, appOrigin: 'http://127.0.0.1:4180', apiOrigin: 'http://127.0.0.1:4181', rpId: '127.0.0.1' }, 'http://127.0.0.1:4180', true).rpId).toBe('127.0.0.1')
  })
  it.each([
    null, { environment: 'UNCONFIGURED' }, { ...config, environment: 'PROD' }, { ...config, secret: 'not-a-config-field' },
    { ...config, apiOrigin: '' }, { ...config, apiOrigin: 'https://user:password@api.example.invalid' },
    { ...config, apiOrigin: 'https://api.example.invalid/v1' }, { ...config, apiOrigin: 'http://api.example.invalid' },
    { ...config, apiOrigin: 'http://127.0.0.1:4181' }, { ...config, rpId: 'other.invalid' },
  ])('rejects non-TEST/incomplete/ambiguous target %# before an API or provider exists', value => {
    expect(() => parseAtomicTestConfig(value, config.appOrigin, true)).toThrow(AtomicTestConfigurationError)
  })
  it('refuses another actual origin and an insecure context', () => {
    expect(() => parseAtomicTestConfig(config, 'https://different.example.invalid', true)).toThrow(/configured secure TEST/)
    expect(() => parseAtomicTestConfig(config, config.appOrigin, false)).toThrow(/configured secure TEST/)
  })
  it('loads only same-origin bounded JSON, no cookies/redirect/cache or URL authority override', async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify(config), { headers: { 'content-type': 'application/json' } }))
    vi.stubGlobal('fetch', fetcher)
    expect(await loadAtomicTestConfig(new URL('/test/atomic-test-config.json', config.appOrigin), config.appOrigin, true)).toEqual(config)
    expect(fetcher.mock.calls[0]).toHaveLength(2)
    expect((fetcher.mock.calls as unknown[][])[0]?.[1]).toEqual({ cache: 'no-store', credentials: 'omit', redirect: 'error' })
    await expect(loadAtomicTestConfig(new URL('https://other.invalid/config.json'), config.appOrigin, true)).rejects.toThrow(/entry origin/)
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
  it.each([
    () => new Response('<html>fallback</html>', { headers: { 'content-type': 'text/html' } }),
    () => new Response('not JSON', { headers: { 'content-type': 'application/json' } }),
    () => new Response(' '.repeat(4097), { headers: { 'content-type': 'application/json' } }),
  ])('never treats a missing/invalid/oversized config as defaults %#', async response => {
    vi.stubGlobal('fetch', vi.fn(async () => response()))
    await expect(loadAtomicTestConfig(new URL('/atomic-test-config.json', config.appOrigin), config.appOrigin, true)).rejects.toThrow(AtomicTestConfigurationError)
  })
  it('ordinary entry remains gated; dedicated build has no fixture/provider-success or worker hook', () => {
    expect(mainSource).not.toMatch(/atomicRegistration|atomicTest/)
    expect(testEntrySource).toContain('browserAtomicTestAdapters(config, document, setNotice)')
    expect(testEntrySource).toContain('atomicRegistration: atomicTransport(config.apiOrigin)')
    expect(testEntrySource).not.toMatch(/from ['"].*(?:fixture|\/test\/)|startShell\(/)
  })
})

describe('configured wrappers preserve real adapter semantics', () => {
  function ports() {
    const assertion: PasskeyAssertion = { id: 'AQ', publicResponse: { id: 'AQ', rawId: 'AQ', type: 'public-key', response: {}, clientExtensionResults: {} }, prf: null }
    const google = { requestIdToken: vi.fn(async () => 'explicit-synthetic-test-token') }
    const passkeys: PasskeyPort = { canAuthenticate: vi.fn(() => true), get: vi.fn(async () => assertion), create: vi.fn(async () => assertion) }
    const report = vi.fn()
    return { google, passkeys, report, wrapped: configuredAtomicTestAdapters(parsed(), google, passkeys, report), assertion }
  }
  it('passes the server public client ID/nonce/signal exactly; no eager provider or coupled manager selection', async () => {
    const p = ports(); const abort = new AbortController()
    expect(p.google.requestIdToken).not.toHaveBeenCalled()
    await p.wrapped.google.requestIdToken('test-only.apps.googleusercontent.com', 'server-nonce', abort.signal)
    expect(p.google.requestIdToken).toHaveBeenCalledWith('test-only.apps.googleusercontent.com', 'server-nonce', abort.signal)
    expect(p.passkeys.create).not.toHaveBeenCalled(); expect(p.passkeys.get).not.toHaveBeenCalled()
    expect(p.report).not.toHaveBeenCalled()
  })
  it('invalid public client ID fails before any Google prompt, with safe configuration copy', () => {
    const p = ports()
    expect(() => p.wrapped.google.requestIdToken('https://wrong.invalid/private', 'nonce')).toThrow(AtomicTestConfigurationError)
    expect(p.google.requestIdToken).not.toHaveBeenCalled(); expect(p.report).toHaveBeenCalledTimes(1)
    expect(p.report.mock.calls[0]?.[0]).not.toContain('private')
  })
  it('keeps creation/get options and cancellation signal intact and never fabricates PRF', async () => {
    const p = ports(), abort = new AbortController()
    const create = { rp: { id: config.rpId }, authenticatorSelection: { residentKey: 'required' } }
    const get = { rpId: config.rpId, allowCredentials: [{ id: 'AQ' }], extensions: { prf: {} } }
    expect(await p.wrapped.passkeys.create(create, abort.signal)).toBe(p.assertion)
    expect(await p.wrapped.passkeys.get(get, abort.signal)).toBe(p.assertion)
    expect(p.passkeys.create).toHaveBeenCalledWith(create, abort.signal)
    expect(p.passkeys.get).toHaveBeenCalledWith(get, abort.signal)
    expect(p.assertion.prf).toBeNull(); expect(p.google.requestIdToken).not.toHaveBeenCalled()
  })
  it('mismatched and omitted RP fail before native create/get including existing-account restore', () => {
    const p = ports()
    expect(() => p.wrapped.passkeys.create({ rp: { id: 'other.invalid' } })).toThrow(/RP ID differs/)
    expect(() => p.wrapped.passkeys.get({})).toThrow(/RP ID differs/)
    expect(p.passkeys.create).not.toHaveBeenCalled(); expect(p.passkeys.get).not.toHaveBeenCalled()
  })
  it('propagates provider cancellation and unsupported capability without replacement/fallback', async () => {
    const p = ports(); vi.mocked(p.passkeys.canAuthenticate).mockReturnValue(false)
    expect(p.wrapped.passkeys.canAuthenticate()).toBe(false)
    vi.mocked(p.passkeys.get).mockRejectedValue(new AuthError('PASSKEY_CANCELLED'))
    await expect(p.wrapped.passkeys.get({ rpId: config.rpId })).rejects.toMatchObject({ code: 'PASSKEY_CANCELLED' })
    expect(p.passkeys.create).not.toHaveBeenCalled(); expect(p.report).not.toHaveBeenCalled()
  })
})
