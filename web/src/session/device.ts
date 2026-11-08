import type { DeviceKeys, KeyCapabilities } from '../crypto/keyOwner'
import { deviceCapabilities } from '../crypto/keyOwner'
import type { MemoryKeys } from '../auth/memoryKeys'
import type { ApiTransport } from '../auth/http'
import type { SecretSession, SessionStore } from './session'

/**
 * Keeps this device signed in, like the Android app: the token pair and the
 * account's non-extractable keys live in IndexedDB until sign-out. Key bytes
 * never become readable; the browser only lets this origin use them.
 */
interface Saved {
  accountId: string
  bundleId: string
  handle: string | null
  secrets: SecretSession
  keys: DeviceKeys
}

const DB = 'remanence-device'
const STORE = 'session'
const KEY = 'current'
/** Refresh this long before the access token runs out. */
const AHEAD_MS = 60_000

export interface DeviceSessionPort {
  load(): Promise<Saved | null>
  save(value: Saved): Promise<void>
  clear(): Promise<void>
}

export function indexedDbSession(factory: IDBFactory = indexedDB): DeviceSessionPort {
  const open = () => new Promise<IDBDatabase>((resolve, reject) => {
    const request = factory.open(DB, 1)
    request.onupgradeneeded = () => { request.result.createObjectStore(STORE) }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
  const run = async <T>(mode: IDBTransactionMode, work: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> => {
    const db = await open()
    try {
      return await new Promise<T>((resolve, reject) => {
        const request = work(db.transaction(STORE, mode).objectStore(STORE))
        request.onsuccess = () => resolve(request.result)
        request.onerror = () => reject(request.error)
      })
    } finally { db.close() }
  }
  return {
    load: async () => (await run<Saved | undefined>('readonly', (store) => store.get(KEY) as IDBRequest<Saved | undefined>)) ?? null,
    save: async (value) => { await run('readwrite', (store) => store.put(value, KEY)) },
    clear: async () => { await run('readwrite', (store) => store.delete(KEY)) },
  }
}

interface RefreshReply {
  access_token: string
  refresh_token: string
  access_expires_at: string
  refresh_expires_at: string
}

function readRefresh(body: unknown): SecretSession | null {
  const reply = body as Partial<RefreshReply> | null
  if (!reply || typeof reply.access_token !== 'string' || typeof reply.refresh_token !== 'string' ||
      typeof reply.access_expires_at !== 'string' || typeof reply.refresh_expires_at !== 'string') return null
  return { accessToken: reply.access_token, refreshToken: reply.refresh_token,
    accessExpiresAt: reply.access_expires_at, refreshExpiresAt: reply.refresh_expires_at }
}

/**
 * Restores a saved visit, keeps its access token fresh and mirrors every
 * change to the device store. Returns a stop function.
 */
export async function keepDeviceSession(store: SessionStore, keys: MemoryKeys, transport: ApiTransport,
  port: DeviceSessionPort = indexedDbSession(), now: () => number = Date.now): Promise<() => void> {
  let timer: ReturnType<typeof setTimeout> | null = null
  let stopped = false

  async function refresh(refreshToken: string): Promise<SecretSession | 'rejected' | null> {
    try {
      const reply = await transport.request({ path: '/v1/auth/refresh', method: 'POST', body: { refresh_token: refreshToken } })
      if (reply.status === 200) return readRefresh(reply.body)
      return reply.status === 401 || reply.status === 400 || reply.status === 409 ? 'rejected' : null
    } catch { return null }
  }

  function schedule() {
    if (timer) clearTimeout(timer)
    timer = null
    const view = store.getSnapshot()
    const secrets = store.secretsFor(view.generation)
    if (stopped || !secrets) return
    const wait = Math.max(5_000, Date.parse(secrets.accessExpiresAt) - now() - AHEAD_MS)
    timer = setTimeout(() => { void renew(view.generation, secrets.refreshToken) }, wait)
  }

  async function renew(generation: number, refreshToken: string) {
    const next = await refresh(refreshToken)
    if (stopped || store.getSnapshot().generation !== generation) return
    if (next === 'rejected') { store.signOut(); return }
    if (next === null) { timer = setTimeout(() => { void renew(generation, refreshToken) }, 30_000); return }
    store.rotate(generation, next)
  }

  // The keys this device holds for its signed-in account, if any.
  let held: DeviceKeys | null = null
  const reopen = () => {
    const view = store.getSnapshot()
    if (!held || view.phase !== 'authenticated' || view.accountId !== held.accountId || view.bundleId !== held.bundleId) return
    store.unlock()
    keys.hold(store.getSnapshot(), deviceCapabilities(held))
  }

  // Restore before anything else can sign in.
  try {
    const saved = await port.load()
    if (saved && Date.parse(saved.secrets.refreshExpiresAt) > now()) {
      const fresh = Date.parse(saved.secrets.accessExpiresAt) - now() > AHEAD_MS ? saved.secrets : await refresh(saved.secrets.refreshToken)
      if (fresh && fresh !== 'rejected' && store.getSnapshot().phase === 'signed-out') {
        held = saved.keys
        store.signIn({ accountId: saved.accountId, bundleId: saved.bundleId, handle: saved.handle, ...fresh })
        reopen()
      } else if (fresh === 'rejected') await port.clear()
    } else if (saved) await port.clear()
  } catch { /* A blocked or broken store only means signing in again. */ }

  let last: string | null = null
  const mirror = () => {
    const view = store.getSnapshot()
    const secrets = store.secretsFor(view.generation)
    const caps: KeyCapabilities | null = keys.capabilities(view)
    if (view.phase === 'signed-out' || !secrets) {
      held = null
      if (last !== null) { last = null; void port.clear().catch(() => undefined) }
      if (timer) clearTimeout(timer)
      timer = null
      return
    }
    // Closing an opened postcard locks the visit; on this device the keys stay held.
    if (view.phase === 'authenticated') queueMicrotask(reopen)
    if (!caps?.deviceKeys || view.accountId === null || view.bundleId === null) { schedule(); return }
    const stamp = `${view.accountId}:${view.bundleId}:${view.handle}:${secrets.refreshToken}`
    if (stamp !== last) {
      last = stamp
      held = caps.deviceKeys()
      const value: Saved = { accountId: view.accountId, bundleId: view.bundleId, handle: view.handle, secrets: { ...secrets }, keys: held }
      void port.save(value).catch(() => undefined)
    }
    schedule()
  }
  const unsubscribe = store.subscribe(mirror)
  const unsubscribeKeys = keys.subscribe(mirror)
  mirror()
  return () => { stopped = true; unsubscribe(); unsubscribeKeys(); if (timer) clearTimeout(timer) }
}
