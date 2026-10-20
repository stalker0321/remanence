import { describe, expect, it } from 'vitest'
import { MemoryKeys } from '../src/auth/memoryKeys'
import { PresentationMemory, showGrantedPostcard } from '../src/collage/memory'
import { CollageError } from '../src/collage/errors'
import { openGrantedPostcard } from '../src/collage/open'
import { parseContentManifest } from '../src/collage/wire'
import { concat, utf8 } from '../src/protocol/bytes'
import { ProtocolError } from '../src/protocol/errors'
import { protoBytes, protoVarint } from '../src/protocol/proto'
import { uuidToBytes } from '../src/protocol/uuid'
import { ScanGrant } from '../src/scan/grant'
import { SessionStore } from '../src/session/session'
import type { IncomingCapsule } from '../src/sync/incoming'
import { fitText } from './sheets'
import { jsPressIds, jsPressMaterial, jsPressPlain } from './sealedFixture'

// The sealed bytes come from the JS producer in sealedFixture.ts. They are not Android output.

function matched(): { session: SessionStore; keys: MemoryKeys; grant: ScanGrant } {
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
  session.matchPostcard()
  return { session, keys: new MemoryKeys(), grant: ScanGrant.issue(jsPressIds.account, jsPressIds.capsule) }
}

describe('content manifest', () => {
  it('refuses a legacy manifest and an unknown field', async () => {
    await expect(parseContentManifest(protoVarint(1, 1))).rejects.toMatchObject({ code: 'UNSUPPORTED' })
    await expect(parseContentManifest(concat([protoVarint(1, 2), protoVarint(9, 1)]))).rejects.toBeInstanceOf(ProtocolError)
    await expect(parseContentManifest(concat([protoVarint(1, 2), protoBytes(5, utf8('track'))]))).rejects.toBeInstanceOf(ProtocolError)
  })

  it('rebuilds a press sheet whose projection hash matches the sealed field', async () => {
    const { plain } = await jsPressPlain()
    const parsed = await parseContentManifest(plain)
    expect(parsed.expression.grammarId).toBe('g5')
    expect(parsed.expression.input.ownerId).toBe('receiver')
    expect(parsed.note).toBe('hello')
    expect(parsed.track).toEqual({ title: 'Hi', artistDisplay: 'Ada' })
    expect(parsed.expression.placements.every((placement) => placement.contentRect === null)).toBe(true)
  })
})

describe('granted postcard', () => {
  for (const failure of ['url', 'owner'] as const) {
    it(`wipes opened and held bytes when synchronous ${failure} interrupts presentation handoff`, async () => {
      const material = await jsPressMaterial(), { session, keys, grant } = matched()
      keys.hold(session.getSnapshot(), material.capabilities())
      const opened = await openGrantedPostcard(session, keys, grant, material.capsule, material.content, material.photos, fitText)
      const copies: Uint8Array[] = [], live = new Set<string>()
      const memory = new PresentationMemory({ create(bytes) {
        copies.push(bytes)
        if (failure === 'url' && copies.length === 2) throw new Error('synthetic URL allocation failure')
        const url = `test:${copies.length}`; live.add(url); return url
      }, revoke(url) { live.delete(url) } })
      memory.attach(session)
      memory.subscribe(() => { if (failure === 'owner' && memory.getSnapshot()) session.signOut() })
      expect(() => showGrantedPostcard(session, memory, opened)).toThrow()
      expect([...opened.photos.values(), ...copies].every((bytes) => bytes.every((n) => n === 0))).toBe(true)
      expect(live.size).toBe(0)
      expect(memory.getSnapshot()).toBeNull()
      expect(session.getSnapshot().phase).toBe(failure === 'owner' ? 'signed-out' : 'postcard-matched')
    })
  }
  it('decrypts a JS-produced capsule only after the grant and then admits the sheet', async () => {
    const material = await jsPressMaterial()
    let opens = 0
    const inner = material.capabilities()
    const { session, keys, grant } = matched()
    keys.hold(session.getSnapshot(), {
      ...inner,
      openEnvelope: (info, ciphertext) => {
        opens += 1
        return inner.openEnvelope(info, ciphertext)
      },
    })
    const opened = await openGrantedPostcard(
      session, keys, grant, material.capsule, material.content, material.photos, fitText,
    )
    expect(session.getSnapshot().phase).toBe('postcard-matched')
    expect(opened.scene.styleId).toBe('press')
    expect(opened.scene.note?.text).toBe('hello')
    expect(opened.photos.size).toBe(3)
    expect(opens).toBe(1)

    const urls = {
      live: new Set<string>(),
      n: 0,
      create() {
        const url = `mem:${this.n++}`
        this.live.add(url)
        return url
      },
      revoke(url: string) {
        this.live.delete(url)
      },
    }
    const memory = new PresentationMemory(urls)
    memory.attach(session)
    showGrantedPostcard(session, memory, opened)
    expect(session.getSnapshot().phase).toBe('presentation-admitted')
    expect(memory.getSnapshot()?.scene.styleId).toBe('press')
    expect(urls.live.size).toBe(3)
    session.signOut()
    expect(memory.getSnapshot()).toBeNull()
    expect(urls.live.size).toBe(0)

    const locked = matched()
    locked.session.lock()
    await expect(openGrantedPostcard(
      locked.session, keys, grant, material.capsule, material.content, [], fitText,
    )).rejects.toMatchObject({ code: 'GRANT' })
  })

  it('does not admit when the statement, the bytes, or the phase change', async () => {
    const material = await jsPressMaterial()
    const { session, keys, grant } = matched()
    let decrypts = 0
    const inner = material.capabilities()
    keys.hold(session.getSnapshot(), {
      ...inner,
      decryptArtifact: () => {
        decrypts += 1
        return Promise.resolve(new Uint8Array([1]))
      },
      openEnvelope: () => Promise.resolve(mismatchedEnvelope(material.statement)),
    })
    const capsule: IncomingCapsule = { ...material.capsule, statementSha256: new Uint8Array(32).fill(1) }
    await expect(openGrantedPostcard(session, keys, grant, capsule, {
      blobId: jsPressIds.content, ciphertext: new Uint8Array([1]),
    }, [], fitText)).rejects.toBeInstanceOf(CollageError)
    expect(decrypts).toBe(0)
    expect(session.getSnapshot().phase).toBe('postcard-matched')
  })
})

function mismatchedEnvelope(statement: Uint8Array): Uint8Array {
  return concat([
    protoVarint(1, 1),
    protoBytes(2, uuidToBytes(jsPressIds.capsule)),
    protoBytes(3, uuidToBytes(jsPressIds.sender)),
    protoBytes(4, uuidToBytes(jsPressIds.account)),
    protoBytes(5, uuidToBytes(jsPressIds.senderBundle)),
    protoBytes(6, uuidToBytes(jsPressIds.bundle)),
    protoBytes(7, new Uint8Array([1])),
    protoBytes(8, statement),
  ])
}
