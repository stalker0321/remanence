import { normalizeHandle } from '../auth/handle'
import { concat, utf8 } from '../protocol/bytes'
import { limits } from '../protocol/limits'
import { protoBytes, protoVarint } from '../protocol/proto'

/**
 * T289 — pure plaintext `RecognitionManifest` (manifest_version 2) encoder.
 *
 * Serializes the SAME fields the accepted Android
 * `RecognitionManifestCodec.buildAndEncrypt` writes before AEAD
 * (schema `remanence_v1.proto:65-77`, `RecognitionManifestCodec.kt:36-80,
 * 202-211`): `manifest_version=2`, `capsule_id`, `chooser_hint`
 * (`sender_handle_snapshot` UTF-8, `created_at_epoch_seconds` int64 varint
 * omitted at the proto3 default 0, optional `place_label`), then
 * `front_fingerprint`; field 5 stays reserved/absent.
 *
 * It is a plaintext METADATA encoder only: NO encryption, AAD, SHA-256,
 * signature, keyset, network, storage, or recognition. It never generates,
 * parses, matches, or validates a fingerprint (the bytes are opaque and
 * caller-provided; genuine FRONT provenance is an upstream precondition). No
 * profile, content, authorization, admission, clock-window, account, or UUID
 * policy is invented.
 *
 * Caller inputs are explicit and validated, never defaulted or rewritten:
 * `capsuleId` (exactly 16 bytes); `senderHandleSnapshot` already canonical
 * under the existing handle contract (`auth/handle.ts`, same fold as the
 * Android `NormalizedHandle` — the encoder validates but never trims, folds,
 * lowercases, or strips `@`); `createdAtEpochSeconds` a nonnegative signed
 * int64 `bigint`; `placeLabel` an explicit `null` (omit) or non-empty string
 * (UTF-8 <= 120 bytes); `frontFingerprint` a non-empty opaque `Uint8Array`.
 *
 * The serialized plaintext + the existing 33-byte AEAD overhead must fit the
 * existing 1_048_576 recognition ciphertext cap (checked from lengths BEFORE
 * any output allocation). Input arrays are never mutated or retained; the
 * returned buffer is caller-owned and the fingerprint is copied exactly once,
 * directly into it. The caller must encrypt then wipe the returned plaintext.
 */

export interface RecognitionManifestInput {
  capsuleId: Uint8Array
  senderHandleSnapshot: string
  createdAtEpochSeconds: bigint
  placeLabel: string | null
  frontFingerprint: Uint8Array
}

export type RecognitionManifestRefusal =
  | 'capsule-id'
  | 'handle'
  | 'created-at'
  | 'place-label'
  | 'fingerprint'
  | 'manifest-size'

export type RecognitionManifestResult =
  | { ok: true; bytes: Uint8Array }
  | { ok: false; reason: RecognitionManifestRefusal; detail?: string }

/** Existing Android `RecognitionManifestCodec` constants (`:202-211`). */
const MANIFEST_VERSION = 2
const CAPSULE_ID_BYTES = 16
const PLACE_LABEL_MAX_BYTES = 120
const MAX_FINGERPRINT_BYTES = 1_048_576
const INT64_MAX = 2n ** 63n - 1n

function refuse(reason: RecognitionManifestRefusal, detail?: string): RecognitionManifestResult {
  return detail === undefined ? { ok: false, reason } : { ok: false, reason, detail }
}

/**
 * The input must ALREADY be canonical: the existing contract's normalized value
 * equals the input. This rejects `@name`, uppercase, and surrounding whitespace
 * without ever rewriting the caller's value.
 */
function isCanonicalHandle(value: unknown): value is string {
  if (typeof value !== 'string') return false
  try {
    return normalizeHandle(value) === value
  } catch {
    return false
  }
}

/** Standard protobuf int64 base-128 varint for a value in [0, 2^63). */
function int64Varint(value: bigint): Uint8Array {
  const out: number[] = []
  let rest = value
  while (rest > 0x7fn) {
    out.push(Number(rest & 0x7fn) | 0x80)
    rest >>= 7n
  }
  out.push(Number(rest))
  return Uint8Array.from(out)
}

/** Bounded base-128 varint for a nonnegative length/tag value in [0, 0xffffffff]. */
function uint32Varint(value: number): Uint8Array {
  const out: number[] = []
  let rest = value
  while (rest > 127) {
    out.push((rest & 127) | 128)
    rest = Math.floor(rest / 128)
  }
  out.push(rest)
  return Uint8Array.from(out)
}

export function encodeRecognitionManifest(input: RecognitionManifestInput): RecognitionManifestResult {
  const { capsuleId, senderHandleSnapshot, createdAtEpochSeconds, placeLabel, frontFingerprint } = input

  if (!(capsuleId instanceof Uint8Array) || capsuleId.length !== CAPSULE_ID_BYTES) {
    return refuse('capsule-id', 'capsuleId must be a 16-byte array')
  }
  if (!isCanonicalHandle(senderHandleSnapshot)) {
    return refuse('handle', 'senderHandleSnapshot must already be a canonical handle')
  }
  if (typeof createdAtEpochSeconds !== 'bigint' || createdAtEpochSeconds < 0n || createdAtEpochSeconds > INT64_MAX) {
    return refuse('created-at', 'createdAtEpochSeconds must be a nonnegative signed int64 bigint')
  }
  let placeBytes: Uint8Array | null = null
  if (placeLabel !== null) {
    if (typeof placeLabel !== 'string' || placeLabel.length === 0) {
      return refuse('place-label', 'placeLabel must be null or a non-empty string')
    }
    placeBytes = utf8(placeLabel)
    if (placeBytes.length > PLACE_LABEL_MAX_BYTES) return refuse('place-label', `${placeBytes.length} utf8 bytes`)
  }
  if (!(frontFingerprint instanceof Uint8Array) || frontFingerprint.length === 0) {
    return refuse('fingerprint', 'frontFingerprint must be a non-empty byte array')
  }
  if (frontFingerprint.length > MAX_FINGERPRINT_BYTES) {
    return refuse('fingerprint', `${frontFingerprint.length} bytes`)
  }

  // ChooserHint: public hint fields only. The fingerprint never enters this
  // helper path (no sensitive protoBytes/concat wrapper for it).
  const hintChunks: Uint8Array[] = [protoBytes(1, utf8(senderHandleSnapshot))]
  if (createdAtEpochSeconds !== 0n) hintChunks.push(Uint8Array.of(0x10), int64Varint(createdAtEpochSeconds))
  if (placeBytes !== null) hintChunks.push(protoBytes(3, placeBytes))
  const hint = concat(hintChunks)

  const prefix = concat([protoVarint(1, MANIFEST_VERSION), protoBytes(2, capsuleId), protoBytes(3, hint)])

  // Fingerprint field: tag 4/wire 2 = 0x22, bounded length varint, then ONE
  // direct copy into the final buffer. Budget from lengths BEFORE allocating.
  const fingerprintLength = uint32Varint(frontFingerprint.length)
  const total = prefix.length + 1 + fingerprintLength.length + frontFingerprint.length
  if (total + limits.artifactOverhead > limits.recognitionCiphertext) {
    return refuse(
      'manifest-size',
      `${total} plaintext bytes + ${limits.artifactOverhead} overhead > ${limits.recognitionCiphertext}`,
    )
  }

  const out = new Uint8Array(total)
  let success = false
  try {
    let offset = 0
    out.set(prefix, offset)
    offset += prefix.length
    out[offset++] = 0x22
    out.set(fingerprintLength, offset)
    offset += fingerprintLength.length
    out.set(frontFingerprint, offset)
    offset += frontFingerprint.length
    if (offset !== total) throw new Error('recognition manifest layout mismatch')
    success = true
    return { ok: true, bytes: out }
  } finally {
    // A throw after the fingerprint was written must not leak the partial
    // buffer; the successful returned buffer is caller-owned and NOT wiped.
    if (!success) out.fill(0)
  }
}
