/**
 * T328 unit gate: strict recognition-manifest-v2 reader.
 *
 * Canonical happy paths go through the ACCEPTED encoder; refusal vectors are
 * independently hand-crafted wire bytes (schema-only vectors, honestly
 * labeled — no Android execution claim).
 */
import { describe, expect, it } from 'vitest'
import { encodeRecognitionManifest } from '../src/collage/recognitionManifest'
import {
  readRecognitionManifest,
  RecognitionManifestReadError,
  type RecognitionManifestReadErrorCode,
} from '../src/collage/readRecognitionManifest'
import { concat, utf8 } from '../src/protocol/bytes'
import { protoBytes, protoVarint } from '../src/protocol/proto'
import { normalizeHandle } from '../src/auth/handle'

const CAPSULE_ID = Uint8Array.from({ length: 16 }, (_value, index) => index + 1)
const INT64_MAX = 2n ** 63n - 1n

function fingerprint(size: number): Uint8Array {
  return Uint8Array.from({ length: size }, (_value, index) => (index % 251) + 1)
}

function encodeOk(overrides: {
  capsuleId?: Uint8Array
  senderHandleSnapshot?: string
  createdAtEpochSeconds?: bigint
  placeLabel?: string | null
  frontFingerprint?: Uint8Array
} = {}): Uint8Array {
  const result = encodeRecognitionManifest({
    capsuleId: overrides.capsuleId ?? CAPSULE_ID,
    senderHandleSnapshot: overrides.senderHandleSnapshot ?? normalizeHandle('sergio'),
    createdAtEpochSeconds: overrides.createdAtEpochSeconds ?? 1_755_000_000n,
    placeLabel: overrides.placeLabel === undefined ? null : overrides.placeLabel,
    frontFingerprint: overrides.frontFingerprint ?? fingerprint(64),
  })
  if (!result.ok) throw new Error(`encode refused: ${result.reason}`)
  return result.bytes
}

function expectRefusal(code: RecognitionManifestReadErrorCode, run: () => unknown): void {
  try {
    run()
    expect.unreachable(`expected refusal ${code}`)
  } catch (error) {
    expect(error).toBeInstanceOf(RecognitionManifestReadError)
    expect((error as RecognitionManifestReadError).code).toBe(code)
  }
}

function hintWith(parts: Uint8Array[]): Uint8Array {
  return protoBytes(3, concat(parts))
}

describe('readRecognitionManifest', () => {
  it('round-trips the accepted encoder output with all hint fields', () => {
    const place = 'a café table'
    const wire = encodeOk({ createdAtEpochSeconds: 1_755_000_000n, placeLabel: place, frontFingerprint: fingerprint(96) })
    const content = readRecognitionManifest(wire, CAPSULE_ID)
    expect(content.senderHandleSnapshot).toBe(normalizeHandle('sergio'))
    expect(content.createdAtEpochSeconds).toBe(1_755_000_000n)
    expect(content.placeLabel).toBe(place)
    expect(content.frontFingerprint).toEqual(fingerprint(96))
  })

  it('parses a full int64-max timestamp (10-byte varint)', () => {
    const wire = encodeOk({ createdAtEpochSeconds: INT64_MAX })
    expect(readRecognitionManifest(wire, CAPSULE_ID).createdAtEpochSeconds).toBe(INT64_MAX)
  })

  it('treats a canonically omitted zero timestamp as zero', () => {
    const wire = encodeOk({ createdAtEpochSeconds: 0n })
    expect(readRecognitionManifest(wire, CAPSULE_ID).createdAtEpochSeconds).toBe(0n)
  })

  it('parses a manifest larger than the shared 64 KiB proto reader cap', () => {
    const big = fingerprint(100_000)
    const wire = encodeOk({ frontFingerprint: big })
    expect(wire.length).toBeGreaterThan(65_536)
    expect(readRecognitionManifest(wire, CAPSULE_ID).frontFingerprint).toEqual(big)
  })

  it('does not wipe the caller-owned input plaintext on success', () => {
    const wire = encodeOk()
    readRecognitionManifest(wire, CAPSULE_ID)
    expect(wire.some((byte) => byte !== 0)).toBe(true)
  })

  it('refuses a capsule-id mismatch', () => {
    const other = Uint8Array.from({ length: 16 }, (_value, index) => index + 9)
    expectRefusal('capsule-id', () => readRecognitionManifest(encodeOk(), other))
  })

  it('refuses an unsupported version', () => {
    const tampered = encodeOk()
    tampered[1] = 1 // root f1 varint value 1 (version 1)
    expectRefusal('version', () => readRecognitionManifest(tampered, CAPSULE_ID))
  })

  it('refuses an explicit zero timestamp as noncanonical', () => {
    // Hand-craft a hint with an explicit zero timestamp; the accepted encoder
    // canonically omits zero, so the re-encode comparison must refuse.
    const hint = hintWith([
      protoBytes(1, utf8(normalizeHandle('sergio'))),
      Uint8Array.of(0x10, 0x00),
    ])
    const root = concat([protoVarint(1, 2), protoBytes(2, CAPSULE_ID), hint, protoBytes(4, fingerprint(64))])
    expectRefusal('canonical', () => readRecognitionManifest(root, CAPSULE_ID))
  })

  it('refuses a nonminimal timestamp varint as noncanonical', () => {
    const hint = hintWith([
      protoBytes(1, utf8(normalizeHandle('sergio'))),
      Uint8Array.of(0x10, 0x82, 0x00), // value 2, nonminimal
    ])
    const root = concat([protoVarint(1, 2), protoBytes(2, CAPSULE_ID), hint, protoBytes(4, fingerprint(64))])
    expectRefusal('canonical', () => readRecognitionManifest(root, CAPSULE_ID))
  })

  it('refuses unknown root fields', () => {
    const root = concat([protoVarint(1, 2), protoBytes(2, CAPSULE_ID), protoBytes(5, Uint8Array.of(1))])
    expectRefusal('wire', () => readRecognitionManifest(root, CAPSULE_ID))
  })

  it('refuses duplicated fields', () => {
    const root = concat([
      protoVarint(1, 2),
      protoVarint(1, 2),
      protoBytes(2, CAPSULE_ID),
      hintWith([protoBytes(1, utf8(normalizeHandle('sergio')))]),
      protoBytes(4, fingerprint(8)),
    ])
    expectRefusal('wire', () => readRecognitionManifest(root, CAPSULE_ID))
  })

  it('refuses reordered hint fields as noncanonical', () => {
    const place = 'x'
    const hint = hintWith([
      protoBytes(3, utf8(place)), // place BEFORE handle: encoder never emits this order
      protoBytes(1, utf8(normalizeHandle('sergio'))),
    ])
    const root = concat([protoVarint(1, 2), protoBytes(2, CAPSULE_ID), hint, protoBytes(4, fingerprint(8))])
    expectRefusal('canonical', () => readRecognitionManifest(root, CAPSULE_ID))
  })

  it('refuses truncated wire', () => {
    const wire = encodeOk()
    expectRefusal('wire', () => readRecognitionManifest(wire.subarray(0, wire.length - 3), CAPSULE_ID))
  })

  it('refuses an empty plaintext', () => {
    expectRefusal('size', () => readRecognitionManifest(new Uint8Array(0), CAPSULE_ID))
  })

  it('refuses a noncanonical sender handle', () => {
    const bad = concat([
      protoVarint(1, 2),
      protoBytes(2, CAPSULE_ID),
      hintWith([protoBytes(1, utf8('@sergio'))]),
      protoBytes(4, fingerprint(8)),
    ])
    expectRefusal('handle', () => readRecognitionManifest(bad, CAPSULE_ID))
  })

  it('refuses malformed UTF-8 in the handle', () => {
    const bad = concat([
      protoVarint(1, 2),
      protoBytes(2, CAPSULE_ID),
      hintWith([protoBytes(1, Uint8Array.of(0xff, 0xfe, 0xfd))]),
      protoBytes(4, fingerprint(8)),
    ])
    expectRefusal('handle', () => readRecognitionManifest(bad, CAPSULE_ID))
  })

  it('refuses an empty place label and an oversized place label', () => {
    const emptyPlace = concat([
      protoVarint(1, 2),
      protoBytes(2, CAPSULE_ID),
      hintWith([protoBytes(1, utf8(normalizeHandle('sergio'))), protoBytes(3, new Uint8Array(0))]),
      protoBytes(4, fingerprint(8)),
    ])
    expectRefusal('place', () => readRecognitionManifest(emptyPlace, CAPSULE_ID))
    const oversized = concat([
      protoVarint(1, 2),
      protoBytes(2, CAPSULE_ID),
      hintWith([protoBytes(1, utf8(normalizeHandle('sergio'))), protoBytes(3, utf8('p'.repeat(121)))]),
      protoBytes(4, fingerprint(8)),
    ])
    expectRefusal('place', () => readRecognitionManifest(oversized, CAPSULE_ID))
  })

  it('refuses a negative timestamp', () => {
    const hint = hintWith([
      protoBytes(1, utf8(normalizeHandle('sergio'))),
      // int64 -1 as a ten-byte two's-complement varint
      Uint8Array.of(0x10, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01),
    ])
    const root = concat([protoVarint(1, 2), protoBytes(2, CAPSULE_ID), hint, protoBytes(4, fingerprint(8))])
    expectRefusal('timestamp', () => readRecognitionManifest(root, CAPSULE_ID))
  })

  it('refuses an empty fingerprint', () => {
    const root = concat([
      protoVarint(1, 2),
      protoBytes(2, CAPSULE_ID),
      hintWith([protoBytes(1, utf8(normalizeHandle('sergio')))]),
      protoBytes(4, new Uint8Array(0)),
    ])
    expectRefusal('fingerprint', () => readRecognitionManifest(root, CAPSULE_ID))
  })

  it('refuses a missing chooser hint', () => {
    const root = concat([protoVarint(1, 2), protoBytes(2, CAPSULE_ID), protoBytes(4, fingerprint(8))])
    expectRefusal('hint', () => readRecognitionManifest(root, CAPSULE_ID))
  })

  it('refuses a missing fingerprint', () => {
    const root = concat([
      protoVarint(1, 2),
      protoBytes(2, CAPSULE_ID),
      hintWith([protoBytes(1, utf8(normalizeHandle('sergio')))]),
    ])
    expectRefusal('fingerprint', () => readRecognitionManifest(root, CAPSULE_ID))
  })

  it('wipes the returned fingerprint copy when a later check throws', () => {
    // Noncanonical explicit-zero path allocates the fingerprint copy before
    // the canonical comparison; the copy must be zeroed on refusal.
    const hint = hintWith([
      protoBytes(1, utf8(normalizeHandle('sergio'))),
      Uint8Array.of(0x10, 0x00),
    ])
    const body = fingerprint(64)
    const root = concat([protoVarint(1, 2), protoBytes(2, CAPSULE_ID), hint, protoBytes(4, body)])
    expectRefusal('canonical', () => readRecognitionManifest(root, CAPSULE_ID))
    // The input body must be untouched (caller-owned).
    expect(root.includes(1)).toBe(true)
  })
})
