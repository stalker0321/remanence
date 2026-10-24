import { concat, utf8 } from '../protocol/bytes'
import { protoBytes, protoVarint, zigzag32 } from '../protocol/proto'

/**
 * T308 — pure browser codec for the FRONT fingerprint wire
 * `remanence.recognition.v2.SiftRootSiftFingerprint` (format version 3, profile
 * `postcard-sift-rootsift-v1`), byte-compatible with the accepted Kotlin
 * `:core:model.SiftRootSiftFingerprintCodec` and the shared
 * `protocol/proto/remanence/recognition/v2/recognition_v2.proto`.
 *
 * This is a WIRE CODEC ONLY: it serializes/parses the fingerprint value. It does
 * NOT extract a fingerprint from an image (no SIFT/OpenCV), does not derive
 * RootSIFT, and applies no matching policy; an all-zero descriptor row is
 * storage-valid. The shared `protocol/proto.ts` reader is deliberately NOT reused
 * (64 KiB cap, no fixed64, no repeated messages) and is left unchanged.
 */

export const SIFT_FINGERPRINT_PROFILE_ID = 'postcard-sift-rootsift-v1'
export const SIFT_FINGERPRINT_FORMAT_VERSION = 3
export const DESCRIPTOR_BYTES = 128
export const MAX_KEYPOINTS = 1500
/** Frozen postcard matcher (S3): same wire shape, format 4, up to 6x6x45 keypoints. */
export const S3_FINGERPRINT_PROFILE_ID = 'postcard-frozen-matcher-s3'
export const S3_FINGERPRINT_FORMAT_VERSION = 4
export const S3_MAX_KEYPOINTS = 1620
const PROFILE_VERSIONS: Readonly<Record<string, number>> = {
  [SIFT_FINGERPRINT_PROFILE_ID]: SIFT_FINGERPRINT_FORMAT_VERSION,
  [S3_FINGERPRINT_PROFILE_ID]: S3_FINGERPRINT_FORMAT_VERSION,
}
const PROFILE_MAX_KEYPOINTS: Readonly<Record<string, number>> = {
  [SIFT_FINGERPRINT_PROFILE_ID]: MAX_KEYPOINTS,
  [S3_FINGERPRINT_PROFILE_ID]: S3_MAX_KEYPOINTS,
}
export const MIN_KEYPOINTS = 1
export const MAX_SERIALIZED_BYTES = 1 << 20
const MICRO_UNITS = 1_000_000
const MAX_CANONICAL_DIMENSION_PX = 100_000
const MAX_ANGLE_CENTI_DEGREES = 35_999
const MIN_OCTAVE = -8
const MAX_OCTAVE = 8
const INT64_MIN = -(2n ** 63n)
const INT64_MAX = 2n ** 63n - 1n

export interface SiftFingerprintKeypoint {
  xMicro: number
  yMicro: number
  scaleMicro: number
  angleCentiDegrees: number
  responseQuantized: number
  octave: number
}

export interface SiftFingerprint {
  profileId: string
  canonicalWidthPx: number
  canonicalHeightPx: number
  /** Signed 64-bit Kotlin `Long`; the fixed64 wire carries its raw two's-complement bits. */
  coarseHash64: bigint
  keypoints: SiftFingerprintKeypoint[]
  /** Exactly one 128-byte raw quantized descriptor row per keypoint, in order. */
  quantizedSiftDescriptors: Uint8Array[]
}

export type SiftFingerprintErrorCode =
  | 'size'
  | 'encoding'
  | 'version'
  | 'profile'
  | 'range'
  | 'descriptors'
  | 'noncanonical'

/** Bounded, redacted refusal. Never carries raw bytes/descriptors. */
export class SiftFingerprintError extends Error {
  constructor(readonly code: SiftFingerprintErrorCode) {
    super(code)
    this.name = 'SiftFingerprintError'
  }
}

function refuse(code: SiftFingerprintErrorCode): SiftFingerprintError {
  return new SiftFingerprintError(code)
}

function int(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value)
}

function inRange(value: unknown, min: number, max: number): value is number {
  return int(value) && value >= min && value <= max
}

function validateKeypoint(keypoint: SiftFingerprintKeypoint): void {
  if (!inRange(keypoint.xMicro, 0, MICRO_UNITS)) throw refuse('range')
  if (!inRange(keypoint.yMicro, 0, MICRO_UNITS)) throw refuse('range')
  if (!inRange(keypoint.scaleMicro, 0, MICRO_UNITS)) throw refuse('range')
  if (!inRange(keypoint.angleCentiDegrees, 0, MAX_ANGLE_CENTI_DEGREES)) throw refuse('range')
  if (!inRange(keypoint.responseQuantized, 0, MICRO_UNITS)) throw refuse('range')
  if (!inRange(keypoint.octave, MIN_OCTAVE, MAX_OCTAVE)) throw refuse('range')
}

function validate(fingerprint: SiftFingerprint): void {
  const maxKeypoints = PROFILE_MAX_KEYPOINTS[fingerprint.profileId]
  if (maxKeypoints === undefined) throw refuse('profile')
  if (!inRange(fingerprint.canonicalWidthPx, 1, MAX_CANONICAL_DIMENSION_PX)) throw refuse('range')
  if (!inRange(fingerprint.canonicalHeightPx, 1, MAX_CANONICAL_DIMENSION_PX)) throw refuse('range')
  if (typeof fingerprint.coarseHash64 !== 'bigint' || fingerprint.coarseHash64 < INT64_MIN || fingerprint.coarseHash64 > INT64_MAX) throw refuse('range')
  if (fingerprint.keypoints.length < MIN_KEYPOINTS || fingerprint.keypoints.length > maxKeypoints) throw refuse('range')
  if (fingerprint.quantizedSiftDescriptors.length !== fingerprint.keypoints.length) throw refuse('descriptors')
  for (const keypoint of fingerprint.keypoints) validateKeypoint(keypoint)
  for (const row of fingerprint.quantizedSiftDescriptors) {
    if (!(row instanceof Uint8Array) || row.length !== DESCRIPTOR_BYTES) throw refuse('descriptors')
  }
}

/**
 * T330 — narrow additive export of the accepted private validator so the
 * correspondence stage can enforce the EXACT existing format-3 contract
 * before any native access without duplicating rules or allocating full
 * fingerprint wires. No validation behavior changes.
 */
export function validateSiftFingerprintShape(fingerprint: SiftFingerprint): void {
  validate(fingerprint)
}

/** `fixed64` (wire type 1): raw little-endian 64 bits of the signed value. */
function fixed64Field(field: number, value: bigint): Uint8Array {
  const out = new Uint8Array(9)
  out[0] = (field << 3) | 1
  let rest = BigInt.asUintN(64, value)
  for (let index = 0; index < 8; index++) {
    out[1 + index] = Number(rest & 0xffn)
    rest >>= 8n
  }
  return out
}

function keypointBytes(keypoint: SiftFingerprintKeypoint): Uint8Array {
  const parts: Uint8Array[] = []
  if (keypoint.xMicro !== 0) parts.push(protoVarint(1, keypoint.xMicro))
  if (keypoint.yMicro !== 0) parts.push(protoVarint(2, keypoint.yMicro))
  if (keypoint.scaleMicro !== 0) parts.push(protoVarint(3, keypoint.scaleMicro))
  if (keypoint.angleCentiDegrees !== 0) parts.push(protoVarint(4, keypoint.angleCentiDegrees))
  if (keypoint.responseQuantized !== 0) parts.push(protoVarint(5, keypoint.responseQuantized))
  if (keypoint.octave !== 0) parts.push(protoVarint(6, zigzag32(keypoint.octave)))
  return concat(parts)
}

export function serializeSiftFingerprint(fingerprint: SiftFingerprint): Uint8Array {
  validate(fingerprint)
  const chunks: Uint8Array[] = [
    protoVarint(1, PROFILE_VERSIONS[fingerprint.profileId]!),
    protoBytes(2, utf8(fingerprint.profileId)),
  ]
  if (fingerprint.canonicalWidthPx !== 0) chunks.push(protoVarint(3, fingerprint.canonicalWidthPx))
  if (fingerprint.canonicalHeightPx !== 0) chunks.push(protoVarint(4, fingerprint.canonicalHeightPx))
  if (fingerprint.coarseHash64 !== 0n) chunks.push(fixed64Field(5, fingerprint.coarseHash64))
  for (const keypoint of fingerprint.keypoints) chunks.push(protoBytes(6, keypointBytes(keypoint)))
  if (fingerprint.quantizedSiftDescriptors.length > 0) {
    const flattened = concat(fingerprint.quantizedSiftDescriptors)
    try {
      chunks.push(protoBytes(7, flattened))
    } finally {
      flattened.fill(0)
    }
  }
  const out = concat(chunks)
  if (out.length > MAX_SERIALIZED_BYTES) throw refuse('size')
  return out
}

/** Bounded reader for the known format-3 fields only (no generic protobuf). */
class BoundedReader {
  #pos = 0
  constructor(private readonly bytes: Uint8Array) {}

  get done(): boolean {
    return this.#pos >= this.bytes.length
  }

  varint(): number {
    let value = 0
    let factor = 1
    for (let index = 0; index < 5; index++) {
      if (this.#pos >= this.bytes.length) throw refuse('encoding')
      const byte = this.bytes[this.#pos++]!
      value += (byte & 127) * factor
      if ((byte & 128) === 0) {
        if (value > 0xffffffff || !Number.isSafeInteger(value)) throw refuse('encoding')
        return value
      }
      factor *= 128
    }
    throw refuse('encoding')
  }

  fixed64(): bigint {
    if (this.bytes.length - this.#pos < 8) throw refuse('encoding')
    let value = 0n
    for (let index = 0; index < 8; index++) value |= BigInt(this.bytes[this.#pos++]!) << BigInt(8 * index)
    return value
  }

  lengthDelimited(): Uint8Array {
    const length = this.varint()
    if (length > this.bytes.length - this.#pos) throw refuse('encoding')
    const value = this.bytes.slice(this.#pos, this.#pos + length)
    this.#pos += length
    return value
  }
}

function unzigzag(value: number): number {
  return (value >>> 1) ^ -(value & 1)
}

function parseKeypoint(bytes: Uint8Array): SiftFingerprintKeypoint {
  const reader = new BoundedReader(bytes)
  const seen = new Set<number>()
  const keypoint: SiftFingerprintKeypoint = { xMicro: 0, yMicro: 0, scaleMicro: 0, angleCentiDegrees: 0, responseQuantized: 0, octave: 0 }
  while (!reader.done) {
    const tag = reader.varint()
    const field = Math.floor(tag / 8)
    const wire = tag % 8
    if (field < 1 || field > 6 || wire !== 0 || seen.has(field)) throw refuse('encoding')
    seen.add(field)
    const value = reader.varint()
    if (field === 1) keypoint.xMicro = value
    else if (field === 2) keypoint.yMicro = value
    else if (field === 3) keypoint.scaleMicro = value
    else if (field === 4) keypoint.angleCentiDegrees = value
    else if (field === 5) keypoint.responseQuantized = value
    else keypoint.octave = unzigzag(value)
  }
  return keypoint
}

function sameBytes(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false
  for (let index = 0; index < left.length; index++) if (left[index] !== right[index]) return false
  return true
}

export function parseSiftFingerprint(bytes: Uint8Array): SiftFingerprint {
  if (!(bytes instanceof Uint8Array) || bytes.length === 0 || bytes.length > MAX_SERIALIZED_BYTES) throw refuse('size')
  const reader = new BoundedReader(bytes)
  let formatVersion = 0
  let profileId = ''
  let canonicalWidthPx = 0
  let canonicalHeightPx = 0
  let coarseHash64 = 0n
  const keypoints: SiftFingerprintKeypoint[] = []
  let rawDescriptors: Uint8Array | null = null
  const seen = new Set<number>()
  try {
    while (!reader.done) {
      const tag = reader.varint()
      const field = Math.floor(tag / 8)
      const wire = tag % 8
      if (field === 6) {
        if (wire !== 2) throw refuse('encoding')
        // Bound the repeated keypoint count BEFORE reading/copying/parsing the
        // message, so an oversized input can never allocate past MAX_KEYPOINTS.
        // The profile precedes keypoints in canonical bytes; cap by it.
        if (keypoints.length >= (PROFILE_MAX_KEYPOINTS[profileId] ?? MAX_KEYPOINTS)) throw refuse('range')
        keypoints.push(parseKeypoint(reader.lengthDelimited()))
        continue
      }
      if (field !== 1 && field !== 2 && field !== 3 && field !== 4 && field !== 5 && field !== 7) throw refuse('encoding')
      if (seen.has(field)) throw refuse('encoding')
      seen.add(field)
      if (field === 1) {
        if (wire !== 0) throw refuse('encoding')
        formatVersion = reader.varint()
      } else if (field === 2) {
        if (wire !== 2) throw refuse('encoding')
        try {
          profileId = new TextDecoder('utf-8', { fatal: true }).decode(reader.lengthDelimited())
        } catch {
          throw refuse('encoding')
        }
      } else if (field === 3) {
        if (wire !== 0) throw refuse('encoding')
        canonicalWidthPx = reader.varint()
      } else if (field === 4) {
        if (wire !== 0) throw refuse('encoding')
        canonicalHeightPx = reader.varint()
      } else if (field === 5) {
        if (wire !== 1) throw refuse('encoding')
        coarseHash64 = BigInt.asIntN(64, reader.fixed64())
      } else {
        if (wire !== 2) throw refuse('encoding')
        rawDescriptors = reader.lengthDelimited()
      }
    }
  } catch (error) {
    rawDescriptors?.fill(0)
    throw error
  }
  if (PROFILE_VERSIONS[profileId] === undefined) {
    rawDescriptors?.fill(0)
    throw refuse('profile')
  }
  if (formatVersion !== PROFILE_VERSIONS[profileId]) {
    rawDescriptors?.fill(0)
    throw refuse('version')
  }
  const raw = rawDescriptors ?? new Uint8Array(0)
  if (raw.length !== keypoints.length * DESCRIPTOR_BYTES) {
    raw.fill(0)
    throw refuse('descriptors')
  }
  const rows: Uint8Array[] = []
  try {
    for (let index = 0; index < keypoints.length; index++) rows.push(raw.slice(index * DESCRIPTOR_BYTES, (index + 1) * DESCRIPTOR_BYTES))
  } finally {
    raw.fill(0)
  }
  const fingerprint: SiftFingerprint = { profileId, canonicalWidthPx, canonicalHeightPx, coarseHash64, keypoints, quantizedSiftDescriptors: rows }
  try {
    validate(fingerprint)
    // Kotlin codec canonicality: input must equal the canonical reserialization.
    const canonical = serializeSiftFingerprint(fingerprint)
    try {
      if (!sameBytes(canonical, bytes)) throw refuse('noncanonical')
    } finally {
      canonical.fill(0)
    }
    return fingerprint
  } catch (error) {
    for (const row of rows) row.fill(0)
    throw error
  }
}
