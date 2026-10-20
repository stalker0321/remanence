import { describe, expect, it } from 'vitest'
import type { ApiResult, ApiTransport } from '../src/auth/http'
import { MemoryKeys } from '../src/auth/memoryKeys'
import { PresentationMemory } from '../src/collage/memory'
import { CollageError } from '../src/collage/errors'
import { SessionStore } from '../src/session/session'
import { receivePostcard } from '../src/scan/receive'
import { jsPressCapture, jsPressIds, jsPressMaterial } from './sealedFixture'
import { fitText } from './sheets'

const success = (replay = false): ApiResult => ({ status: 200, body: {
  capsule_id: jsPressIds.capsule, state: 'OPENED', first_opened_at: '2026-10-20T12:00:00Z', is_replay: replay,
} })
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}
async function world() {
  const material = await jsPressMaterial()
  const store = new SessionStore()
  const signIn = () => store.signIn({ accountId: jsPressIds.account, bundleId: jsPressIds.bundle,
    handle: 'ada', accessToken: 'current-bearer', refreshToken: 'synthetic', accessExpiresAt: '', refreshExpiresAt: '' })
  signIn()
  store.unlock()
  const keys = new MemoryKeys()
  keys.attach(store)
  const caps = material.capabilities()
  keys.hold(store.getSnapshot(), caps)
  const urls: string[] = []
  const memory = new PresentationMemory({ create: () => { urls.push('synthetic-url'); return 'memory:sheet' }, revoke: () => {} })
  memory.attach(store)
  const reads: Uint8Array[] = []
  const run = (transport: ApiTransport, controller = new AbortController()) => receivePostcard(store, keys, memory,
    { match: async () => ({ kind: 'one', capsuleId: jsPressIds.capsule }) }, jsPressCapture,
    { items: [material.capsule], hasMore: false, nextCursor: null },
    { read: async (_item, id) => { const bytes = material.ciphertext(id).slice(); reads.push(bytes); return bytes } },
    fitText, controller.signal, undefined, transport)
  return { material, store, keys, caps, urls, memory, reads, run, signIn }
}

describe('ADR-014 prepare then authenticated first-open then presentation', () => {
  it('holds prepared content privately while one claim is pending; accepts exact success and replay', async () => {
    const w = await world()
    const entered = deferred<void>()
    const reply = deferred<ApiResult>()
    let calls = 0
    const opening = w.run({ request: async (input) => {
      calls++
      expect(input).toMatchObject({ method: 'POST', path: `/v1/capsules/${jsPressIds.capsule}/first-open`, token: 'current-bearer' })
      expect(input.body).toBeUndefined()
      expect(w.store.getSnapshot().phase).toBe('postcard-matched')
      expect(w.memory.getSnapshot()).toBeNull()
      expect(w.urls).toHaveLength(0)
      expect(w.reads.length).toBeGreaterThan(1)
      entered.resolve()
      return reply.promise
    } })
    await entered.promise
    expect(calls).toBe(1)
    reply.resolve(success())
    await opening
    expect(w.store.getSnapshot().phase).toBe('presentation-admitted')
    expect(w.memory.getSnapshot()?.photos.size).toBe(3)
    expect(w.reads.every((bytes) => bytes.every((b) => b === 0))).toBe(true)
    // Existing admitted in-memory sheet stays readable without another request.
    expect(w.memory.getSnapshot()).not.toBeNull()
    w.store.lock()
    w.store.unlock()
    w.keys.hold(w.store.getSnapshot(), w.material.capabilities())
    await w.run({ request: async () => { calls++; return success(true) } })
    expect(calls).toBe(2)
    expect(w.memory.getSnapshot()).not.toBeNull()
  })

  for (const fault of ['revoked', 'foreign', 'offline', 'lost', 'bad-shape', 'wrong-id', 'unauthenticated'] as const) {
    it(`does not present cached ciphertext on ${fault}; retry is owned and explicit`, async () => {
      const w = await world()
      let calls = 0
      await expect(w.run({ request: async () => {
        calls++
        if (fault === 'offline' || fault === 'lost') throw new Error('synthetic reply lost')
        if (fault === 'revoked') return { status: 409, body: { status: 409, code: 'CAPSULE_STATE_INVALID' } }
        if (fault === 'foreign') return { status: 404, body: { status: 404, code: 'CAPSULE_NOT_FOUND' } }
        if (fault === 'unauthenticated') return { status: 401, body: {} }
        if (fault === 'wrong-id') return { status: 200, body: { ...success().body as object, capsule_id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' } }
        return { status: 200, body: { ...success().body as object, extra: true } }
      } })).rejects.toBeDefined()
      expect(calls).toBe(1)
      expect(w.store.getSnapshot().phase).toBe('keys-unlocked')
      expect(w.memory.getSnapshot()).toBeNull()
      expect(w.urls).toHaveLength(0)
      expect(w.reads.every((bytes) => bytes.every((b) => b === 0))).toBe(true)
      await w.run({ request: async () => { calls++; return success(true) } })
      expect(calls).toBe(2)
      expect(w.memory.getSnapshot()).not.toBeNull()
    })
  }

  for (const boundary of ['abort', 'logout', 'same-account-reentry', 'owner', 'lock'] as const) {
    it(`late successful admission cannot affect ${boundary}`, async () => {
      const w = await world()
      const controller = new AbortController()
      const reply = deferred<ApiResult>()
      const entered = deferred<void>()
      const opening = w.run({ request: async () => { entered.resolve(); return reply.promise } }, controller)
      const rejected = expect(opening).rejects.toBeDefined()
      await entered.promise
      if (boundary === 'abort') controller.abort()
      else if (boundary === 'lock') w.store.lock()
      else {
        w.store.signOut()
        if (boundary === 'same-account-reentry') { w.signIn(); w.store.unlock(); w.keys.hold(w.store.getSnapshot(), w.material.capabilities()) }
        if (boundary === 'owner') w.store.signIn({ accountId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', bundleId: null, handle: 'other', accessToken: 'new', refreshToken: 'new', accessExpiresAt: '', refreshExpiresAt: '' })
      }
      const newer = w.store.getSnapshot()
      reply.resolve(success())
      await rejected
      if (boundary === 'abort') expect(w.store.getSnapshot().phase).toBe('keys-unlocked')
      else expect(w.store.getSnapshot()).toBe(newer)
      expect(w.urls).toHaveLength(0)
      expect(w.memory.getSnapshot()).toBeNull()
      expect(w.reads.every((bytes) => bytes.every((b) => b === 0))).toBe(true)
    })
  }

  for (const fault of ['prepare', 'abort'] as const) {
    it(`never sends an admission for local ${fault} failure`, async () => {
      const w = await world()
      const controller = new AbortController()
      const real = w.caps.openEnvelope
      w.caps.openEnvelope = async (...args) => {
        if (fault === 'prepare') throw new CollageError('INTEGRITY')
        const result = await real(...args)
        controller.abort()
        return result
      }
      let calls = 0
      await expect(w.run({ request: async () => { calls++; return success() } }, controller)).rejects.toBeDefined()
      expect(calls).toBe(0)
      expect(w.store.getSnapshot().phase).toBe('keys-unlocked')
      expect(w.memory.getSnapshot()).toBeNull()
    })
  }
})
