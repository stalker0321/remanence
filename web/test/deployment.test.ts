import { afterEach, describe, expect, it, vi } from 'vitest'
import { DeploymentConfigurationError, disabledDeployment, loadDeploymentConfig, parseDeploymentConfig } from '../src/deployment/config'
import { verifyDeploymentWorker } from '../src/deployment/worker'
import { deploymentPorts } from '../src/deployment/ports'

const target = { version: 1, atomicRegistration: true, environment: 'DEV', appOrigin: 'https://app.example.invalid', apiOrigin: 'https://api.example.invalid', rpId: 'example.invalid' }
const url = new URL('/app-config.json', target.appOrigin)
const response = (body: unknown = target, headers: Record<string, string> = {}) => new Response(JSON.stringify(body), { headers: { 'content-type': 'application/json', 'cache-control': 'no-store', ...headers } })
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers() })

describe('normal deployment opt-in, independent of dedicated TEST guard', () => {
  it('accepts only explicit DEV/TEST opt-in and validates a disabled full target too', () => {
    for (const environment of ['DEV', 'TEST']) for (const atomicRegistration of [false, true]) {
      const input = { ...target, environment, atomicRegistration }
      const result = parseDeploymentConfig(input, target.appOrigin, true)
      expect(result).toEqual(input); expect(Object.isFrozen(result)).toBe(true)
    }
    expect(parseDeploymentConfig({ version: 1, atomicRegistration: false }, target.appOrigin, true)).toBe(disabledDeployment)
  })
  it.each([
    null, {}, { version: 1, atomicRegistration: true }, { ...target, environment: 'PROD' },
    { ...target, version: 2 }, { ...target, atomicRegistration: 'true' }, { ...target, googleClientId: 'ambient' },
    { ...target, appOrigin: 'https://other.invalid' }, { ...target, apiOrigin: 'https://api.example.invalid/path' },
    { ...target, apiOrigin: 'http://api.example.invalid' }, { ...target, rpId: 'other.invalid' },
    { ...target, atomicRegistration: false, rpId: 'other.invalid' },
  ])('fails closed before ports for invalid configuration %#', input => {
    expect(() => parseDeploymentConfig(input, target.appOrigin, true)).toThrow(DeploymentConfigurationError)
  })
  it('rejects insecure contexts; permits only the existing explicit loopback exception', () => {
    expect(() => parseDeploymentConfig(target, target.appOrigin, false)).toThrow(DeploymentConfigurationError)
    expect(parseDeploymentConfig({ ...target, appOrigin: 'http://127.0.0.1:4180', apiOrigin: 'http://127.0.0.1:4181', rpId: '127.0.0.1' }, 'http://127.0.0.1:4180', true)).toMatchObject({ rpId: '127.0.0.1' })
  })
  it('loads fresh anonymous nonredirecting JSON; no stored/URL override', async () => {
    const fetcher = vi.fn(async () => response()); vi.stubGlobal('fetch', fetcher)
    expect(await loadDeploymentConfig(url, target.appOrigin, true)).toEqual(target)
    expect((fetcher.mock.calls as unknown[][])[0]?.[1]).toMatchObject({ cache: 'no-store', credentials: 'omit', redirect: 'error', signal: expect.any(AbortSignal) })
    await expect(loadDeploymentConfig(new URL(`${url}?enabled=1`), target.appOrigin, true)).rejects.toThrow()
    await expect(loadDeploymentConfig(new URL('https://other.invalid/app-config.json'), target.appOrigin, true)).rejects.toThrow()
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
  it('missing config is registration-disabled, not an implicit atomic or legacy registration', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 404 })))
    expect(await loadDeploymentConfig(url, target.appOrigin, true)).toBe(disabledDeployment)
  })
  it.each([
    () => response(target, { 'cache-control': 'max-age=60' }),
    () => response(target, { age: '3' }),
    () => response(target, { 'content-type': 'text/html' }),
    () => response('x'.repeat(4097)),
    () => new Response('not JSON', { headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } }),
    () => new Response('', { status: 503 }),
    () => { throw new Error('offline') },
  ])('rejects cache/error/unbounded config instead of using an old target %#', async factory => {
    vi.stubGlobal('fetch', vi.fn(async () => factory()))
    await expect(loadDeploymentConfig(url, target.appOrigin, true)).rejects.toThrow(DeploymentConfigurationError)
  })
  it('aborts a stalled fetch and never enables setup', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn((_url, init) => new Promise((_resolve, reject) => init.signal.addEventListener('abort', () => reject(new Error('abort'))))))
    const check = expect(loadDeploymentConfig(url, target.appOrigin, true)).rejects.toThrow(DeploymentConfigurationError)
    await vi.advanceTimersByTimeAsync(3000); await check
  })
})

describe('normal-entry worker compatibility, not forced worker replacement', () => {
  const worker = (postMessage: (...args: unknown[]) => void, rest = {}) => ({ scriptURL: `${target.appOrigin}/sw.js`, state: 'activated', postMessage, ...rest } as unknown as ServiceWorker)
  it('allows an uncontrolled document; unrelated/activating workers cannot enable setup', async () => {
    await expect(verifyDeploymentWorker('/', target.appOrigin, null)).resolves.toBeUndefined()
    const post = vi.fn()
    for (const rest of [{ scriptURL: `${target.appOrigin}/old.js` }, { state: 'activating' }]) {
      await expect(verifyDeploymentWorker('/', target.appOrigin, worker(post, rest))).rejects.toThrow()
    }
    expect(post).not.toHaveBeenCalled()
  })
  it('requires the matching static-only base declaration', async () => {
    for (const valid of [true, false]) {
      const post = (_message: unknown, ports: unknown) => (ports as MessagePort[])[0]!.postMessage({ type: 'REMANENCE_STATIC_POLICY_V1', base: valid ? '/' : '/other/', runtimeCache: false, configNetworkOnly: true })
      const promise = verifyDeploymentWorker('/', target.appOrigin, worker(post))
      if (valid) await expect(promise).resolves.toBeUndefined()
      else await expect(promise).rejects.toThrow()
    }
  })
  it('old silent worker times out, without unregister, cache deletion or activation', async () => {
    vi.useFakeTimers(); const post = vi.fn()
    const check = expect(verifyDeploymentWorker('/', target.appOrigin, worker(post))).rejects.toThrow()
    await vi.advanceTimersByTimeAsync(1000); await check
    expect(post).toHaveBeenCalledTimes(1)
  })
})

describe('normal entry guarded existing ports', () => {
  const doc = {} as Document
  it('does not expose atomic setup without explicit configuration', () => {
    expect(deploymentPorts(disabledDeployment, () => true, vi.fn(), doc).atomicRegistration).toBeUndefined()
    expect(deploymentPorts(null, () => true, vi.fn(), doc).atomicRegistration).toBeUndefined()
  })
  it('invalid/offline/controller-changed config cannot issue HTTP/provider/native requests', async () => {
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher)
    for (const [config, allowed] of [[null, true], [parseDeploymentConfig(target, target.appOrigin, true), false]] as const) {
      const report = vi.fn(), ports = deploymentPorts(config, () => allowed, report, doc)
      expect(ports.passkeys.canAuthenticate()).toBe(false)
      expect(() => ports.transport.request({ method: 'GET', path: '/v1/me' })).toThrow()
      expect(() => ports.transport.requestBytes({ path: '/v1/me/key-backup', maxBytes: 10 })).toThrow()
      await expect(ports.google.requestIdToken('client', 'nonce')).rejects.toThrow()
      await expect(ports.passkeys.get({})).rejects.toThrow()
      if (ports.atomicRegistration) expect(() => ports.atomicRegistration!.request({ action: 'google/options', body: {} })).toThrow()
      expect(report).toHaveBeenCalled()
    }
    expect(fetcher).not.toHaveBeenCalled()
  })
})
