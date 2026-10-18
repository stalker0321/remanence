import { utf8 } from '../protocol/bytes'

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
 * sign-bit mask, no global collision store). Local implementation choice for
 * the observed nonzero-uint32 domain, not RNG-byte or Android interop parity.
 *
 * Owned-secret cleanup (T267 R2): the raw key and the key-id draw are
 * PREALLOCATED and registered before the CSPRNG effect, so a fill-then-throw
 * still leaves a tracked buffer for `finally`. The entire keyset is assembled
 * into ONE preallocated output buffer: public tags/lengths/type-url/key-id are
 * written directly, and the raw key is copied EXACTLY ONCE into that output.
 * There are no secret-bearing `protoBytes`/`concat` wrapper temporaries. The
 * `finally` wipes the raw key and key-id buffers on success/failure and wipes a
 * partially-written output on failure; the returned keyset is intentionally
 * NOT wiped (it is the secret caller-owned result). JS/WebCrypto internal
 * copies cannot be zeroized from here; no total-heap-erasure claim.
 */

const AES_GCM_TYPE_URL = utf8('type.googleapis.com/google.crypto.tink.AesGcmKey')
const KEY_BYTES = 32
const KEY_ID_BYTES = 4
const KEY_STATUS_ENABLED = 1
const KEY_MATERIAL_SYMMETRIC = 1
const OUTPUT_PREFIX_TINK = 1

const TAG_KEYSET_PRIMARY = 0x08
const TAG_KEYSET_KEY = 0x12
const TAG_KEY_DATA = 0x0a
const TAG_KEY_STATUS = 0x10
const TAG_KEY_ID = 0x18
const TAG_KEY_OUTPUT_PREFIX = 0x20
const TAG_KEYDATA_TYPE_URL = 0x0a
const TAG_KEYDATA_VALUE = 0x12
const TAG_KEYDATA_MATERIAL = 0x18
const TAG_AES_GCM_KEY_VALUE = 0x12

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

export function generateCapsuleKeyset(): Uint8Array {
  // Registered BEFORE the CSPRNG effect: a fill-then-throw cannot lose the
  // owned reference, so `finally` always wipes the partially-filled buffer.
  const rawKey = new Uint8Array(KEY_BYTES)
  const idBytes = new Uint8Array(KEY_ID_BYTES)
  let keyset: Uint8Array | null = null
  let success = false
  try {
    crypto.getRandomValues(rawKey)
    let keyId = 0
    do {
      crypto.getRandomValues(idBytes)
      keyId = ((idBytes[0]! << 24) | (idBytes[1]! << 16) | (idBytes[2]! << 8) | idBytes[3]!) >>> 0
    } while (keyId === 0)

    // Compute every length from public/known values, then allocate ONE buffer.
    const urlLength = AES_GCM_TYPE_URL.length
    const aesGcmKeyLength = 1 + varintLength(KEY_BYTES) + KEY_BYTES
    const keyDataLength =
      (1 + varintLength(urlLength) + urlLength) +
      (1 + varintLength(aesGcmKeyLength) + aesGcmKeyLength) +
      (1 + varintLength(KEY_MATERIAL_SYMMETRIC))
    const keyLength =
      (1 + varintLength(keyDataLength) + keyDataLength) +
      (1 + varintLength(KEY_STATUS_ENABLED)) +
      (1 + varintLength(keyId)) +
      (1 + varintLength(OUTPUT_PREFIX_TINK))
    const totalLength = (1 + varintLength(keyId)) + (1 + varintLength(keyLength) + keyLength)

    keyset = new Uint8Array(totalLength)
    let offset = 0
    // Keyset.primary_key_id = 1 (varint)
    keyset[offset++] = TAG_KEYSET_PRIMARY
    offset = writeVarint(keyset, offset, keyId)
    // Keyset.key = 2 (Key message)
    keyset[offset++] = TAG_KEYSET_KEY
    offset = writeVarint(keyset, offset, keyLength)
    // Key.key_data = 1 (KeyData message)
    keyset[offset++] = TAG_KEY_DATA
    offset = writeVarint(keyset, offset, keyDataLength)
    // KeyData.type_url = 1 (public ASCII URL)
    keyset[offset++] = TAG_KEYDATA_TYPE_URL
    offset = writeVarint(keyset, offset, urlLength)
    keyset.set(AES_GCM_TYPE_URL, offset)
    offset += urlLength
    // KeyData.value = 2 (AesGcmKey message)
    keyset[offset++] = TAG_KEYDATA_VALUE
    offset = writeVarint(keyset, offset, aesGcmKeyLength)
    // AesGcmKey.key_value = 2 (the SINGLE raw-key copy into the final buffer)
    keyset[offset++] = TAG_AES_GCM_KEY_VALUE
    offset = writeVarint(keyset, offset, KEY_BYTES)
    keyset.set(rawKey, offset)
    offset += KEY_BYTES
    // KeyData.key_material_type = 3
    keyset[offset++] = TAG_KEYDATA_MATERIAL
    offset = writeVarint(keyset, offset, KEY_MATERIAL_SYMMETRIC)
    // Key.status = 2 (ENABLED)
    keyset[offset++] = TAG_KEY_STATUS
    offset = writeVarint(keyset, offset, KEY_STATUS_ENABLED)
    // Key.key_id = 3
    keyset[offset++] = TAG_KEY_ID
    offset = writeVarint(keyset, offset, keyId)
    // Key.output_prefix_type = 4 (TINK)
    keyset[offset++] = TAG_KEY_OUTPUT_PREFIX
    offset = writeVarint(keyset, offset, OUTPUT_PREFIX_TINK)
    if (offset !== totalLength) throw new Error('capsule keyset length mismatch')

    success = true
    return keyset
  } finally {
    rawKey.fill(0)
    idBytes.fill(0)
    // Wipe only a PARTIAL output; the successful returned keyset is caller-owned.
    if (!success && keyset !== null) keyset.fill(0)
  }
}
