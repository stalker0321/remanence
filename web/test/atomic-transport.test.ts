import { describe, expect, it, vi } from 'vitest'
import { atomicTransport, type AtomicRequest, reservation, setupReply } from '../src/auth/atomicWire'
import { fetchTransport } from '../src/auth/http'
import { AtomicFixture, RESERVATION } from './atomicFixture'

const token = `rgs_${'A'.repeat(43)}`
const json = (body: unknown = {}, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } })
describe('isolated atomic exact transport', () => {
  it('binds exact routes, fixed headers, no cookies, no cache, no redirects, no query/idempotency escape', async () => {
    const send = vi.fn(async () => json())
    const port = atomicTransport('https://example.test', send)
    await port.request({ action: 'google/verify', body: { flow: RESERVATION, nonce: 'A'.repeat(43), id_token: 'synthetic', onboarding_version: 1 } })
    await port.request({ action: 'status', reservation: RESERVATION, token })
    await port.request({ action: 'finalize', reservation: RESERVATION, token, body: { epoch: 1 } })
    expect(send.mock.calls[0]).toMatchObject(['https://example.test/v1/auth/google/verify', { method: 'POST', credentials: 'omit', redirect: 'error', cache: 'no-store' }])
    expect(send.mock.calls[1]).toEqual([`https://example.test/v2/registration/reservations/${RESERVATION}`, {
      method: 'GET', headers: { accept: 'application/json', 'x-remanence-profile-version': '2', authorization: `Bearer ${token}` }, body: undefined,
      credentials: 'omit', redirect: 'error', cache: 'no-store', signal: undefined,
    }])
    expect(send.mock.calls[2]).toMatchObject([`https://example.test/v2/registration/reservations/${RESERVATION}/finalize`, { method: 'POST' }])
  })
  it.each(['../finalize', 'status?x=1', 'finalize#x', 'PUT', 'google/else'])('refuses out-of-surface action %s before fetch', async (action) => {
    const send = vi.fn(async () => json())
    await expect(atomicTransport('', send).request({ action, reservation: RESERVATION, token, body: {} } as AtomicRequest)).rejects.toMatchObject({ code: 'TRANSPORT_FAILED' })
    expect(send).not.toHaveBeenCalled()
  })
  it.each(['https://example.test/path', 'https://u:p@example.test', 'https://example.test/?query=x', 'https://example.test/#x', 'javascript:alert(1)'])('refuses unsafe origin %s', (origin) => {
    expect(() => atomicTransport(origin)).toThrow()
  })
  it('rejects noncanonical reservation and ordinary bearer instead of setup scope', async () => {
    const send = vi.fn(async () => json()); const port = atomicTransport('', send)
    for (const input of [{ reservation: RESERVATION.toUpperCase(), token }, { reservation: RESERVATION, token: 'pm_at_ordinary' }, { reservation: `${RESERVATION}/cancel`, token }]) {
      await expect(port.request({ action: 'status', ...input })).rejects.toMatchObject({ code: 'TRANSPORT_FAILED' })
    }
    expect(send).not.toHaveBeenCalled()
  })
  it('bounds request and streamed response, rejects HTML/malformed JSON and cancels streams', async () => {
    const send = vi.fn(async () => json()); const port = atomicTransport('', send)
    await expect(port.request({ action: 'finalize', reservation: RESERVATION, token, body: { blob: 'x'.repeat(262144) } })).rejects.toMatchObject({ code: 'TRANSPORT_FAILED' })
    expect(send).not.toHaveBeenCalled()
    for (const response of [new Response('<html>', { status: 404 }), json('x'.repeat(262144)), new Response('{', { headers: { 'content-type': 'application/json' } })]) {
      await expect(atomicTransport('', async () => response).request({ action: 'status', reservation: RESERVATION, token })).rejects.toMatchObject({ code: 'TRANSPORT_FAILED' })
    }
  })
  it('fences abort before fetch, after response, and while body resolves', async () => {
    const aborted = new AbortController(); aborted.abort(); const send = vi.fn(async () => json())
    await expect(atomicTransport('', send).request({ action: 'status', reservation: RESERVATION, token, signal: aborted.signal })).rejects.toMatchObject({ code: 'CANCELLED' })
    expect(send).not.toHaveBeenCalled()
    for (const boundary of ['response', 'body']) {
      const abort = new AbortController()
      const port = atomicTransport('', async () => {
        if (boundary === 'response') { abort.abort(); return json() }
        return new Response(new ReadableStream({ pull(controller) { abort.abort(); controller.enqueue(new TextEncoder().encode('{}')); controller.close() } }), { headers: { 'content-type': 'application/json' } })
      })
      await expect(port.request({ action: 'status', reservation: RESERVATION, token, signal: abort.signal })).rejects.toMatchObject({ code: 'CANCELLED' })
    }
  })
  it('does not broaden generic v2 GET or idempotency header allowance', async () => {
    const original = globalThis.fetch; const send = vi.fn(async () => json()); globalThis.fetch = send
    try {
      await expect(fetchTransport('').request({ method: 'GET', path: `/v2/registration/reservations/${RESERVATION}` })).rejects.toThrow()
      await expect(fetchTransport('').request({ method: 'POST', path: '/v1/auth/google/verify', idempotencyKey: RESERVATION })).rejects.toThrow()
      expect(send).not.toHaveBeenCalled()
    } finally { globalThis.fetch = original }
  })
  it('fails closed on malformed reservations and never exposes setup bearer in public state', () => {
    const f = new AtomicFixture()
    expect(reservation(f.reservation)).toEqual(f.reservation)
    for (const patch of [{ epoch: true }, { epoch: 0 }, { state: 'SEALED' }, { account_id: 'other' }, { credential_ids: ['AQ=='] }, { outcome: undefined }, { restarted_into: undefined }]) {
      expect(() => reservation({ ...f.reservation, ...patch })).toThrow()
    }
    expect(() => setupReply({ status: 'SETUP_REQUIRED', reservation: f.reservation, setup_token: 'pm_at_test', setup_session_id: RESERVATION, setup_expires_at: '2031-01-01T00:00:00Z' })).toThrow()
  })
})
