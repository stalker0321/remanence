import { concat, utf8 } from '../protocol/bytes'
import { protoBytes, protoVarint } from '../protocol/proto'

/**
 * T267 — fresh per-capsule Tink `AES256_GCM` keyset, compatible with the
 * existing web `parseAes256GcmKeyset` and the Android `CapsuleKeysetParser`
 * wire shape (schema fields per the version-pinned Tink 1.16 keyset proto):
 *
 *   Keyset  { primary_key_id = 1; repeated key = 2 }
 *   Key     { key_data = 1; status = 2 (ENABLED=1); key_id = 3; output_prefix_type = 4 (TINK=1) }
 *   KeyData { type_url = 1 ("type.googleapis.com/google.crypto.tink.AesGcmKey");
 *             value = 2 (serialized AesGcmKey); key_material_type = 3 (SYMMETRIC=1) }
 *   AesGcmKey { version = 1 (0/default); key_value = 2 (32 random bytes) }
 *
 * One fresh 256-bit key via `crypto.getRandomValues` on every call; never
 * derived from account/identity/images/route IDs/time/password. Key id: four
 * CSPRNG bytes -> big-endian uint32 (all 32 bits preserved), redraw ONLY on
 * zero — the version-pinned upstream Tink 1.16 `Util.randKeyId` domain (no
 * sign-bit mask, no global collision store). This is a local implementation
 * choice for the observed nonzero-uint32 domain, not RNG-byte or Android
 * interop parity.
 *
 * The returned serialized keyset is a SECRET caller-owned value (it embeds the
 * raw key); the caller owns its lifecycle and cleanup. This function never
 * caches or returns an internal buffer, and wipes every owned secret-bearing
 * buffer (raw key, key-id draw, serialized AesGcmKey/KeyData/Key intermediates)
 * in `finally` on success and on failure. JS/WebCrypto internal copies cannot be
 * zeroized from here; no total-heap-erasure claim. The returned final keyset
 * buffer is intentionally NOT wiped so it stays usable by the caller.
 */

const AES_GCM_TYPE_URL = 'type.googleapis.com/google.crypto.tink.AesGcmKey'
const KEY_BYTES = 32
const KEY_ID_BYTES = 4
const KEY_STATUS_ENABLED = 1
const KEY_MATERIAL_SYMMETRIC = 1
const OUTPUT_PREFIX_TINK = 1

export function generateCapsuleKeyset(): Uint8Array {
  let rawKey: Uint8Array | null = null
  let idBytes: Uint8Array | null = null
  let aesGcmKey: Uint8Array | null = null
  let keyData: Uint8Array | null = null
  let key: Uint8Array | null = null
  try {
    rawKey = crypto.getRandomValues(new Uint8Array(KEY_BYTES))
    let keyId = 0
    do {
      idBytes = crypto.getRandomValues(new Uint8Array(KEY_ID_BYTES))
      keyId = ((idBytes[0]! << 24) | (idBytes[1]! << 16) | (idBytes[2]! << 8) | idBytes[3]!) >>> 0
    } while (keyId === 0)

    // AesGcmKey { version = 0 (proto default, omitted); key_value = rawKey }.
    aesGcmKey = concat([protoBytes(2, rawKey)])
    // KeyData { type_url; value; key_material_type = SYMMETRIC }.
    keyData = concat([
      protoBytes(1, utf8(AES_GCM_TYPE_URL)),
      protoBytes(2, aesGcmKey),
      protoVarint(3, KEY_MATERIAL_SYMMETRIC),
    ])
    // Key { key_data; status = ENABLED; key_id; output_prefix_type = TINK }.
    key = concat([
      protoBytes(1, keyData),
      protoVarint(2, KEY_STATUS_ENABLED),
      protoVarint(3, keyId),
      protoVarint(4, OUTPUT_PREFIX_TINK),
    ])
    // Keyset { primary_key_id = keyId; repeated key = 2 } — fresh key, no cache.
    return concat([protoVarint(1, keyId), protoBytes(2, key)])
  } finally {
    if (rawKey) rawKey.fill(0)
    if (idBytes) idBytes.fill(0)
    if (aesGcmKey) aesGcmKey.fill(0)
    if (keyData) keyData.fill(0)
    if (key) key.fill(0)
  }
}
