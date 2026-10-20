import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchTransport, type ApiRequest, type ApiResult } from '../src/auth/http'
import { SessionStore } from '../src/session/session'
import { cancelOutgoing, outgoing, readHistory, readRecent, recentRecipients, type HistoryItem } from '../src/history/api'

const owner = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const other = 'bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const capsule = 'cccccccc-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const delivery = 'dddddddd-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const time = '2026-10-20T12:00:00+00:00'
function signIn(store = new SessionStore(), accountId = owner) {
  store.signIn({ accountId, bundleId: capsule, handle: 'sender_qa', accessToken: 'synthetic-token', refreshToken: 'synthetic-refresh',
    accessExpiresAt: time, refreshExpiresAt: time })
  return store
}
function row(): HistoryItem {
  return { capsule_id: capsule, protocol_version: 1, delivery_id: null, status: 'READY',
    created_at: time, published_at: time, expires_at: null, first_opened_at: null, revoked_at: null,
    recipient: { user_id: other, handle: 'target_qa', hint: null, is_self: false },
    locator: { status: 'ACTIVE', code: 'AB12Z' }, cancel_action: 'REVOKE_V1', cancel_deadline: time }
}
function page(items: unknown[] = [row()]) { return { items, has_more: false, next_cursor: null } }
const signal = () => new AbortController().signal
afterEach(() => vi.unstubAllGlobals())

describe('sealed outgoing metadata boundary', () => {
  it('accepts authenticated locked-key metadata and strips no secret into a client model', async () => {
    const store = signIn()
    const request = vi.fn(async (input: ApiRequest) => {
      expect(input).toMatchObject({ path: '/v1/capsules/outgoing', method: 'GET', token: 'synthetic-token', query: { cursor: 'opaque' } })
      return { status: 200, body: page() }
    })
    expect((await outgoing(store, { request }, 'opaque', signal())).items).toEqual([row()])
    expect(store.getSnapshot().phase).toBe('authenticated')
    expect(() => readHistory(page([{ ...row(), vault_ciphertext: 'PRIVATE' }]), owner)).toThrow()
  })
  it.each(['FUTURE', null])('fails closed on unknown status %s', (status) => {
    const item = readHistory(page([{ ...row(), status }]), owner).items[0]!
    expect(item.status).toBe('UNSUPPORTED')
    expect(item.cancel_action).toBeNull()
    expect(item.locator.code).toBeNull()
  })
  it('fails closed on unknown action', () => {
    expect(readHistory(page([{ ...row(), cancel_action: 'NEW' }]), owner).items[0]).toMatchObject({
      status: 'UNSUPPORTED', cancel_action: null, locator: { code: null },
    })
  })
  it.each([
    { locator: { status: 'RETIRED_OPEN', code: 'AB12Z' } },
    { locator: { status: 'ACTIVE', code: 'not-a-code' } },
    { status: 'OPENED', first_opened_at: time },
    { status: 'DRAFT' },
    { delivery_id: delivery },
    { recipient: { user_id: owner, handle: 'sender_qa', hint: null, is_self: false } },
    { created_at: 'yesterday' },
  ])('rejects incoherent v1 DTO %j', (patch) => {
    expect(() => readHistory(page([{ ...row(), ...patch }]), owner)).toThrow()
  })
  it('accepts pending without inventing publication or reusable identity', () => {
    const pending = { ...row(), protocol_version: 2, delivery_id: delivery, status: 'PENDING',
      published_at: null, cancel_deadline: null, cancel_action: 'CANCEL_PENDING_V2',
      recipient: { user_id: null, handle: null, hint: 'r•••@•••', is_self: null } }
    expect(readHistory(page([pending]), owner).items[0]).toEqual(pending)
    expect(() => readHistory(page([{ ...pending, recipient: row().recipient }]), owner)).toThrow()
    expect(() => readHistory(page([{ ...pending, published_at: time }]), owner)).toThrow()
  })
  it('rejects duplicate identities, oversized pages, bad cursors and pagination mismatch', () => {
    for (const bad of [page([row(), row()]), page(Array(21).fill(row())),
      { ...page(), has_more: true }, { ...page(), next_cursor: 'abc' },
      { ...page(), has_more: true, next_cursor: '../bad' }, { items: [], has_more: true, next_cursor: 'abc' }]) {
      expect(() => readHistory(bad, owner)).toThrow()
    }
  })
  it.each([404, 405, 503, 401])('does not translate HTTP %s into empty', async (status) => {
    await expect(outgoing(signIn(), { request: async () => ({ status, body: {} }) }, null, signal()))
      .rejects.toMatchObject({ kind: status === 401 ? 'auth' : 'unavailable' })
  })
  it.each(['logout', 'owner', 'lock', 'abort'])('rejects late transport that ignores %s', async (mode) => {
    const store = signIn()
    store.unlock()
    const controller = new AbortController()
    let resolve!: (result: ApiResult) => void
    const result = outgoing(store, { request: () => new Promise((done) => { resolve = done }) }, null, controller.signal)
    if (mode === 'logout') store.signOut()
    if (mode === 'owner') signIn(store, other)
    if (mode === 'lock') store.lock()
    if (mode === 'abort') controller.abort()
    resolve({ status: 200, body: page() })
    await expect(result).rejects.toMatchObject({ kind: 'stale' })
  })
  it('rejects recent self/duplicate/invalid identities and uses separate authenticated GET', async () => {
    const recent = { user_id: other, handle: 'target_qa', last_sent_at: time }
    expect(readRecent({ items: [recent] }, owner)).toEqual([recent])
    for (const items of [[recent, recent], [{ ...recent, user_id: owner }], [{ ...recent, handle: '@target_qa' }]]) {
      expect(() => readRecent({ items }, owner)).toThrow()
    }
    const request = vi.fn(async () => ({ status: 200, body: { items: [recent] } }))
    expect(await recentRecipients(signIn(), { request }, signal())).toEqual([recent])
    expect(request).toHaveBeenCalledWith(expect.objectContaining({ path: '/v1/recipients/recent', token: 'synthetic-token', method: 'GET' }))
  })
})

describe('existing cancellation contracts, no retry', () => {
  it.each([1, 2])('sends only the existing v%s action with no body or retry key', async (version) => {
    const item = row()
    if (version === 2) Object.assign(item, { protocol_version: 2, delivery_id: delivery, cancel_action: 'CANCEL_PENDING_V2' })
    const request = vi.fn(async () => ({ status: 200, body: version === 1
      ? { capsule_id: capsule, state: 'REVOKED', is_replay: false }
      : { delivery_id: delivery, state: 'CANCELLED' } }))
    await cancelOutgoing(signIn(), { request }, item, signal())
    expect(request).toHaveBeenCalledTimes(1)
    expect(request).toHaveBeenCalledWith(expect.objectContaining({
      method: 'POST', path: version === 1 ? `/v1/capsules/${capsule}/revoke` : `/v2/pending-deliveries/${delivery}/cancel`,
    }))
    const sent = request.mock.calls[0] as unknown as [ApiRequest]
    expect(sent[0].body).toBeUndefined()
    expect(sent[0].idempotencyKey).toBeUndefined()
  })
  it.each([409, 401, 500, 404, 200])('is honest about failed/malformed %s responses', async (status) => {
    const request = vi.fn(async () => ({ status, body: { state: 'CANCELLED' } }))
    await expect(cancelOutgoing(signIn(), { request }, row(), signal())).rejects.toMatchObject({
      kind: status === 409 ? 'conflict' : status === 401 ? 'auth' : 'unknown',
    })
    expect(request).toHaveBeenCalledTimes(1)
  })
  it('transport failure is unknown; never an implicit second POST', async () => {
    const request = vi.fn(async () => { throw new Error('PRIVATE') })
    await expect(cancelOutgoing(signIn(), { request }, row(), signal())).rejects.toMatchObject({ kind: 'unknown' })
    expect(request).toHaveBeenCalledTimes(1)
  })
  it('actual fetch transport permits only exact v2 cancel and keeps responses no-store', async () => {
    const fetch = vi.fn(async () => new Response(JSON.stringify({ delivery_id: delivery, state: 'CANCELLED' })))
    vi.stubGlobal('fetch', fetch)
    const transport = fetchTransport('https://synthetic.invalid')
    await transport.request({ method: 'POST', path: `/v2/pending-deliveries/${delivery}/cancel`, token: 'synthetic-token' })
    expect(fetch).toHaveBeenCalledWith(`https://synthetic.invalid/v2/pending-deliveries/${delivery}/cancel`, expect.objectContaining({ cache: 'no-store', credentials: 'omit', redirect: 'error' }))
    for (const path of [`/v2/pending-deliveries/${delivery}/commit`, `/v2/pending-deliveries/../cancel`, '/v2/anything']) {
      await expect(transport.request({ method: 'POST', path })).rejects.toThrow()
    }
    await expect(transport.request({ method: 'GET', path: `/v2/pending-deliveries/${delivery}/cancel` })).rejects.toThrow()
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})
