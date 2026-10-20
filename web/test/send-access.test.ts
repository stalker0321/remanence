import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchTransport, type ApiRequest, type ApiResult } from '../src/auth/http'
import { fetchSendAccess, readSendAccess } from '../src/access/api'
import { sendAccessProblem } from '../src/access/problems'
import { sendFeedback } from '../src/create/sendFeedback'
import { SessionStore } from '../src/session/session'

const owner = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const other = 'bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee'
function signIn(store = new SessionStore(), accountId = owner) {
  store.signIn({ accountId, bundleId: null, handle: 'sender_qa', accessToken: 'synthetic-access', refreshToken: 'synthetic-refresh',
    accessExpiresAt: '2026-10-21T00:00:00Z', refreshExpiresAt: '2026-11-21T00:00:00Z' })
  return store
}
function dto(mode = 'enforce', self = 2, other = 2, lifetime = false) {
  return { policy_version: 1, mode, lifetime,
    free: { self: { limit: 2, used: 2 - self, remaining: self }, other: { limit: 2, used: 2 - other, remaining: other } },
    can_publish: { self: mode === 'record' || lifetime || self > 0, other: mode === 'record' || lifetime || other > 0 } }
}
function problem(status = 409, shape = 'v1') {
  const code = status === 409 ? 'SEND_LIMIT_REACHED' : 'SEND_ACCESS_UNAVAILABLE'
  return shape === 'pending' ? { type: 'about:blank', title: code, status, code } : {
    type: 'https://remanence.invalid/problems/send-access', title: 'Sending access', status, code,
    detail: 'PRIVATE_DETAIL_NOT_RENDERED', request_id: owner, retryable: status === 503,
  }
}
afterEach(() => vi.unstubAllGlobals())
describe('CLIENT_ONLY proposed read boundary', () => {
  it.each([0, 1, 2])('keeps independent SELF=%s and OTHER=0/1/2', (self) => {
    for (const other of [0, 1, 2]) {
      const value = dto('enforce', self, other)
      expect(readSendAccess(value)).toEqual(value)
    }
  })
  it('accepts historical FREE alongside record and lifetime without reset or decrement', () => {
    for (const value of [dto('record', 0, 1), dto('enforce', 0, 0, true), dto('record', 0, 0, true)]) {
      expect(readSendAccess(value)).toEqual(value)
    }
  })
  it.each([
    { policy_version: 2 }, { policy_version: '1' }, { mode: 'off' }, { mode: 'future' }, { lifetime: 1 },
    { can_publish: { self: false, other: true } }, { can_publish: { self: true } },
    { grant_key: 'PRIVATE' }, { free: { self: { limit: 2, used: 0, remaining: 2 } } },
  ])('rejects unsupported/malformed root %j', (patch) => {
    expect(() => readSendAccess({ ...dto(), ...patch })).toThrow()
  })
  it.each([
    { limit: 3, used: 1, remaining: 2 }, { limit: 2, used: -1, remaining: 3 },
    { limit: 2, used: 0, remaining: 1 }, { limit: 2, used: 0.5, remaining: 1.5 },
    { limit: 2, used: '0', remaining: 2 }, { limit: 2, used: NaN, remaining: 2 },
    { limit: 2, used: 0, remaining: Infinity }, { limit: 2, used: 0, remaining: 2, refill: true },
  ])('rejects invalid bucket %j', (bucket) => {
    for (const key of ['self', 'other']) {
      const value = dto()
      expect(() => readSendAccess({ ...value, free: { ...value.free, [key]: bucket } })).toThrow()
    }
  })
  it('rejects record/lifetime denial and enforce allowance inconsistent with remaining', () => {
    for (const value of [dto('record', 0, 0), dto('enforce', 0, 0, true)]) {
      expect(() => readSendAccess({ ...value, can_publish: { self: false, other: true } })).toThrow()
    }
    expect(() => readSendAccess({ ...dto('enforce', 0, 0), can_publish: { self: true, other: true } })).toThrow()
  })
  it('uses one authenticated owner GET;404 is hidden, never new2+2', async () => {
    const request = vi.fn(async (_input: ApiRequest) => ({ status: 404, body: 'ignored' }))
    expect(await fetchSendAccess(signIn(), { request }, new AbortController().signal)).toEqual({ kind: 'hidden' })
    expect(request).toHaveBeenCalledTimes(1)
    expect(request.mock.calls[0]![0]).toMatchObject({ method: 'GET', path: '/v1/me/send-access', token: 'synthetic-access' })
    expect(request.mock.calls[0]![0].body).toBeUndefined()
    expect(request.mock.calls[0]![0].query).toBeUndefined()
  })
  it.each([503, 401, 405, 500])('does not grant allowance on HTTP%s', async (status) => {
    await expect(fetchSendAccess(signIn(), { request: async () => ({ status, body: dto() }) }, new AbortController().signal))
      .rejects.toMatchObject({ kind: 'unavailable' })
  })
  it.each(['owner', 'logout', 'generation', 'abort', 'lease'])('fences late success and failure after %s', async (mode) => {
    for (const success of [true, false]) {
      const store = signIn()
      store.unlock()
      const controller = new AbortController()
      let resolve!: (value: ApiResult) => void
      let reject!: (reason: Error) => void
      const result = fetchSendAccess(store, { request: () => new Promise((ok, fail) => { resolve = ok; reject = fail }) }, controller.signal)
      if (mode === 'owner') signIn(store, other)
      if (mode === 'logout') store.signOut()
      if (mode === 'generation') store.lock()
      if (mode === 'abort') controller.abort()
      if (mode === 'lease') vi.spyOn(store, 'secretsFor').mockReturnValue(null)
      if (success) resolve({ status: 200, body: dto() }); else reject(new Error('PRIVATE'))
      await expect(result).rejects.toMatchObject({ kind: 'stale' })
    }
  })
  it('actual fetch handles HTML404 and keeps anonymous cookies/no-store/redirect fence', async () => {
    const fetch = vi.fn(async () => new Response('<h1>not supported</h1>', { status: 404, headers: { 'content-type': 'text/html' } }))
    vi.stubGlobal('fetch', fetch)
    expect(await fetchSendAccess(signIn(), fetchTransport('https://synthetic.invalid'), new AbortController().signal)).toEqual({ kind: 'hidden' })
    expect(fetch).toHaveBeenCalledWith('https://synthetic.invalid/v1/me/send-access', expect.objectContaining({
      method: 'GET', cache: 'no-store', credentials: 'omit', redirect: 'error',
      headers: expect.objectContaining({ authorization: 'Bearer synthetic-access' }),
    }))
  })
})
describe('authoritative finalize response classification only', () => {
  it.each(['v1', 'pending'])('recognizes both bounded%s errors', (shape) => {
    expect(sendAccessProblem(409, problem(409, shape))).toBe('limit')
    expect(sendAccessProblem(503, problem(503, shape))).toBe('unavailable')
  })
  it.each([
    { status: 500 }, { code: 'UNKNOWN' }, { retryable: true }, { request_id: 'bad' },
    { title: '' }, { detail: 'x'.repeat(1025) }, { secret: 'PRIVATE' },
  ])('does not turn malformed v1%j into confirmed denial', (patch) => {
    expect(sendAccessProblem(409, { ...problem(), ...patch })).toBeNull()
  })
  it('rejects code-only, wrong HTTP, malformed pending and arbitrary exceptions', () => {
    for (const value of [{ code: 'SEND_LIMIT_REACHED' }, new Error('SEND_LIMIT_REACHED'),
      { ...problem(409, 'pending'), type: 'other' }, { ...problem(409, 'pending'), title: 'other' }]) {
      expect(sendAccessProblem(409, value)).toBeNull()
    }
    expect(sendAccessProblem(503, problem())).toBeNull()
    expect(sendAccessProblem(409, problem(503))).toBeNull()
  })
  it('maps outcomes to fixed presentation keys, not balance or retry behavior', () => {
    expect(sendFeedback({ kind: 'access-denied' })).toEqual({ notice: 'sendLimitReached', help: 'sendLimitHelp' })
    expect(sendFeedback({ kind: 'access-unavailable' })).toEqual({ notice: 'sendAccessUnavailable', help: 'sendAccessUnavailableHelp' })
    expect(sendFeedback({ kind: 'unknown', stage: 'finalize', code: 'SEND_LIMIT_REACHED' })?.notice).toBe('sendNotConfirmed')
  })
})
