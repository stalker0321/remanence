import { afterEach, describe, expect, it, vi } from 'vitest'
import * as recognitionManifestModule from '../src/collage/recognitionManifest'
import {
  encodeRecognitionManifest,
  type RecognitionManifestInput,
  type RecognitionManifestRefusal,
} from '../src/collage/recognitionManifest'
import { decryptArtifact } from '../src/crypto/artifact'
import { encryptArtifact } from '../src/crypto/artifactEncrypt'
import { generateCapsuleKeyset } from '../src/crypto/capsuleKeyset'
import { bytesToHex, hexToBytes } from '../src/protocol/bytes'
import { artifactAad } from '../src/protocol/context'
import { limits } from '../src/protocol/limits'
import { uuidToBytes } from '../src/protocol/uuid'

const CAPSULE_UUID = '00010203-0405-0607-0809-0a0b0c0d0e0f'
const BLOB_UUID = '00000000-0000-0000-0000-0000000000e5'
const SENDER_UUID = '10111213-1415-1617-1819-1a1b1c1d1e1f'
const RECIPIENT_UUID = '20212223-2425-2627-2829-2a2b2c2d2e2f'
const CAPSULE_BYTES = uuidToBytes(CAPSULE_UUID)
const FP = Uint8Array.from([0xde, 0xad, 0xbe, 0xef])
const INT64_MAX = 2n ** 63n - 1n
const MAX_FINGERPRINT_BYTES = 1_048_576

/**
 * Hand-constructed from the frozen schema (NOT an Android-generated golden):
 * `RecognitionManifest { version=1(2), capsule_id=2, chooser_hint=3,
 * front_fingerprint=4 }`, `ChooserHint { handle=1, created_at=2(int64), place=3 }`,
 * built independently with standard protobuf field tags + base-128 varints.
 */
const V_PLACE_NULL = '08021210000102030405060708090a0b0c0d0e0f1a0b0a0361646110c0d9ecc4062204deadbeef'
const V_PLACE_LVIV = '08021210000102030405060708090a0b0c0d0e0f1a110a0361646110c0d9ecc4061a044c7669762204deadbeef'
const V_TIME_ZERO = '08021210000102030405060708090a0b0c0d0e0f1a050a036164612204deadbeef'

function baseInput(overrides: Partial<RecognitionManifestInput> = {}): RecognitionManifestInput {
  return {
    capsuleId: CAPSULE_BYTES,
    senderHandleSnapshot: 'ada',
    createdAtEpochSeconds: 1_755_000_000n,
    placeLabel: null,
    frontFingerprint: FP,
    ...overrides,
  }
}

function refusalReason(input: RecognitionManifestInput): RecognitionManifestRefusal | 'ok' {
  const result = encodeRecognitionManifest(input)
  return result.ok ? 'ok' : result.reason
}

function sameBytes(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index])
}

function findSubsequence(haystack: Uint8Array, needle: Uint8Array): number {
  for (let start = 0; start <= haystack.length - needle.length; start++) {
    let match = true
    for (let offset = 0; offset < needle.length; offset++) {
      if (haystack[start + offset] !== needle[offset]) {
        match = false
        break
      }
    }
    if (match) return start
  }
  return -1
}

/**
 * TEST-ONLY bounded bigint decoder (the production `proto.readMessage` is a
 * uint32 reader and cannot decode the full int64 created_at range). Wire 0 ->
 * bigint (<= 10 bytes, rejects 64-bit overflow), wire 2 -> length-delimited
 * bytes; duplicate/unexpected fields fail. No production parser is extended.
 */
function decodeMessage(bytes: Uint8Array, allowed: readonly number[]): Map<number, bigint | Uint8Array> {
  const fields = new Map<number, bigint | Uint8Array>()
  let pos = 0
  const readVarintBig = (): bigint => {
    let value = 0n
    let shift = 0n
    for (let i = 0; i < 10; i++) {
      if (pos >= bytes.length) throw new Error('truncated varint')
      const piece = bytes[pos++]!
      value |= BigInt(piece & 0x7f) << shift
      if ((piece & 0x80) === 0) {
        if (i === 9 && (piece & 0x7f) > 1) throw new Error('varint overflow')
        return value
      }
      shift += 7n
    }
    throw new Error('unterminated varint')
  }
  while (pos < bytes.length) {
    const tag = Number(readVarintBig())
    const field = Math.floor(tag / 8)
    const wire = tag % 8
    if (!allowed.includes(field) || fields.has(field)) throw new Error(`unexpected/duplicate field ${field}`)
    if (wire === 0) fields.set(field, readVarintBig())
    else if (wire === 2) {
      const length = Number(readVarintBig())
      if (length > bytes.length - pos) throw new Error('truncated length')
      fields.set(field, bytes.slice(pos, pos + length))
      pos += length
    } else throw new Error(`unexpected wire type ${wire}`)
  }
  return fields
}

function hintOf(bytes: Uint8Array): Map<number, bigint | Uint8Array> {
  const manifest = decodeMessage(bytes, [1, 2, 3, 4])
  const hint = manifest.get(3)
  if (!(hint instanceof Uint8Array)) throw new Error('missing hint')
  return decodeMessage(hint, [1, 2, 3])
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('recognition manifest encoder', () => {
  it('exports only the production encodeRecognitionManifest API (no observer/hook)', () => {
    expect(Object.keys(recognitionManifestModule)).toEqual(['encodeRecognitionManifest'])
    expect('recognitionManifestTestPort' in recognitionManifestModule).toBe(false)
  })

  it('emits the exact hand-constructed v2 vector (place absent and present)', () => {
    const absent = encodeRecognitionManifest(baseInput({ placeLabel: null }))
    expect(absent.ok).toBe(true)
    if (!absent.ok) return
    expect(bytesToHex(absent.bytes)).toBe(V_PLACE_NULL)

    const present = encodeRecognitionManifest(baseInput({ placeLabel: 'Lviv' }))
    expect(present.ok).toBe(true)
    if (!present.ok) return
    expect(bytesToHex(present.bytes)).toBe(V_PLACE_LVIV)

    for (const [bytes, place] of [[absent.bytes, null], [present.bytes, 'Lviv']] as const) {
      const manifest = decodeMessage(bytes, [1, 2, 3, 4])
      expect([...manifest.keys()].sort((left, right) => left - right)).toEqual([1, 2, 3, 4])
      expect(manifest.get(1)).toBe(2n)
      expect(manifest.get(2)).toEqual(CAPSULE_BYTES)
      const hint = decodeMessage(manifest.get(3) as Uint8Array, [1, 2, 3])
      expect(new TextDecoder().decode(hint.get(1) as Uint8Array)).toBe('ada')
      expect(hint.get(2)).toBe(1_755_000_000n)
      if (place === null) expect(hint.has(3)).toBe(false)
      else expect(new TextDecoder().decode(hint.get(3) as Uint8Array)).toBe(place)
      expect(manifest.get(4)).toEqual(FP)
    }
  })

  it('omits the hint created_at field at the proto3 default time 0', () => {
    const result = encodeRecognitionManifest(baseInput({ createdAtEpochSeconds: 0n }))
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(bytesToHex(result.bytes)).toBe(V_TIME_ZERO)
    expect(hintOf(result.bytes).has(2)).toBe(false)
  })

  it('encodes normal, >uint32 and INT64_MAX created_at on the exact local wire', () => {
    const cases: Array<[bigint, string]> = [
      [1_755_000_000n, 'c0d9ecc406'],
      [4_294_967_296n, '8080808010'],
      [INT64_MAX, 'ffffffffffffffff7f'],
    ]
    for (const [value, fieldHex] of cases) {
      const result = encodeRecognitionManifest(baseInput({ createdAtEpochSeconds: value }))
      expect(result.ok).toBe(true)
      if (!result.ok) return
      expect(hintOf(result.bytes).get(2)).toBe(value)
      expect(findSubsequence(result.bytes, hexToBytes(`10${fieldHex}`))).toBeGreaterThan(-1)
    }
  })

  it('refuses a non-canonical or malformed sender handle', () => {
    for (const handle of ['@ada', 'Ada', 'ada ', ' ab', 'ab', 'a', 'ad_a'.repeat(20), 'ada!', 'адa']) {
      expect(refusalReason(baseInput({ senderHandleSnapshot: handle }))).toBe('handle')
    }
  })

  it('refuses a capsule id that is not exactly 16 bytes', () => {
    for (const capsule of [new Uint8Array(0), new Uint8Array(15), new Uint8Array(17), 'x' as unknown as Uint8Array]) {
      expect(refusalReason(baseInput({ capsuleId: capsule }))).toBe('capsule-id')
    }
  })

  it('refuses a negative, out-of-range, or non-bigint timestamp', () => {
    for (const value of [-1n, INT64_MAX + 1n, 1_700_000_000 as unknown as bigint]) {
      expect(refusalReason(baseInput({ createdAtEpochSeconds: value }))).toBe('created-at')
    }
  })

  it('accepts the exact 120-byte place cap and refuses empty / 121 / 122 bytes', () => {
    const at120 = 'a'.repeat(118) + 'м' // 118 + 2 = 120 UTF-8 bytes
    const boundary = encodeRecognitionManifest(baseInput({ placeLabel: at120 }))
    expect(boundary.ok).toBe(true)
    if (boundary.ok) expect(new TextDecoder().decode(hintOf(boundary.bytes).get(3) as Uint8Array)).toBe(at120)
    expect(refusalReason(baseInput({ placeLabel: '' }))).toBe('place-label')
    expect(refusalReason(baseInput({ placeLabel: 'a'.repeat(119) + 'м' }))).toBe('place-label') // 121
    expect(refusalReason(baseInput({ placeLabel: 'м'.repeat(61) }))).toBe('place-label') // 122 (Android case)
  })

  it('refuses an empty or over-1MiB fingerprint', () => {
    expect(refusalReason(baseInput({ frontFingerprint: new Uint8Array(0) }))).toBe('fingerprint')
    expect(refusalReason(baseInput({ frontFingerprint: new Uint8Array(MAX_FINGERPRINT_BYTES + 1) }))).toBe('fingerprint')
  })

  it('accepts the exact serialized budget (+33) and refuses the next byte with valid per-field inputs', () => {
    // handle "ada", time 0 (omitted), null place => 27-byte prefix.
    const accepted = encodeRecognitionManifest(baseInput({ createdAtEpochSeconds: 0n, frontFingerprint: new Uint8Array(1_048_512) }))
    expect(accepted.ok).toBe(true)
    if (accepted.ok) expect(accepted.bytes.length + limits.artifactOverhead).toBe(limits.recognitionCiphertext)

    const next = new Uint8Array(1_048_513)
    expect(next.length).toBeLessThanOrEqual(MAX_FINGERPRINT_BYTES) // still inside the raw fingerprint cap
    expect(refusalReason(baseInput({ createdAtEpochSeconds: 0n, frontFingerprint: next }))).toBe('manifest-size')
  })

  it('never mutates or aliases caller inputs and returns an independent output', () => {
    const capsule = CAPSULE_BYTES.slice()
    const fingerprint = FP.slice()
    const capsuleBefore = capsule.slice()
    const fingerprintBefore = fingerprint.slice()
    const result = encodeRecognitionManifest({
      capsuleId: capsule,
      senderHandleSnapshot: 'ada',
      createdAtEpochSeconds: 5n,
      placeLabel: 'Lviv',
      frontFingerprint: fingerprint,
    })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    const snapshot = result.bytes.slice()
    expect(sameBytes(capsule, capsuleBefore)).toBe(true)
    expect(sameBytes(fingerprint, fingerprintBefore)).toBe(true)
    fingerprint[0] ^= 0xff
    capsule[0] ^= 0xff
    expect(sameBytes(result.bytes, snapshot)).toBe(true)
  })

  it('copies the fingerprint exactly once, directly into the output', () => {
    const fingerprint = FP.slice()
    const realSet = Uint8Array.prototype.set
    let copies = 0
    let destination: Uint8Array | null = null
    vi.spyOn(Uint8Array.prototype, 'set').mockImplementation(
      function (this: Uint8Array, source: ArrayLike<number>, offset?: number) {
        if ((source as unknown) === fingerprint) {
          copies += 1
          destination = this
        }
        return realSet.call(this, source as never, offset as never)
      } as unknown as typeof Uint8Array.prototype.set,
    )
    const result = encodeRecognitionManifest(baseInput({ frontFingerprint: fingerprint }))
    expect(result.ok).toBe(true)
    expect(copies).toBe(1)
    expect(destination).not.toBeNull()
  })

  it('wipes a partial output when serialization throws after writing the fingerprint', () => {
    const fingerprint = FP.slice()
    const realSet = Uint8Array.prototype.set
    let destination: Uint8Array | null = null
    vi.spyOn(Uint8Array.prototype, 'set').mockImplementation(
      function (this: Uint8Array, source: ArrayLike<number>, offset?: number) {
        destination = this
        const done = realSet.call(this, source as never, offset as never)
        if ((source as unknown) === fingerprint) throw new Error('write-fingerprint-then-throw')
        return done
      } as unknown as typeof Uint8Array.prototype.set,
    )
    expect(() => encodeRecognitionManifest(baseInput({ frontFingerprint: fingerprint }))).toThrow('write-fingerprint-then-throw')
    expect(destination).not.toBeNull()
    expect((destination as unknown as Uint8Array).every((byte) => byte === 0)).toBe(true)
  })

  it('round-trips a real generated keyset through the recognition AAD and existing decrypt', async () => {
    const keyset = generateCapsuleKeyset()
    const result = encodeRecognitionManifest(baseInput({ placeLabel: 'Lviv', frontFingerprint: new Uint8Array(64).fill(3) }))
    expect(result.ok).toBe(true)
    if (!result.ok) return
    const aad = artifactAad({
      capsuleId: CAPSULE_UUID,
      blobId: BLOB_UUID,
      kind: 'RECOGNITION_MANIFEST',
      ordinal: -1,
      senderUserId: SENDER_UUID,
      recipientUserId: RECIPIENT_UUID,
    })
    const ciphertext = await encryptArtifact(keyset, aad, result.bytes, 'RECOGNITION_MANIFEST')
    expect(ciphertext.length).toBe(result.bytes.length + limits.artifactOverhead)
    const plain = await decryptArtifact(keyset, aad, ciphertext, 'RECOGNITION_MANIFEST')
    expect(sameBytes(plain, result.bytes)).toBe(true)
    keyset.fill(0)
    plain.fill(0)
  })
})
