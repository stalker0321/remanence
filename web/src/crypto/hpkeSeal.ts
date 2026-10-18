import { Aes256Gcm, CipherSuite, DhkemX25519HkdfSha256, HkdfSha256, HpkeError } from '@hpke/core'
import { arrayBuffer, concat, writeUint32 } from '../protocol/bytes'
import { ProtocolError } from '../protocol/errors'
import { limits } from '../protocol/limits'
import { parseHpkePublicKeyset } from './tink'

/**
 * T271 — the missing recipient HPKE SEAL counterpart to `openRecipientEnvelope`.
 *
 * Uses the EXACT existing pinned `@hpke/core 1.9.0` suite
 * (X25519-HKDF-SHA256 / HKDF-SHA256 / AES-256-GCM) and the existing HPKE public
 * keyset parser. Wire frame matches the existing open / fixtures / Android
 * cryptor: `0x01 || keyId(BE32) || enc(32) || RFC9180 AES-256-GCM(ct||tag16)`,
 * total = plaintext + 53 bytes. There is NO separate AEAD AAD and NO extra IV
 * field: the existing open supplies `info` (context info) only and no AEAD AAD,
 * so this seal does the same.
 *
 * Explicit inputs only: recipient public keyset bytes, `info` bytes, plaintext
 * bytes. No route/keyset/account/session/epoch defaults, no nonce argument, no
 * ekm/psk/auth-sender/deterministic options, no test hooks. The library
 * generates a fresh ephemeral key on every call.
 *
 * Owned-copy cleanup (learned from T263/T267): `parsed.keyId` and the parsed
 * public-key copy are captured, and owned `info`/`plaintext`/import buffers are
 * created inside one `try` BEFORE the first await; `finally` wipes them on
 * success and on import/seal/partial-copy failure. Caller arrays are never
 * mutated. The library's internal `SenderContext`/ephemeral secret and any
 * `CryptoKey` cannot be zeroized from JS; that limitation is explicit, not
 * claimed away. No total-heap-erasure claim.
 */

const suite = new CipherSuite({
  kem: new DhkemX25519HkdfSha256(),
  kdf: new HkdfSha256(),
  aead: new Aes256Gcm(),
})

const INFO_MAX = 128
const TINK_PREFIX_BYTE = 1
const ENC_BYTES = 32
const TAG_BYTES = 16
const MIN_CIPHERTEXT_BYTES = 5 + ENC_BYTES + TAG_BYTES

export async function sealRecipientEnvelope(
  publicKeyset: Uint8Array,
  info: Uint8Array,
  plaintext: Uint8Array,
): Promise<Uint8Array> {
  // Preflight BEFORE any import / encapsulation / RNG.
  if (info.length === 0 || info.length > INFO_MAX) throw new ProtocolError('OVERSIZED')
  if (plaintext.length === 0) throw new ProtocolError('EMPTY')
  if (plaintext.length + MIN_CIPHERTEXT_BYTES > limits.recipientEnvelopeCiphertext) {
    throw new ProtocolError('OVERSIZED')
  }
  const parsed = parseHpkePublicKeyset(publicKeyset)
  const keyId = parsed.keyId

  // Owned buffers are registered BEFORE the first await, so caller mutation
  // during import/seal cannot change the emitted binding/content.
  let ownedKey: Uint8Array | null = null
  let importBuffer: ArrayBuffer | null = null
  let infoBuffer: ArrayBuffer | null = null
  let plaintextBuffer: ArrayBuffer | null = null
  try {
    ownedKey = parsed.raw
    infoBuffer = arrayBuffer(info)
    plaintextBuffer = arrayBuffer(plaintext)
    importBuffer = arrayBuffer(ownedKey)
    const recipientPublicKey = await suite.kem.importKey('raw', importBuffer, true)
    const sealed = await suite.seal({ recipientPublicKey, info: infoBuffer }, plaintextBuffer)
    return concat([Uint8Array.of(TINK_PREFIX_BYTE), writeUint32(keyId), new Uint8Array(sealed.enc), new Uint8Array(sealed.ct)])
  } catch (error) {
    if (error instanceof ProtocolError) throw error
    // Known HPKE/WebCrypto failures become the envelope-level rejection; other
    // (programming) errors are NOT broadly silenced.
    if (error instanceof HpkeError) throw new ProtocolError('ENVELOPE_REJECTED')
    if (typeof DOMException !== 'undefined' && error instanceof DOMException) {
      throw new ProtocolError('ENVELOPE_REJECTED')
    }
    throw error
  } finally {
    if (ownedKey) ownedKey.fill(0)
    if (importBuffer) new Uint8Array(importBuffer).fill(0)
    if (infoBuffer) new Uint8Array(infoBuffer).fill(0)
    if (plaintextBuffer) new Uint8Array(plaintextBuffer).fill(0)
  }
}
