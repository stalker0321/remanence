import { describe, expect, it } from 'vitest'
import { MemoryKeys } from '../src/auth/memoryKeys'
import { PresentationMemory } from '../src/collage/memory'
import { CollageError } from '../src/collage/errors'
import { receivePostcard as receive, type CiphertextPort } from '../src/scan/receive'
import { explainScan } from '../src/scan/notice'
import { unavailableRecognition, type RecognitionEngine } from '../src/scan/grant'
import { SessionStore } from '../src/session/session'
import type { IncomingCapsule, IncomingCatalog } from '../src/sync/incoming'
import { fitText } from './sheets'
import { jsPressCapture, jsPressIds, jsPressMaterial } from './sealedFixture'

// Existing local-receive controls now explicitly supply a synthetic successful
// admission. The dedicated first-open suite exercises denial/unknown/fences.
function receivePostcard(...args: Parameters<typeof receive>): ReturnType<typeof receive> {
  args[10] = { request: async () => ({ status: 200, body: {
    capsule_id: jsPressIds.capsule, state: 'OPENED', first_opened_at: '2026-10-20T12:00:00Z', is_replay: false,
  } }) }
  return receive(...args)
}

function unlocked(): SessionStore {
  const session = new SessionStore()
  session.signIn({
    accountId: jsPressIds.account,
    bundleId: jsPressIds.bundle,
    handle: 'ada',
    accessToken: 'access',
    refreshToken: 'refresh',
    accessExpiresAt: '2026-10-17T00:00:00Z',
    refreshExpiresAt: '2026-11-17T00:00:00Z',
  })
  session.unlock()
  return session
}

function catalog(items: IncomingCapsule[]): IncomingCatalog {
  return { items, hasMore: false, nextCursor: null }
}

function memoryFor(session: SessionStore): PresentationMemory {
  const memory = new PresentationMemory({
    create: () => 'mem:sheet',
    revoke: () => {},
  })
  memory.attach(session)
  return memory
}

describe('receive postcard', () => {
  for (const failure of ['decrypt', 'cancel'] as const) {
    it(`allows an owned retry after ${failure} fails after match admission`, async () => {
      const material = await jsPressMaterial()
      const session = unlocked()
      const keys = new MemoryKeys()
      keys.attach(session)
      const caps = material.capabilities()
      const original = caps.openEnvelope
      const controller = new AbortController()
      let first = true
      caps.openEnvelope = async (...args) => {
        if (first) {
          first = false
          if (failure === 'decrypt') throw new CollageError('INTEGRITY')
          controller.abort()
        }
        return original(...args)
      }
      keys.hold(session.getSnapshot(), caps)
      const memory = memoryFor(session)
      const blobs: CiphertextPort = { read: async (_item, id) => material.ciphertext(id).slice() }
      const engine: RecognitionEngine = { match: async () => ({ kind: 'one', capsuleId: jsPressIds.capsule }) }
      await expect(receivePostcard(session, keys, memory, engine, jsPressCapture, catalog([material.capsule]), blobs, fitText, controller.signal)).rejects.toBeDefined()
      expect(memory.getSnapshot()).toBeNull()
      expect(session.getSnapshot().phase).toBe('keys-unlocked')
      expect(keys.capabilities(session.getSnapshot())).toBe(caps)
      await receivePostcard(session, keys, memory, engine, jsPressCapture, catalog([material.capsule]), blobs, fitText, new AbortController().signal)
      expect(session.getSnapshot().phase).toBe('presentation-admitted')
      expect(memory.getSnapshot()?.photos.size).toBe(3)
    })
  }

  for (const boundary of ['account', 'operation'] as const) {
  it(`a failed old receive never rolls back a newer ${boundary}`, async () => {
    const material = await jsPressMaterial()
    const session = unlocked()
    const keys = new MemoryKeys()
    keys.attach(session)
    let newer: ReturnType<SessionStore['getSnapshot']> | undefined
    const caps = material.capabilities()
    caps.openEnvelope = async () => {
      if (boundary === 'account') {
        session.signOut()
        session.signIn({ accountId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', bundleId: null, handle: 'new', accessToken: 'new', refreshToken: 'new', accessExpiresAt: '', refreshExpiresAt: '' })
      } else {
        session.lock()
      }
      session.unlock()
      session.matchPostcard()
      newer = session.getSnapshot()
      throw new CollageError('INTEGRITY')
    }
    keys.hold(session.getSnapshot(), caps)
    const memory = memoryFor(session)
    await expect(receivePostcard(session, keys, memory,
      { match: async () => ({ kind: 'one', capsuleId: jsPressIds.capsule }) },
      jsPressCapture, catalog([material.capsule]),
      { read: async (_item, id) => material.ciphertext(id).slice() },
      fitText, new AbortController().signal)).rejects.toBeDefined()
    expect(session.getSnapshot()).toBe(newer)
    expect(memory.getSnapshot()).toBeNull()
  })
  }

  it('does not match an empty index or an unavailable engine', async () => {
    const session = unlocked()
    let calls = 0
    const engine: RecognitionEngine = { match: () => { calls += 1; return Promise.resolve({ kind: 'one' }) } }
    await expect(receivePostcard(
      session, new MemoryKeys(), memoryFor(session), engine, jsPressCapture, catalog([]),
      { read: () => Promise.reject(new Error('unread')) }, fitText, new AbortController().signal,
    )).rejects.toMatchObject({ code: 'EMPTY' })
    expect(calls).toBe(0)
    const material = await jsPressMaterial()
    await expect(receivePostcard(
      session, new MemoryKeys(), memoryFor(session), unavailableRecognition(), jsPressCapture,
      catalog([material.capsule]), { read: () => Promise.reject(new Error('unread')) }, fitText, new AbortController().signal,
    )).rejects.toMatchObject({ code: 'RECOGNITION_UNAVAILABLE' })
    expect(session.getSnapshot().phase).toBe('keys-unlocked')
    expect(explainScan(new CollageError('INTEGRITY'))).toBe('integrityFailed')
  })

  it('keeps a recognized postcard closed when the body or the session is gone', async () => {
    const material = await jsPressMaterial()
    const one: RecognitionEngine = { match: () => Promise.resolve({ kind: 'one', capsuleId: jsPressIds.capsule }) }
    const session = unlocked()
    const headless = { ...material.capsule, blobs: [], envelopeCiphertext: new Uint8Array() }
    await expect(receivePostcard(
      session, new MemoryKeys(), memoryFor(session), one, jsPressCapture, catalog([headless]),
      { read: () => Promise.reject(new Error('unread')) }, fitText, new AbortController().signal,
    )).rejects.toMatchObject({ code: 'BODY_MISSING' })
    expect(session.getSnapshot().phase).toBe('keys-unlocked')

    const many: RecognitionEngine = { match: () => Promise.resolve({ kind: 'many' }) }
    await expect(receivePostcard(
      session, new MemoryKeys(), memoryFor(session), many, jsPressCapture, catalog([material.capsule]),
      { read: () => Promise.reject(new Error('unread')) }, fitText, new AbortController().signal,
    )).rejects.toMatchObject({ code: 'AMBIGUOUS' })

    const leaving: RecognitionEngine = {
      match: () => {
        session.signOut()
        return Promise.resolve({ kind: 'one', capsuleId: jsPressIds.capsule })
      },
    }
    await expect(receivePostcard(
      session, new MemoryKeys(), memoryFor(session), leaving, jsPressCapture, catalog([material.capsule]),
      { read: () => Promise.reject(new Error('unread')) }, fitText, new AbortController().signal,
    )).rejects.toMatchObject({ code: 'STALE' })
    expect(session.getSnapshot().phase).toBe('signed-out')
  })

  it('admits a JS-produced sheet only after the grant and drops a late cancel', async () => {
    const material = await jsPressMaterial()
    const session = unlocked()
    const keys = new MemoryKeys()
    keys.hold(session.getSnapshot(), material.capabilities())
    const memory = memoryFor(session)
    const blobs: CiphertextPort = {
      read: (_item, blobId) => Promise.resolve(material.ciphertext(blobId).slice()),
    }
    const engine: RecognitionEngine = { match: () => Promise.resolve({ kind: 'one', capsuleId: jsPressIds.capsule }) }
    await receivePostcard(
      session, keys, memory, engine, jsPressCapture, catalog([material.capsule]), blobs, fitText, new AbortController().signal,
    )
    expect(session.getSnapshot().phase).toBe('presentation-admitted')
    expect(memory.getSnapshot()?.scene.styleId).toBe('press')
    expect(memory.getSnapshot()?.scene.note?.text).toBe('hello')
    expect(memory.getSnapshot()?.photos.size).toBe(3)

    const again = unlocked()
    const againKeys = new MemoryKeys()
    againKeys.hold(again.getSnapshot(), material.capabilities())
    const againMemory = memoryFor(again)
    const controller = new AbortController()
    const late: RecognitionEngine = {
      match: () => {
        controller.abort()
        return Promise.resolve({ kind: 'one', capsuleId: jsPressIds.capsule })
      },
    }
    await expect(receivePostcard(
      again, againKeys, againMemory, late, jsPressCapture, catalog([material.capsule]), blobs, fitText, controller.signal,
    )).rejects.toMatchObject({ code: 'CANCELLED' })
    expect(again.getSnapshot().phase).toBe('keys-unlocked')
    expect(againMemory.getSnapshot()).toBeNull()
  })
})
