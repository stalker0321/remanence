import { ProtocolError } from '../protocol/errors'

/**
 * T275 — pure `RecipientEnvelopePlaintext` builder.
 *
 * Serializes EXACTLY the existing protocol schema
 * (`remanence_v1.proto:54-63`), field order 1..8:
 *   `1 protocol_version=1`, `2 capsule_id(16)`, `3 sender_user_id(16)`,
 *   `4 recipient_user_id(16)`, `5 sender_key_bundle_id(16)`,
 *   `6 recipient_key_bundle_id(16)`, `7 capsule_aead_keyset`,
 *   `8 publish_statement_sha256(32)`.
 *
 * This mirrors the Android producer `CapsulePublisher.kt:311-326` /
 * `:399-419`. Explicit caller inputs only (16-byte IDs, 32-byte hash, the
 * serialized capsule AEAD keyset); no recipient/ID policy, account/epoch state,
 * statement builder, grant or defaults. There is NO async digest: the caller
 * supplies `publishStatementSha256` = SHA-256 of the RAW deterministic
 * `PublishStatement` protobuf bytes (`CapsulePublisher.kt:319,407`), NOT the
 * `postmark/publish/v1`-prefixed signature input and NOT the 69-byte signature.
 * The builder cannot verify unknown statement contents from the hash alone.
 * Keyset validity is an upstream generated-Tink precondition; the existing
 * receiver/decrypt parser validates it downstream.
 *
 * Secret ownership: the output (and the embedded keyset) is SECRET and
 * caller-owned. The builder assembles ONE final buffer, copies the provided
 * keyset once directly into it, and never wipes the caller's keyset. A partial
 * output is wiped on a serialization failure; a successful output is not wiped.
 * There are no secret `protoBytes`/`concat` temporaries and no production
 * observer/hook/test argument. Synchronous, no long-lived state, no logs.
 */

const PROTOCOL_VERSION = 1
const UUID_BYTES = 16
const HASH_BYTES = 32

const TAG_PROTOCOL_VERSION = 0x08
const TAG_CAPSULE_ID = 0x12
const TAG_SENDER_USER_ID = 0x1a
const TAG_RECIPIENT_USER_ID = 0x22
const TAG_SENDER_KEY_BUNDLE_ID = 0x2a
const TAG_RECIPIENT_KEY_BUNDLE_ID = 0x32
const TAG_CAPSULE_AEAD_KEYSET = 0x3a
const TAG_PUBLISH_STATEMENT_SHA256 = 0x42

export interface RecipientEnvelopePlaintextInput {
  capsuleId: Uint8Array
  senderUserId: Uint8Array
  recipientUserId: Uint8Array
  senderKeyBundleId: Uint8Array
  recipientKeyBundleId: Uint8Array
  capsuleAeadKeyset: Uint8Array
  publishStatementSha256: Uint8Array
}

function varintLength(value: number): number {
  let rest = value
  let length = 1
  while (rest > 127) {
    rest = Math.floor(rest / 128)
    length += 1
  }
  return length
}

function writeVarint(out: Uint8Array, offset: number, value: number): number {
  let rest = value
  while (rest > 127) {
    out[offset++] = (rest & 127) | 128
    rest = Math.floor(rest / 128)
  }
  out[offset++] = rest
  return offset
}

function requireBytes(value: Uint8Array, length: number, code: 'INVALID_ENCODING' | 'EMPTY'): Uint8Array {
  if (!(value instanceof Uint8Array) || value.length !== length) throw new ProtocolError(code)
  return value
}

export function buildRecipientEnvelopePlaintext(input: RecipientEnvelopePlaintextInput): Uint8Array {
  const capsuleId = requireBytes(input.capsuleId, UUID_BYTES, 'INVALID_ENCODING')
  const senderUserId = requireBytes(input.senderUserId, UUID_BYTES, 'INVALID_ENCODING')
  const recipientUserId = requireBytes(input.recipientUserId, UUID_BYTES, 'INVALID_ENCODING')
  const senderKeyBundleId = requireBytes(input.senderKeyBundleId, UUID_BYTES, 'INVALID_ENCODING')
  const recipientKeyBundleId = requireBytes(input.recipientKeyBundleId, UUID_BYTES, 'INVALID_ENCODING')
  const publishStatementSha256 = requireBytes(input.publishStatementSha256, HASH_BYTES, 'INVALID_ENCODING')
  const keyset = input.capsuleAeadKeyset
  if (!(keyset instanceof Uint8Array) || keyset.length === 0) throw new ProtocolError('EMPTY')

  const keysetLength = varintLength(keyset.length)
  // field1(2) + five 16-byte fields(5*18) + keyset field + hash field(34)
  const total = 2 + 5 * (1 + 1 + UUID_BYTES) + (1 + keysetLength + keyset.length) + (1 + 1 + HASH_BYTES)
  const out = new Uint8Array(total)
  let success = false
  try {
    let offset = 0
    // 1 protocol_version = 1
    out[offset++] = TAG_PROTOCOL_VERSION
    offset = writeVarint(out, offset, PROTOCOL_VERSION)
    // 2..6: 16-byte UUID / key-bundle fields
    const uuidFields: Array<[number, Uint8Array]> = [
      [TAG_CAPSULE_ID, capsuleId],
      [TAG_SENDER_USER_ID, senderUserId],
      [TAG_RECIPIENT_USER_ID, recipientUserId],
      [TAG_SENDER_KEY_BUNDLE_ID, senderKeyBundleId],
      [TAG_RECIPIENT_KEY_BUNDLE_ID, recipientKeyBundleId],
    ]
    for (const [tag, value] of uuidFields) {
      out[offset++] = tag
      out[offset++] = UUID_BYTES
      out.set(value, offset)
      offset += UUID_BYTES
    }
    // 7 capsule_aead_keyset (the SINGLE secret copy into the final buffer)
    out[offset++] = TAG_CAPSULE_AEAD_KEYSET
    offset = writeVarint(out, offset, keyset.length)
    out.set(keyset, offset)
    offset += keyset.length
    // 8 publish_statement_sha256
    out[offset++] = TAG_PUBLISH_STATEMENT_SHA256
    out[offset++] = HASH_BYTES
    out.set(publishStatementSha256, offset)
    offset += HASH_BYTES
    if (offset !== total) throw new ProtocolError('INVALID_ENCODING')
    success = true
    return out
  } finally {
    // Wipe only a PARTIAL output; the successful returned plaintext is
    // caller-owned and must stay usable.
    if (!success) out.fill(0)
  }
}
