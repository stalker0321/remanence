import { describe, expect, it } from 'vitest'
import { MemoryKeys } from '../src/auth/memoryKeys'
import { PresentationMemory, showGrantedPostcard } from '../src/collage/memory'
import { CollageError } from '../src/collage/errors'
import { jpegDimensionFixture } from '../src/collage/jpeg'
import { openGrantedPostcard } from '../src/collage/open'
import { parseContentManifest } from '../src/collage/wire'
import { decryptArtifact } from '../src/crypto/artifact'
import type { KeyCapabilities } from '../src/crypto/keyOwner'
import { arrayBuffer, concat, utf8, writeUint32 } from '../src/protocol/bytes'
import { artifactAad } from '../src/protocol/context'
import { ProtocolError } from '../src/protocol/errors'
import { protoBytes, protoVarint, zigzag32 } from '../src/protocol/proto'
import { uuidToBytes } from '../src/protocol/uuid'
import { ScanGrant } from '../src/scan/grant'
import { SessionStore } from '../src/session/session'
import type { IncomingCapsule } from '../src/sync/incoming'
import { projectionHash } from '../src/collage/codec'
import { fitText, pressSheet } from './sheets'

// JS producer of the ADR-005 frame. These bytes are not Android output.

const ACCOUNT = '20212223-2425-2627-2829-2a2b2c2d2e2f'
const BUNDLE = '40414243-4445-4647-4849-4a4b4c4d4e4f'
const SENDER = '10111213-1415-1617-1819-1a1b1c1d1e1f'
const SENDER_BUNDLE = '30313233-3435-3637-3839-3a3b3c3d3e3f'
const CAPSULE = '00010203-0405-0607-0809-0a0b0c0d0e0f'
const CONTENT = '00000000-0000-0000-0000-0000000000c0'
const PHOTOS = [
  '00000000-0000-0000-0000-000000000001',
  '00000000-0000-0000-0000-000000000002',
  '00000000-0000-0000-0000-000000000003',
]
const keyId = 77

function sint(field: number, value: number): Uint8Array {
  return protoVarint(field, zigzag32(value))
}

function str(field: number, value: string): Uint8Array {
  return protoBytes(field, utf8(value))
}

function aesKeyset(raw: Uint8Array): Uint8Array {
  const material = protoBytes(2, raw)
  const data = concat([
    protoBytes(1, utf8('type.googleapis.com/google.crypto.tink.AesGcmKey')),
    protoBytes(2, material),
    protoVarint(3, 1),
  ])
  const key = concat([protoBytes(1, data), protoVarint(2, 1), protoVarint(3, keyId), protoVarint(4, 1)])
  return concat([protoVarint(1, keyId), protoBytes(2, key)])
}

async function framed(raw: Uint8Array, plain: Uint8Array, aad: Uint8Array): Promise<Uint8Array> {
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const key = await crypto.subtle.importKey('raw', arrayBuffer(raw), 'AES-GCM', false, ['encrypt'])
  const body = new Uint8Array(await crypto.subtle.encrypt({
    name: 'AES-GCM',
    iv: arrayBuffer(iv),
    additionalData: arrayBuffer(aad),
  }, key, arrayBuffer(plain)))
  return concat([new Uint8Array([1]), writeUint32(keyId), iv, body])
}

function textType(type: { family: string; size: number; weight: number; italic: boolean; line: number }): Uint8Array {
  return concat([str(1, type.family), protoVarint(2, type.size), protoVarint(3, type.weight), protoVarint(4, type.italic ? 1 : 0), protoVarint(5, type.line)])
}

async function manifestFor(rawNote = 'hello'): Promise<{ plain: Uint8Array; photos: Uint8Array[] }> {
  const expression = await pressSheet()
  const blobIds = new Map(expression.input.photos.map((photo, index) => [photo.contentId, uuidToBytes(PHOTOS[index] ?? '')]))
  const hash = await projectionHash(expression, 'sheet-1', blobIds)
  const style = expression.style
  const note = expression.noteType
  const music = expression.musicRegion
  const region = expression.noteRegion
  if (!style || !note || !music || !region) throw new Error('sheet')
  const sources = expression.input.photos.map((photo, index) => protoBytes(16, concat([
    protoVarint(1, photo.ordinal),
    protoBytes(2, uuidToBytes(PHOTOS[index] ?? '')),
    str(3, photo.contentId),
    str(4, photo.contentHash),
    protoVarint(5, photo.widthPx),
    protoVarint(6, photo.heightPx),
  ])))
  const placements = expression.placements.map((placement) => protoBytes(17, concat([
    str(1, placement.contentId),
    sint(2, placement.x),
    sint(3, placement.y),
    sint(4, placement.width),
    sint(5, placement.height),
    protoBytes(11, concat([
      protoVarint(1, placement.crop?.x ?? 0),
      protoVarint(2, placement.crop?.y ?? 0),
      protoVarint(3, placement.crop?.width ?? 0),
      protoVarint(4, placement.crop?.height ?? 0),
    ])),
  ])))
  const encoded = concat([
    protoVarint(1, 2),
    str(2, 'sheet-1'),
    str(3, hash),
    protoVarint(4, expression.canvasVersion),
    str(5, expression.grammarId),
    protoVarint(6, expression.grammarVersion),
    str(7, expression.branchId),
    str(8, expression.noteTreatment),
    protoVarint(9, expression.fontVersion),
    protoVarint(10, expression.paletteVersion),
    protoVarint(11, 1),
    sint(12, region.x),
    sint(13, region.y),
    sint(14, region.width),
    sint(15, region.height),
    ...sources,
    ...placements,
    protoBytes(18, concat([str(1, style.styleId), str(2, style.styleVersion), protoBytes(3, style.digest)])),
    protoBytes(19, textType(note)),
    protoBytes(20, concat([
      sint(1, music.x), sint(2, music.y), sint(3, music.width), sint(4, music.height),
      str(5, music.mode),
      protoBytes(6, textType(music.type)),
    ])),
  ])
  const photos = expression.input.photos.map((photo, index) => protoBytes(3, concat([
    protoBytes(1, uuidToBytes(PHOTOS[index] ?? '')),
    protoVarint(2, index),
    str(3, 'image/jpeg'),
    protoVarint(4, photo.widthPx),
    protoVarint(5, photo.heightPx),
  ])))
  const plain = concat([
    protoVarint(1, 2),
    protoBytes(2, uuidToBytes(CAPSULE)),
    ...photos,
    str(4, rawNote),
    protoBytes(6, encoded),
    protoBytes(7, concat([
      protoVarint(1, 1),
      str(2, '11111111-1111-4111-8111-111111111111'),
      str(3, 'Hi'),
      str(4, 'Ada'),
    ])),
  ])
  return {
    plain,
    photos: expression.input.photos.map((photo) => jpegDimensionFixture(photo.widthPx, photo.heightPx)),
  }
}

function envelope(keyset: Uint8Array, statement: Uint8Array): Uint8Array {
  return concat([
    protoVarint(1, 1),
    protoBytes(2, uuidToBytes(CAPSULE)),
    protoBytes(3, uuidToBytes(SENDER)),
    protoBytes(4, uuidToBytes(ACCOUNT)),
    protoBytes(5, uuidToBytes(SENDER_BUNDLE)),
    protoBytes(6, uuidToBytes(BUNDLE)),
    protoBytes(7, keyset),
    protoBytes(8, statement),
  ])
}

function matched(): { session: SessionStore; keys: MemoryKeys; grant: ScanGrant } {
  const session = new SessionStore()
  session.signIn({
    accountId: ACCOUNT,
    bundleId: BUNDLE,
    handle: 'ada',
    accessToken: 'access',
    refreshToken: 'refresh',
    accessExpiresAt: '2026-10-17T00:00:00Z',
    refreshExpiresAt: '2026-11-17T00:00:00Z',
  })
  session.unlock()
  session.matchPostcard()
  return { session, keys: new MemoryKeys(), grant: ScanGrant.issue(ACCOUNT, CAPSULE) }
}

describe('content manifest', () => {
  it('refuses a legacy manifest and an unknown field', async () => {
    await expect(parseContentManifest(protoVarint(1, 1))).rejects.toMatchObject({ code: 'UNSUPPORTED' })
    await expect(parseContentManifest(concat([protoVarint(1, 2), protoVarint(9, 1)]))).rejects.toBeInstanceOf(ProtocolError)
    await expect(parseContentManifest(concat([protoVarint(1, 2), protoBytes(5, utf8('track'))]))).rejects.toBeInstanceOf(ProtocolError)
  })

  it('rebuilds a press sheet whose projection hash matches the sealed field', async () => {
    const { plain } = await manifestFor()
    const parsed = await parseContentManifest(plain)
    expect(parsed.expression.grammarId).toBe('g5')
    expect(parsed.expression.input.ownerId).toBe('receiver')
    expect(parsed.note).toBe('hello')
    expect(parsed.track).toEqual({ title: 'Hi', artistDisplay: 'Ada' })
    expect(parsed.expression.placements.every((placement) => placement.contentRect === null)).toBe(true)
  })
})

describe('granted postcard', () => {
  it('decrypts a JS-produced capsule only after the grant and then admits the sheet', async () => {
    const raw = crypto.getRandomValues(new Uint8Array(32))
    const keyset = aesKeyset(raw)
    const statement = new Uint8Array(32).fill(9)
    const body = await manifestFor()
    const contentAad = artifactAad({
      capsuleId: CAPSULE, blobId: CONTENT, kind: 'CONTENT_MANIFEST', ordinal: -1,
      senderUserId: SENDER, recipientUserId: ACCOUNT,
    })
    const contentCiphertext = await framed(raw, body.plain, contentAad)
    const photoCiphertexts = await Promise.all(body.photos.map((plain, ordinal) => framed(raw, plain, artifactAad({
      capsuleId: CAPSULE, blobId: PHOTOS[ordinal] ?? '', kind: 'PHOTO', ordinal,
      senderUserId: SENDER, recipientUserId: ACCOUNT,
    }))))
    const capsule: IncomingCapsule = {
      capsuleId: CAPSULE,
      senderUserId: SENDER,
      recipientUserId: ACCOUNT,
      senderKeyBundleId: SENDER_BUNDLE,
      recipientKeyBundleId: BUNDLE,
      envelopeCiphertext: new Uint8Array([1]),
      statementSha256: statement,
      blobs: [
        { blobId: CONTENT, kind: 'CONTENT_MANIFEST', ordinal: null, ciphertextSize: contentCiphertext.length, ciphertextSha256: '00' },
        ...PHOTOS.map((blobId, ordinal) => ({
          blobId,
          kind: 'PHOTO' as const,
          ordinal,
          ciphertextSize: photoCiphertexts[ordinal]?.length ?? 0,
          ciphertextSha256: '00',
        })),
      ],
    }
    let opens = 0
    const caps: KeyCapabilities = {
      accountId: ACCOUNT,
      bundleId: BUNDLE,
      openEnvelope: () => {
        opens += 1
        return Promise.resolve(envelope(keyset, statement))
      },
      decryptArtifact: (held, aad, ciphertext, kind) => decryptArtifact(held, aad, ciphertext, kind),
      sign: () => Promise.resolve(new Uint8Array()),
      lock() {},
    }
    const { session, keys, grant } = matched()
    keys.hold(session.getSnapshot(), caps)
    const opened = await openGrantedPostcard(session, keys, grant, capsule, {
      blobId: CONTENT,
      ciphertext: contentCiphertext,
    }, PHOTOS.map((blobId, ordinal) => ({ blobId, ordinal, ciphertext: photoCiphertexts[ordinal] ?? new Uint8Array() })), fitText)
    expect(session.getSnapshot().phase).toBe('postcard-matched')
    expect(opened.scene.styleId).toBe('press')
    expect(opened.scene.note?.text).toBe('hello')
    expect(opened.photos.size).toBe(3)
    expect(opens).toBe(1)

    const urls = { live: new Set<string>(), n: 0, create() { const url = `mem:${this.n++}`; this.live.add(url); return url }, revoke(url: string) { this.live.delete(url) } }
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
    await expect(openGrantedPostcard(locked.session, keys, grant, capsule, {
      blobId: CONTENT, ciphertext: contentCiphertext,
    }, [], fitText)).rejects.toMatchObject({ code: 'GRANT' })
  })

  it('does not admit when the statement, the bytes, or the phase change', async () => {
    const raw = crypto.getRandomValues(new Uint8Array(32))
    const statement = new Uint8Array(32).fill(9)
    const { session, keys, grant } = matched()
    let decrypts = 0
    const caps: KeyCapabilities = {
      accountId: ACCOUNT,
      bundleId: BUNDLE,
      openEnvelope: () => Promise.resolve(envelope(aesKeyset(raw), statement)),
      decryptArtifact: () => {
        decrypts += 1
        return Promise.resolve(new Uint8Array([1]))
      },
      sign: () => Promise.resolve(new Uint8Array()),
      lock() {},
    }
    keys.hold(session.getSnapshot(), caps)
    const capsule: IncomingCapsule = {
      capsuleId: CAPSULE,
      senderUserId: SENDER,
      recipientUserId: ACCOUNT,
      senderKeyBundleId: SENDER_BUNDLE,
      recipientKeyBundleId: BUNDLE,
      envelopeCiphertext: new Uint8Array([1]),
      statementSha256: new Uint8Array(32).fill(1),
      blobs: [],
    }
    await expect(openGrantedPostcard(session, keys, grant, capsule, {
      blobId: CONTENT, ciphertext: new Uint8Array([1]),
    }, [], fitText)).rejects.toBeInstanceOf(CollageError)
    expect(decrypts).toBe(0)
    expect(session.getSnapshot().phase).toBe('postcard-matched')
  })
})
