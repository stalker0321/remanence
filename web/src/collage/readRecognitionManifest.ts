import { normalizeHandle } from '../auth/handle'
import { utf8 } from '../protocol/bytes'
import { encodeRecognitionManifest } from './recognitionManifest'

/**
 * T328 — strict browser reader for one DECRYPTED recognition-manifest-v2
 * plaintext (`remanence.protocol.v1.RecognitionManifest`), mirroring the
 * accepted native checks in `RecognitionManifestCodec.decryptAndParse`
 * (`:115-214`) and `PendingRecognitionDecode.frontFingerprint` (`:23-111`):
 * bounded nonempty plaintext (+33 AEAD overhead within the existing
 * 1_048_576 recognition ciphertext cap), manifest_version exactly 2,
 * inner capsule_id exactly the 16 routing bytes, required chooser hint with
 * a canonical sender handle, created-at in [0, INT64_MAX] as a full
 * signed-int64 bigint, optional place label (nonempty, <=120 UTF-8 bytes),
 * nonempty bounded front fingerprint — then EXACT canonical re-encode
 * equality against the ACCEPTED encoder (`recognitionManifest.ts`), which
 * is what refuses unknown/duplicate/reordered/nonminimal/default-explicit
 * encodings (a zero timestamp is canonically omitted).
 *
 * The shared `protocol/proto.ts` reader is deliberately NOT reused: it is
 * capped at 64 KiB and uint32 scalars and therefore cannot parse a valid
 * up-to-1-MiB manifest or the full int64 hint timestamp. This module adds
 * a scoped bounded reader only; it does not widen the global parser, the
 * encoder, the wire, or any limit, and it does not duplicate the
 * serializer (canonical bytes always come from `encodeRecognitionManifest`).
 *
 * The input plaintext is CALLER-OWNED and never wiped here. On success the
 * returned fingerprint is a caller-owned copy; all canonical comparison
 * bytes are wiped on every path. No hint/fingerprint/plaintext is logged,
 * persisted, or retained. Errors are typed and redacted (code only).
 */

const MANIFEST_VERSION = 2
const CAPSULE_ID_BYTES = 16
const PLACE_LABEL_MAX_BYTES = 120
const MAX_FINGERPRINT_BYTES = 1_048_576
const ARTIFACT_OVERHEAD_BYTES = 33
const RECOGNITION_CIPHERTEXT_CAP = 1_048_576
const INT64_MAX = 2n ** 63n - 1n

export type RecognitionManifestReadErrorCode =
  | 'size'
  | 'wire'
  | 'version'
  | 'capsule-id'
  | 'hint'
  | 'handle'
  | 'timestamp'
  | 'place'
  | 'fingerprint'
  | 'canonical'

/** Bounded, redacted refusal. Never carries plaintext/hint/fingerprint bytes. */
export class RecognitionManifestReadError extends Error {
  constructor(readonly code: RecognitionManifestReadErrorCode) {
    super(code)
    this.name = 'RecognitionManifestReadError'
  }
}

function refuse(code: RecognitionManifestReadErrorCode): never {
  throw new RecognitionManifestReadError(code)
}

export interface RecognitionManifestContent {
  /** Caller-owned copy of the decoded format-3 fingerprint wire. */
  readonly frontFingerprint: Uint8Array
  readonly senderHandleSnapshot: string
  readonly createdAtEpochSeconds: bigint
  readonly placeLabel: string | null
}

function decodeUtf8Strict(bytes: Uint8Array, code: RecognitionManifestReadErrorCode): string {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    return refuse(code)
  }
}

interface FieldSpan {
  readonly field: number
  readonly wire: 0 | 2
  /** Byte offset of the value inside the region (varint start or payload start). */
  readonly valueStart: number
  /** Payload length for wire 2. */
  readonly length: number
}

/** Wire-level uint32 varint (lengths/tags); rejects overflow and truncation. */
function readUint32Varint(bytes: Uint8Array, pos: { at: number }): number {
  let value = 0
  let factor = 1
  for (let i = 0; i < 5; i++) {
    if (pos.at >= bytes.length) refuse('wire')
    const piece = bytes[pos.at++]
    if (piece === undefined) refuse('wire')
    value += (piece & 127) * factor
    if ((piece & 128) === 0) {
      if (value > 0xffffffff) refuse('wire')
      return value
    }
    factor *= 128
  }
  return refuse('wire')
}

/**
 * Wire-level signed int64 varint read from raw bytes (up to 10 bytes, a
 * 10th-byte payload >1 refused, two's-complement conversion). Used only for
 * the chooser-hint timestamp, which the shared uint32 reader cannot carry.
 */
function readInt64Varint(bytes: Uint8Array, pos: { at: number }): bigint {
  let value = 0n
  let factor = 1n
  for (let i = 0; i < 10; i++) {
    if (pos.at >= bytes.length) refuse('wire')
    const piece = bytes[pos.at++]
    if (piece === undefined) refuse('wire')
    value += BigInt(piece & 127) * factor
    if ((piece & 128) === 0) {
      if (i === 9 && piece > 1) refuse('wire')
      return value >= 1n << 63n ? value - (1n << 64n) : value
    }
    factor *= 128n
  }
  return refuse('wire')
}

/**
 * Records field spans in order without copying byte fields. Wire-0 values
 * are skipped with a generic up-to-10-byte varint walk (NO range check):
 * the chooser-hint timestamp is a full signed int64 that the shared uint32
 * reader cannot carry; semantic readers below re-interpret the raw span.
 */
function scanFields(bytes: Uint8Array, allowed: readonly number[]): FieldSpan[] {
  const seen = new Set<number>()
  const spans: FieldSpan[] = []
  const pos = { at: 0 }
  while (pos.at < bytes.length) {
    const tag = readUint32Varint(bytes, pos)
    const field = Math.floor(tag / 8)
    const wire = tag % 8
    if (!allowed.includes(field) || seen.has(field)) refuse('wire')
    seen.add(field)
    if (wire === 0) {
      const valueStart = pos.at
      for (let i = 0; i < 10; i++) {
        if (pos.at >= bytes.length) refuse('wire')
        const piece = bytes[pos.at++]
        if (piece === undefined) refuse('wire')
        if ((piece & 128) === 0) break
        if (i === 9) refuse('wire')
      }
      spans.push({ field, wire: 0, valueStart, length: pos.at - valueStart })
    } else if (wire === 2) {
      const length = readUint32Varint(bytes, pos)
      if (length > bytes.length - pos.at) refuse('wire')
      spans.push({ field, wire: 2, valueStart: pos.at, length })
      pos.at += length
    } else {
      refuse('wire')
    }
  }
  return spans
}

function spanBytes(region: Uint8Array, span: FieldSpan): Uint8Array {
  if (span.wire !== 2) refuse('wire')
  return region.subarray(span.valueStart, span.valueStart + span.length)
}

function findField(spans: readonly FieldSpan[], field: number): FieldSpan | undefined {
  return spans.find((span) => span.field === field)
}

function equalBytes(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false
  let diff = 0
  for (let i = 0; i < left.length; i++) diff |= (left[i] ?? 0) ^ (right[i] ?? 0)
  return diff === 0
}

export function readRecognitionManifest(
  plaintext: Uint8Array,
  expectedCapsuleId: Uint8Array,
): RecognitionManifestContent {
  if (!(plaintext instanceof Uint8Array) || plaintext.length === 0) refuse('size')
  if (!(expectedCapsuleId instanceof Uint8Array) || expectedCapsuleId.length !== CAPSULE_ID_BYTES) {
    refuse('capsule-id')
  }
  if (plaintext.length + ARTIFACT_OVERHEAD_BYTES > RECOGNITION_CIPHERTEXT_CAP) refuse('size')

  // ---- structural scan of the root message (no large copies yet) ----
  const root = scanFields(plaintext, [1, 2, 3, 4])
  const version = findField(root, 1)
  const capsule = findField(root, 2)
  const hint = findField(root, 3)
  const fingerprint = findField(root, 4)
  if (!version || version.wire !== 0) refuse('version')
  {
    const pos = { at: version.valueStart }
    if (readUint32Varint(plaintext, pos) !== MANIFEST_VERSION) refuse('version')
  }
  if (!capsule || capsule.wire !== 2) refuse('capsule-id')
  const capsuleId = spanBytes(plaintext, capsule)
  if (capsuleId.length !== CAPSULE_ID_BYTES) refuse('capsule-id')
  if (!equalBytes(capsuleId, expectedCapsuleId)) refuse('capsule-id')
  if (!hint || hint.wire !== 2) refuse('hint')
  if (!fingerprint || fingerprint.wire !== 2) refuse('fingerprint')

  // ---- chooser hint ----
  const hintRegion = spanBytes(plaintext, hint)
  const hintFields = scanFields(hintRegion, [1, 2, 3])
  const handle = findField(hintFields, 1)
  const timestamp = findField(hintFields, 2)
  const place = findField(hintFields, 3)
  if (!handle || handle.wire !== 2) refuse('handle')
  const senderHandleSnapshot = decodeUtf8Strict(spanBytes(hintRegion, handle), 'handle')
  if (senderHandleSnapshot.length === 0) refuse('handle')
  try {
    if (normalizeHandle(senderHandleSnapshot) !== senderHandleSnapshot) refuse('handle')
  } catch {
    refuse('handle')
  }

  let createdAtEpochSeconds = 0n
  if (timestamp) {
    if (timestamp.wire !== 0) refuse('timestamp')
    createdAtEpochSeconds = readInt64Varint(hintRegion, { at: timestamp.valueStart })
  }
  if (createdAtEpochSeconds < 0n || createdAtEpochSeconds > INT64_MAX) refuse('timestamp')

  let placeLabel: string | null = null
  if (place) {
    if (place.wire !== 2) refuse('place')
    placeLabel = decodeUtf8Strict(spanBytes(hintRegion, place), 'place')
    if (placeLabel.length === 0) refuse('place')
    if (utf8(placeLabel).length > PLACE_LABEL_MAX_BYTES) refuse('place')
  }

  // ---- fingerprint copy LAST, after every fatal structural/semantic check ----
  if (fingerprint.length === 0 || fingerprint.length > MAX_FINGERPRINT_BYTES) refuse('fingerprint')
  const frontFingerprint = plaintext.slice(fingerprint.valueStart, fingerprint.valueStart + fingerprint.length)
  let transferred = false
  try {
    // ---- exact canonical re-encode equality via the ACCEPTED encoder ----
    let canonical: Uint8Array | null = null
    try {
      const encoded = encodeRecognitionManifest({
        capsuleId: expectedCapsuleId.slice(),
        senderHandleSnapshot,
        createdAtEpochSeconds,
        placeLabel,
        frontFingerprint,
      })
      if (!encoded.ok) refuse('canonical')
      canonical = encoded.bytes
      if (!equalBytes(canonical, plaintext)) refuse('canonical')
    } finally {
      canonical?.fill(0)
    }
    transferred = true
    return { frontFingerprint, senderHandleSnapshot, createdAtEpochSeconds, placeLabel }
  } finally {
    if (!transferred) frontFingerprint.fill(0)
  }
}
