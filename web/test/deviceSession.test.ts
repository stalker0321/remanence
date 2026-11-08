import { describe, expect, it, vi } from 'vitest'
import { MemoryKeys } from '../src/auth/memoryKeys'
import type { ApiRequest, ApiTransport } from '../src/auth/http'
import { keepDeviceSession, type DeviceSessionPort } from '../src/session/device'
import { SessionStore } from '../src/session/session'

const ACCOUNT = '11111111-2222-4333-8444-555555555555'
const BUNDLE = '66666666-7777-4888-8999-aaaaaaaaaaaa'
const NOW = Date.parse('2026-11-09T12:00:00Z')
const iso = (offsetMs: number) => new Date(NOW + offsetMs).toISOString()

function memoryPort(initial: unknown = null) {
  let value = initial
  const port: DeviceSessionPort & { value: () => unknown } = {
    load: async () => value as never,
    save: async (next) => { value = next },
    clear: async () => { value = null },
    value: () => value,
  }
  return port
}

function saved(accessIn: number, refreshIn = 30 * 86_400_000) {
  return {
    accountId: ACCOUNT, bundleId: BUNDLE, handle: 'vuzol.node',
    secrets: { accessToken: 'pm_at_old', refreshToken: 'pm_rt_old', accessExpiresAt: iso(accessIn), refreshExpiresAt: iso(refreshIn) },
    keys: { accountId: ACCOUNT, bundleId: BUNDLE, recipientKey: {} as CryptoKey, signing: {} as CryptoKey, signingKeyId: 1, encryptionKeyId: 2 },
  }
}

function transport(reply: { status: number; body?: unknown }) {
  const calls: ApiRequest[] = []
  const api: ApiTransport = { request: async (input) => { calls.push(input); return { status: reply.status, body: reply.body ?? null } } }
  return { api, calls }
}

describe('device session', () => {
  it('opens a saved visit unlocked after a reload, without a sign-in', async () => {
    const store = new SessionStore(); const keys = new MemoryKeys(); keys.attach(store)
    const { api, calls } = transport({ status: 500 })
    const stop = await keepDeviceSession(store, keys, api, memoryPort(saved(10 * 60_000)), () => NOW)
    const view = store.getSnapshot()
    expect(view.phase).toBe('keys-unlocked')
    expect(view.handle).toBe('vuzol.node')
    expect(keys.capabilities(view)?.accountId).toBe(ACCOUNT)
    expect(calls).toHaveLength(0)
    stop()
  })

  it('refreshes an expired access token before restoring', async () => {
    const store = new SessionStore(); const keys = new MemoryKeys(); keys.attach(store)
    const port = memoryPort(saved(-60_000))
    const { api, calls } = transport({ status: 200, body: { session_id: ACCOUNT, access_token: 'pm_at_new', refresh_token: 'pm_rt_new', access_expires_at: iso(15 * 60_000), refresh_expires_at: iso(30 * 86_400_000) } })
    const stop = await keepDeviceSession(store, keys, api, port, () => NOW)
    expect(calls[0]).toMatchObject({ path: '/v1/auth/refresh', method: 'POST', body: { refresh_token: 'pm_rt_old' } })
    expect(store.secretsFor(store.getSnapshot().generation)?.accessToken).toBe('pm_at_new')
    await vi.waitFor(() => expect((port.value() as { secrets: { refreshToken: string } }).secrets.refreshToken).toBe('pm_rt_new'))
    stop()
  })

  it('forgets a visit the server no longer accepts', async () => {
    const store = new SessionStore(); const keys = new MemoryKeys(); keys.attach(store)
    const port = memoryPort(saved(-60_000))
    const stop = await keepDeviceSession(store, keys, transport({ status: 401 }).api, port, () => NOW)
    expect(store.getSnapshot().phase).toBe('signed-out')
    expect(port.value()).toBeNull()
    stop()
  })

  it('wipes the device on sign-out', async () => {
    const store = new SessionStore(); const keys = new MemoryKeys(); keys.attach(store)
    const port = memoryPort(saved(10 * 60_000))
    const stop = await keepDeviceSession(store, keys, transport({ status: 500 }).api, port, () => NOW)
    store.signOut()
    await vi.waitFor(() => expect(port.value()).toBeNull())
    expect(keys.capabilities(store.getSnapshot())).toBeNull()
    stop()
  })

  it('keeps the keys held when an opened postcard locks the visit', async () => {
    const store = new SessionStore(); const keys = new MemoryKeys(); keys.attach(store)
    const stop = await keepDeviceSession(store, keys, transport({ status: 500 }).api, memoryPort(saved(10 * 60_000)), () => NOW)
    store.lock()
    await vi.waitFor(() => expect(store.getSnapshot().phase).toBe('keys-unlocked'))
    expect(keys.capabilities(store.getSnapshot())).not.toBeNull()
    stop()
  })
})
