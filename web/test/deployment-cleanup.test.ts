/** Actual guarded HTTP ports + controller + SessionStore/MemoryKeys + real
 * identity/readback crypto. Only HTTP/provider results are synthetic fixtures. */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { deploymentPorts } from '../src/deployment/ports'
import { parseDeploymentConfig } from '../src/deployment/config'
import { AtomicRegistration } from '../src/auth/atomicRegistration'
import type { AtomicRequest } from '../src/auth/atomicWire'
import type { ApiRequest } from '../src/auth/http'
import { MemoryKeys } from '../src/auth/memoryKeys'
import { SessionStore } from '../src/session/session'
import { unlockAccount } from '../src/crypto/keyOwner'
import { AtomicFixture, RESERVATION } from './atomicFixture'

const target = { version: 1, atomicRegistration: true, environment: 'TEST', appOrigin: 'https://app.example.invalid', apiOrigin: 'https://api.example.invalid', rpId: 'example.invalid' }
const cleanups: (() => void)[] = []
afterEach(() => { for (const clean of cleanups.splice(0)) clean(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

function context() {
  const fixture = new AtomicFixture(), session = new SessionStore(), keys = new MemoryKeys()
  const detach = keys.attach(session), state = { online: true, sameController: true }
  const requests: { path: string; token: string; signal?: AbortSignal | null }[] = []
  const fetcher = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
    const path = new URL(String(url)).pathname, headers = new Headers(init?.headers)
    const token = (headers.get('authorization') ?? '').replace(/^Bearer /, '')
    requests.push({ path, token, signal: init?.signal })
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
    let result
    if (path.startsWith('/v1/auth/google/')) result = await fixture.setup.request({ action: path.slice('/v1/auth/'.length), body, signal: init?.signal } as AtomicRequest)
    else if (path.startsWith('/v2/registration/reservations/')) {
      const [reservation, ...parts] = path.slice('/v2/registration/reservations/'.length).split('/')
      result = await fixture.setup.request({ action: parts.join('/') || 'status', reservation, token, body, signal: init?.signal } as AtomicRequest)
    } else result = await fixture.api.request({ path, method: init?.method ?? 'GET', token, body, signal: init?.signal } as ApiRequest)
    return new Response(JSON.stringify(result.body), { status: result.status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } })
  })
  vi.stubGlobal('fetch', fetcher)
  const report = vi.fn(), ports = deploymentPorts(parseDeploymentConfig(target, target.appOrigin, true), () => state.online && state.sameController, report, {} as Document)
  const controller = new AtomicRegistration({ session, keys, setup: ports.atomicRegistration!, api: ports.transport, google: fixture.google, passkeys: fixture.passkeys })
  cleanups.push(() => { state.online = state.sameController = true; controller.dispose(); session.signOut(); keys.drop(); detach() })
  return { fixture, session, keys, state, fetcher, requests, report, controller }
}
async function ready(c = context()) {
  await c.controller.begin(); await c.controller.save('synthetic_owner')
  expect(c.controller.getSnapshot().phase).toBe('ready')
  expect(c.session.getSnapshot().phase).toBe('keys-unlocked')
  expect(c.keys.capabilities(c.session.getSnapshot())).not.toBeNull()
  return c
}
// Same event ordering as actual AtomicAccount; actual first-click browser tests
// additionally execute the real React handler, not this test convenience function.
const firstSignOut = (c: ReturnType<typeof context>) => { c.controller.leave(); c.session.signOut() }
function signedOut(c: ReturnType<typeof context>, old: ReturnType<SessionStore['getSnapshot']>) {
  expect(c.session.getSnapshot()).toMatchObject({ phase: 'signed-out', accountId: null, bundleId: null, handle: null })
  expect(c.session.secretsFor(old.generation)).toBeNull()
  expect(c.session.secretsFor(c.session.getSnapshot().generation)).toBeNull()
  expect(c.keys.capabilities(old)).toBeNull()
  expect(c.keys.capabilities(c.session.getSnapshot())).toBeNull()
  expect(c.controller.getSnapshot().phase).toBe('idle')
  expect(c.fixture.secrets.every(bytes => bytes.every(value => value === 0))).toBe(true)
}
function hold(c: ReturnType<typeof context>, point: string) {
  let release!: () => void, arrived!: () => void
  const reached = new Promise<void>(done => { arrived = done }), pending = new Promise<void>(done => { release = done })
  c.fixture.pause = async where => { if (where === point) { arrived(); await pending } }
  return { reached, release }
}

describe('local retirement survives guarded normal-PWA network admission', () => {
  it.each(['online', 'sameController'] as const)('FIRST sign-out from real READY with denied %s clears secrets and locks even retained capability references', async denial => {
    const c = await ready(), old = c.session.getSnapshot(), caps = c.keys.capabilities(old)!, before = c.requests.length
    c.state[denial] = false
    expect(() => firstSignOut(c)).not.toThrow()
    signedOut(c, old)
    await expect(caps.sign(new Uint8Array([1]))).rejects.toMatchObject({ code: 'LOCKED' })
    expect(c.requests).toHaveLength(before); expect(c.report).toHaveBeenCalledTimes(1)
    expect(c.fixture.provisions).toBe(1); expect(c.fixture.reservation.state).toBe('ACTIVE')
  })
  it('online first sign-out clears local state without waiting for exact-old-token leave reply', async () => {
    const c = await ready(), old = c.session.getSnapshot(), gate = hold(c, 'leave:after')
    expect(() => firstSignOut(c)).not.toThrow(); signedOut(c, old)
    await gate.reached
    const request = c.requests.at(-1)!
    expect(request.path).toBe(`/v2/registration/reservations/${RESERVATION}/leave`)
    expect(request.token).toBe(`rgs_${'A'.repeat(43)}`)
    expect(c.fixture.provisions).toBe(1); gate.release()
  })
  it('offline lock drops capability but retains authenticated session until first explicit sign-out', async () => {
    const c = await ready(), old = c.session.getSnapshot(), caps = c.keys.capabilities(old)!, before = c.requests.length
    c.state.online = false; c.session.lock()
    expect(c.session.getSnapshot().phase).toBe('authenticated')
    expect(c.session.secretsFor(c.session.getSnapshot().generation)).not.toBeNull()
    expect(c.keys.capabilities(old)).toBeNull()
    await expect(caps.sign(new Uint8Array([1]))).rejects.toMatchObject({ code: 'LOCKED' })
    expect(() => firstSignOut(c)).not.toThrow(); signedOut(c, old); expect(c.requests).toHaveLength(before)
  })
  it('best-effort remote leave remains bounded to 5s independently of completed local retirement', async () => {
    const c = await ready(), old = c.session.getSnapshot(), gate = hold(c, 'leave:after')
    vi.useFakeTimers(); firstSignOut(c); signedOut(c, old); await gate.reached
    const signal = c.requests.at(-1)!.signal!
    expect(signal.aborted).toBe(false); await vi.advanceTimersByTimeAsync(5000)
    expect(signal.aborted).toBe(true); gate.release(); await vi.advanceTimersByTimeAsync(0)
    expect(vi.getTimerCount()).toBe(0); signedOut(c, old)
  })
  it.each(['same', 'different'] as const)('late old leave cannot wipe %s replacement session/token/real unlocked capability', async kind => {
    const c = await ready(), old = c.session.getSnapshot(), gate = hold(c, 'leave:after')
    firstSignOut(c); await gate.reached
    const next = kind === 'different' ? context() : null
    if (next) { next.fixture.reservation.account_id = RESERVATION; await ready(next) }
    const secret = new Uint8Array(32).fill(7)
    const replacement = await unlockAccount((next?.fixture ?? c.fixture).reply(), secret); secret.fill(0)
    const accountId = kind === 'same' ? old.accountId! : RESERVATION
    c.session.signIn({ accountId, bundleId: old.bundleId, handle: 'replacement', accessToken: 'new-access', refreshToken: 'new-refresh', accessExpiresAt: '', refreshExpiresAt: '' })
    c.session.unlock(); c.keys.hold(c.session.getSnapshot(), replacement)
    const generation = c.session.getSnapshot().generation
    gate.release(); await new Promise(done => setTimeout(done, 0))
    expect(c.session.getSnapshot()).toMatchObject({ generation, phase: 'keys-unlocked', accountId })
    expect(c.session.secretsFor(generation)?.refreshToken).toBe('new-refresh')
    expect(c.keys.capabilities(c.session.getSnapshot())).toBe(replacement)
    expect((await replacement.sign(new Uint8Array([1]))).length).toBeGreaterThan(0)
  })
  it('same-account session refresh retires old controller without clearing newer tokens on later disposal', async () => {
    const c = await ready(), old = c.session.getSnapshot(), before = c.requests.length
    c.state.online = false
    c.session.signIn({ accountId: old.accountId!, bundleId: old.bundleId, handle: old.handle, accessToken: 'refreshed-access', refreshToken: 'refreshed-refresh', accessExpiresAt: '', refreshExpiresAt: '' })
    expect(c.controller.getSnapshot().phase).toBe('idle'); expect(c.keys.capabilities(old)).toBeNull()
    c.controller.dispose()
    expect(c.session.secretsFor(c.session.getSnapshot().generation)?.refreshToken).toBe('refreshed-refresh')
    expect(c.requests).toHaveLength(before)
  })
  it.each(['passkey/get', 'record:after'])('offline leave during %s fences late work and wipes only the retired candidate', async point => {
    const c = context(); await c.controller.begin(); const gate = hold(c, point), pending = c.controller.save('synthetic_owner')
    await gate.reached; const before = c.requests.length, old = c.session.getSnapshot(); c.state.online = false
    expect(() => c.controller.leave()).not.toThrow(); gate.release(); await pending
    signedOut(c, old); expect(c.requests).toHaveLength(before)
    expect(c.fixture.provisions).toBe(point === 'record:after' ? 1 : 0)
  })
})
