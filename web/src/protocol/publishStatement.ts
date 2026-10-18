import { bytesToHex, concat } from './bytes'
import { ProtocolError } from './errors'
import { protoBytes, protoVarint, zigzag32 } from './proto'
import { limits } from './limits'

/**
 * T279 — pure deterministic `PublishStatement` encoder.
 *
 * Mirrors the actual Android `PublishStatementBuilder.kt:38-79` +
 * `ArtifactLayout.kt` + `CanonicalArtifactOrder.kt`:
 * - layout: unique 16-byte blob ids; exactly one RECOGNITION_MANIFEST and one
 *   CONTENT_MANIFEST; 3..5 PHOTO; non-photo ordinal -1; photo ordinals exactly
 *   {0..n-1}; known kinds only;
 * - every ciphertext sha256 is 32 bytes and `0 < size <= cap(kind)`;
 * - bindings are sorted by `(protobuf kind number, ordinal, unsigned blob id)`
 *   on a COPY (the caller list is never mutated);
 * - deterministic proto3: fields ascend 1..8; defaults omitted (timestamp 0 and
 *   PHOTO ordinal 0 omitted; non-photo -1 retained); int64 timestamp is a
 *   two's-complement varint (10 bytes for negatives) via a LOCAL bigint writer.
 *
 * Explicit caller inputs only: five 16-byte ids, a caller-supplied int64
 * `createdAtEpochSeconds` in the FULL signed 64-bit range (no clock/window/
 * default policy), and explicit artifacts. No async/digest/signature/state/
 * observer/logs. The returned bytes are deterministic public signed metadata.
 * The raw statement SHA-256 is distinct from the `postmark/publish/v1`-prefixed
 * signature input; signing remains in the existing `crypto/publish.ts`.
 */

export type PublishStatementKind = 'RECOGNITION_MANIFEST' | 'CONTENT_MANIFEST' | 'PHOTO'

export interface PublishStatementArtifact {
  blobId: Uint8Array
  kind: PublishStatementKind
  ordinal: number
  ciphertextSize: number
  ciphertextSha256: Uint8Array
}

export interface PublishStatementInput {
  capsuleId: Uint8Array
  senderUserId: Uint8Array
  recipientUserId: Uint8Array
  senderKeyBundleId: Uint8Array
  recipientKeyBundleId: Uint8Array
  createdAtEpochSeconds: bigint
  artifacts: PublishStatementArtifact[]
}

const PROTOCOL_VERSION = 1
const UUID_BYTES = 16
const SHA256_BYTES = 32
const NON_PHOTO_ORDINAL = -1
const PHOTO_COUNT_MIN = 3
const PHOTO_COUNT_MAX = 5
const INT64_MIN = -(2n ** 63n)
const INT64_MAX = (2n ** 63n) - 1n

const KIND_NUMBER: Record<PublishStatementKind, number> = {
  RECOGNITION_MANIFEST: 1,
  CONTENT_MANIFEST: 2,
  PHOTO: 3,
}
const KIND_CAP: Record<PublishStatementKind, number> = {
  RECOGNITION_MANIFEST: limits.recognitionCiphertext,
  CONTENT_MANIFEST: limits.contentCiphertext,
  PHOTO: limits.photoCiphertext,
}

function requireUuid(value: Uint8Array): Uint8Array {
  if (!(value instanceof Uint8Array) || value.length !== UUID_BYTES) throw new ProtocolError('INVALID_ENCODING')
  return value
}

function requireSha256(value: Uint8Array): Uint8Array {
  if (!(value instanceof Uint8Array) || value.length !== SHA256_BYTES) throw new ProtocolError('INVALID_ENCODING')
  return value
}

function kindNumber(kind: PublishStatementKind): number {
  const number = (KIND_NUMBER as Record<string, number | undefined>)[kind]
  if (number === undefined) throw new ProtocolError('UNSUPPORTED')
  return number
}

/** Standard protobuf int64 two's-complement base-128 varint (10 bytes when negative). */
function int64Varint(value: bigint): Uint8Array {
  const two64 = 1n << 64n
  let rest = value < 0n ? value + two64 : value
  if (rest < 0n || rest >= two64) throw new ProtocolError('INVALID_ENCODING')
  const out: number[] = []
  while (rest > 0x7fn) {
    out.push(Number(rest & 0x7fn) | 0x80)
    rest >>= 7n
  }
  out.push(Number(rest))
  return Uint8Array.from(out)
}

function protoInt64(field: number, value: bigint): Uint8Array {
  // Field numbers used here are < 16, so the tag is one byte.
  return concat([Uint8Array.of(field << 3), int64Varint(value)])
}

function compareUnsignedBytes(left: Uint8Array, right: Uint8Array): number {
  const length = Math.min(left.length, right.length)
  for (let index = 0; index < length; index++) {
    const difference = (left[index]! & 0xff) - (right[index]! & 0xff)
    if (difference !== 0) return difference
  }
  return left.length - right.length
}

function bindingBytes(artifact: PublishStatementArtifact): Uint8Array {
  const parts: Uint8Array[] = [
    protoBytes(1, artifact.blobId),
    protoVarint(2, kindNumber(artifact.kind)),
  ]
  const ordinal = zigzag32(artifact.ordinal)
  if (ordinal !== 0) parts.push(protoVarint(3, ordinal))
  parts.push(protoVarint(4, artifact.ciphertextSize))
  parts.push(protoBytes(5, artifact.ciphertextSha256))
  return concat(parts)
}

export function buildPublishStatement(input: PublishStatementInput): Uint8Array {
  const capsuleId = requireUuid(input.capsuleId)
  const senderUserId = requireUuid(input.senderUserId)
  const recipientUserId = requireUuid(input.recipientUserId)
  const senderKeyBundleId = requireUuid(input.senderKeyBundleId)
  const recipientKeyBundleId = requireUuid(input.recipientKeyBundleId)

  if (typeof input.createdAtEpochSeconds !== 'bigint') throw new ProtocolError('INVALID_ENCODING')
  if (input.createdAtEpochSeconds < INT64_MIN || input.createdAtEpochSeconds > INT64_MAX) {
    throw new ProtocolError('INVALID_ENCODING')
  }
  if (!Array.isArray(input.artifacts)) throw new ProtocolError('INVALID_ENCODING')

  const seenBlobIds = new Set<string>()
  let recognition = 0
  let content = 0
  const photoOrdinals: number[] = []
  for (const artifact of input.artifacts) {
    const blobId = requireUuid(artifact.blobId)
    const blobKey = bytesToHex(blobId)
    if (seenBlobIds.has(blobKey)) throw new ProtocolError('INVALID_ENCODING')
    seenBlobIds.add(blobKey)
    const kind = artifact.kind
    if (kind === 'RECOGNITION_MANIFEST') {
      recognition += 1
      if (artifact.ordinal !== NON_PHOTO_ORDINAL) throw new ProtocolError('INVALID_ENCODING')
    } else if (kind === 'CONTENT_MANIFEST') {
      content += 1
      if (artifact.ordinal !== NON_PHOTO_ORDINAL) throw new ProtocolError('INVALID_ENCODING')
    } else if (kind === 'PHOTO') {
      if (!Number.isInteger(artifact.ordinal) || artifact.ordinal < 0 || artifact.ordinal > 4) {
        throw new ProtocolError('INVALID_ENCODING')
      }
      photoOrdinals.push(artifact.ordinal)
    } else {
      throw new ProtocolError('UNSUPPORTED')
    }
  }
  if (recognition !== 1 || content !== 1) throw new ProtocolError('INVALID_ENCODING')
  if (photoOrdinals.length < PHOTO_COUNT_MIN || photoOrdinals.length > PHOTO_COUNT_MAX) {
    throw new ProtocolError('INVALID_ENCODING')
  }
  photoOrdinals.sort((left, right) => left - right)
  if (photoOrdinals.some((ordinal, index) => ordinal !== index)) throw new ProtocolError('INVALID_ENCODING')

  for (const artifact of input.artifacts) {
    requireSha256(artifact.ciphertextSha256)
    const cap = KIND_CAP[artifact.kind]
    if (
      !Number.isInteger(artifact.ciphertextSize) ||
      artifact.ciphertextSize <= 0 ||
      artifact.ciphertextSize > cap
    ) {
      throw new ProtocolError('OVERSIZED')
    }
  }

  // Sort a COPY with the canonical `(kind number, ordinal, unsigned blob id)`
  // order; never mutate the caller's list or artifact objects.
  const sorted = input.artifacts.slice().sort((left, right) => {
    const kind = kindNumber(left.kind) - kindNumber(right.kind)
    if (kind !== 0) return kind
    const ordinal = left.ordinal - right.ordinal
    if (ordinal !== 0) return ordinal
    return compareUnsignedBytes(left.blobId, right.blobId)
  })

  const parts: Uint8Array[] = [
    protoVarint(1, PROTOCOL_VERSION),
    protoBytes(2, capsuleId),
    protoBytes(3, senderUserId),
    protoBytes(4, recipientUserId),
    protoBytes(5, senderKeyBundleId),
    protoBytes(6, recipientKeyBundleId),
  ]
  if (input.createdAtEpochSeconds !== 0n) parts.push(protoInt64(7, input.createdAtEpochSeconds))
  for (const artifact of sorted) parts.push(protoBytes(8, bindingBytes(artifact)))
  return concat(parts)
}
