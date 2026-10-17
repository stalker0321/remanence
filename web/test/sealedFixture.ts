import { decryptArtifact } from '../src/crypto/artifact'
import type { KeyCapabilities } from '../src/crypto/keyOwner'
import type { SealedBlob, SealedPhoto } from '../src/collage/open'
import { projectionHash } from '../src/collage/codec'
import { arrayBuffer, bytesToHex, concat, utf8, writeUint32 } from '../src/protocol/bytes'
import { artifactAad } from '../src/protocol/context'
import { protoBytes, protoVarint, zigzag32 } from '../src/protocol/proto'
import { uuidToBytes } from '../src/protocol/uuid'
import type { StillCapture } from '../src/scan/camera'
import type { IncomingCapsule } from '../src/sync/incoming'
import { jpegDimensionFixture } from '../src/collage/jpeg'

const swatches = ['#c4554a', '#d6cce9', '#f4e8c7']

async function raster(width: number, height: number, color: string): Promise<Uint8Array> {
  if (typeof document === 'undefined') return jpegDimensionFixture(width, height)
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) return jpegDimensionFixture(width, height)
  context.fillStyle = color
  context.fillRect(0, 0, width, height)
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.8))
  if (!blob) return jpegDimensionFixture(width, height)
  return new Uint8Array(await blob.arrayBuffer())
}
import { pressSheet } from './sheets'

// JS producer of the ADR-005 frame. These bytes are not Android output.

export const jsPressIds = {
  account: '20212223-2425-2627-2829-2a2b2c2d2e2f',
  bundle: '40414243-4445-4647-4849-4a4b4c4d4e4f',
  sender: '10111213-1415-1617-1819-1a1b1c1d1e1f',
  senderBundle: '30313233-3435-3637-3839-3a3b3c3d3e3f',
  capsule: '00010203-0405-0607-0809-0a0b0c0d0e0f',
  content: '00000000-0000-0000-0000-0000000000c0',
  photos: [
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000003',
  ],
}

const keyId = 77

export const jsPressCapture: StillCapture = { bytes: new Uint8Array([9, 8, 7]), width: 32, height: 24 }

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
  return concat([
    str(1, type.family),
    protoVarint(2, type.size),
    protoVarint(3, type.weight),
    protoVarint(4, type.italic ? 1 : 0),
    protoVarint(5, type.line),
  ])
}

export async function jsPressPlain(rawNote = 'hello'): Promise<{ plain: Uint8Array; photos: Uint8Array[] }> {
  const expression = await pressSheet()
  const blobIds = new Map(expression.input.photos.map((photo, index) => [photo.contentId, uuidToBytes(jsPressIds.photos[index] ?? '')]))
  const hash = await projectionHash(expression, 'sheet-1', blobIds)
  const style = expression.style
  const note = expression.noteType
  const music = expression.musicRegion
  const region = expression.noteRegion
  if (!style || !note || !music || !region) throw new Error('sheet')
  const sources = expression.input.photos.map((photo, index) => protoBytes(16, concat([
    protoVarint(1, photo.ordinal),
    protoBytes(2, uuidToBytes(jsPressIds.photos[index] ?? '')),
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
    protoBytes(1, uuidToBytes(jsPressIds.photos[index] ?? '')),
    protoVarint(2, index),
    str(3, 'image/jpeg'),
    protoVarint(4, photo.widthPx),
    protoVarint(5, photo.heightPx),
  ])))
  const plain = concat([
    protoVarint(1, 2),
    protoBytes(2, uuidToBytes(jsPressIds.capsule)),
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
    photos: await Promise.all(expression.input.photos.map((photo, index) => raster(photo.widthPx, photo.heightPx, swatches[index] ?? '#c4554a'))),
  }
}

function envelope(keyset: Uint8Array, statement: Uint8Array): Uint8Array {
  return concat([
    protoVarint(1, 1),
    protoBytes(2, uuidToBytes(jsPressIds.capsule)),
    protoBytes(3, uuidToBytes(jsPressIds.sender)),
    protoBytes(4, uuidToBytes(jsPressIds.account)),
    protoBytes(5, uuidToBytes(jsPressIds.senderBundle)),
    protoBytes(6, uuidToBytes(jsPressIds.bundle)),
    protoBytes(7, keyset),
    protoBytes(8, statement),
  ])
}

async function sha256(bytes: Uint8Array): Promise<string> {
  return bytesToHex(new Uint8Array(await crypto.subtle.digest('SHA-256', arrayBuffer(bytes))))
}

export interface JsPressMaterial {
  plain: Uint8Array
  statement: Uint8Array
  capsule: IncomingCapsule
  content: SealedBlob
  photos: SealedPhoto[]
  capabilities: () => KeyCapabilities
  ciphertext: (blobId: string) => Uint8Array
}

export async function jsPressMaterial(): Promise<JsPressMaterial> {
  const raw = crypto.getRandomValues(new Uint8Array(32))
  const keyset = aesKeyset(raw)
  const statement = new Uint8Array(32).fill(9)
  const body = await jsPressPlain()
  const contentCiphertext = await framed(raw, body.plain, artifactAad({
    capsuleId: jsPressIds.capsule,
    blobId: jsPressIds.content,
    kind: 'CONTENT_MANIFEST',
    ordinal: -1,
    senderUserId: jsPressIds.sender,
    recipientUserId: jsPressIds.account,
  }))
  const photoCiphertexts = await Promise.all(body.photos.map((plain, ordinal) => framed(raw, plain, artifactAad({
    capsuleId: jsPressIds.capsule,
    blobId: jsPressIds.photos[ordinal] ?? '',
    kind: 'PHOTO',
    ordinal,
    senderUserId: jsPressIds.sender,
    recipientUserId: jsPressIds.account,
  }))))
  const stored = new Map<string, Uint8Array>([[jsPressIds.content, contentCiphertext]])
  photoCiphertexts.forEach((bytes, index) => stored.set(jsPressIds.photos[index] ?? '', bytes))
  const capsule: IncomingCapsule = {
    capsuleId: jsPressIds.capsule,
    senderUserId: jsPressIds.sender,
    recipientUserId: jsPressIds.account,
    senderKeyBundleId: jsPressIds.senderBundle,
    recipientKeyBundleId: jsPressIds.bundle,
    envelopeCiphertext: new Uint8Array([1]),
    statementSha256: statement,
    blobs: [
      {
        blobId: jsPressIds.content,
        kind: 'CONTENT_MANIFEST',
        ordinal: null,
        ciphertextSize: contentCiphertext.length,
        ciphertextSha256: await sha256(contentCiphertext),
      },
      ...jsPressIds.photos.map((blobId, ordinal) => ({
        blobId,
        kind: 'PHOTO' as const,
        ordinal,
        ciphertextSize: photoCiphertexts[ordinal]?.length ?? 0,
        ciphertextSha256: '',
      })),
    ],
  }
  for (const blob of capsule.blobs) {
    if (blob.kind === 'PHOTO') blob.ciphertextSha256 = await sha256(stored.get(blob.blobId) ?? new Uint8Array())
  }
  return {
    plain: body.plain,
    statement,
    capsule,
    content: { blobId: jsPressIds.content, ciphertext: contentCiphertext },
    photos: jsPressIds.photos.map((blobId, ordinal) => ({
      blobId,
      ordinal,
      ciphertext: photoCiphertexts[ordinal] ?? new Uint8Array(),
    })),
    capabilities: () => ({
      accountId: jsPressIds.account,
      bundleId: jsPressIds.bundle,
      openEnvelope: () => Promise.resolve(envelope(keyset, statement)),
      decryptArtifact: (held, aad, ciphertext, kind) => decryptArtifact(held, aad, ciphertext, kind),
      sign: () => Promise.resolve(new Uint8Array()),
      lock() {},
    }),
    ciphertext: (blobId) => stored.get(blobId) ?? new Uint8Array(),
  }
}
