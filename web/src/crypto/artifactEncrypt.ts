import { arrayBuffer, concat, utf8, writeUint32 } from '../protocol/bytes'
import { ARTIFACT_AAD_PREFIX, type ArtifactKind } from '../protocol/context'
import { ProtocolError } from '../protocol/errors'
import { limits } from '../protocol/limits'
import { parseAes256GcmKeyset } from './tink'

/**
 * T263 — the missing WebCrypto ENCRYPT counterpart to `decryptArtifact`.
 *
 * Frame EXACTLY the existing contract (`docs/security.md` section 6.2):
 * `0x01 || primaryKeyId(BE32) || randomIV(12) || AES-256-GCM(ciphertext||tag16)`
 * = 33-byte overhead, no RAW/LEGACY/CRUNCHY alternate. The caller supplies an
 * explicit serialized Tink `AES256_GCM` keyset, the exact versioned AAD bytes,
 * the plaintext and the artifact kind. No keyset generation/serialization, no
 * caller route/identity defaults, no nonce argument, no key derivation from
 * images/IDs. Fresh IV per call via `crypto.getRandomValues`.
 *
 * Caller-owned arrays are never mutated. Every sensitive buffer THIS function
 * owns (the parsed key copy, plaintext/AAD copies, import and encrypt buffers,
 * IV) is acquired/assigned inside one `try` and wiped in `finally`, so a throw
 * during a partial copy, import or encrypt cannot evade the wipe. WebCrypto's
 * internal key/cipher copies cannot be zeroized from JS; that limitation is
 * explicit, not claimed away.
 */

const PREFIX = utf8(ARTIFACT_AAD_PREFIX)
const IV_BYTES = 12
const TAG_BITS = 128
const TINK_PREFIX_BYTE = 1

export async function encryptArtifact(
  keyset: Uint8Array,
  aad: Uint8Array,
  plaintext: Uint8Array,
  kind: ArtifactKind,
): Promise<Uint8Array> {
  // Preflight BEFORE any random generation / import / encrypt.
  const cap = kind === 'PHOTO'
    ? limits.photoCiphertext
    : kind === 'RECOGNITION_MANIFEST'
      ? limits.recognitionCiphertext
      : kind === 'CONTENT_MANIFEST'
        ? limits.contentCiphertext
        : 0
  if (cap === 0) throw new ProtocolError('UNKNOWN_KIND')
  if (!startsWith(aad, PREFIX) || aad[PREFIX.length] !== 0) throw new ProtocolError('AAD_REJECTED')
  if (plaintext.length === 0) throw new ProtocolError('EMPTY')
  if (plaintext.length + limits.artifactOverhead > cap) throw new ProtocolError('OVERSIZED')
  // Existing parser only (single-key AES256/TINK/ENABLED); its key material is
  // already an owned copy, never an alias of the caller's keyset.
  const parsed = parseAes256GcmKeyset(keyset)
  const keyId = parsed.keyId

  // Every owned sensitive buffer is assigned inside the try and wiped in the
  // finally. `ownedKey` is registered FIRST so the parsed secret is covered
  // before any later copy/allocation can throw.
  let ownedKey: Uint8Array | null = null
  let ownedPlaintext: Uint8Array | null = null
  let ownedAad: Uint8Array | null = null
  let importBuffer: ArrayBuffer | null = null
  let aadBuffer: ArrayBuffer | null = null
  let plaintextBuffer: ArrayBuffer | null = null
  let iv: Uint8Array | null = null
  try {
    ownedKey = parsed.key
    // Own the caller's mutable inputs synchronously, BEFORE the first await, so
    // caller mutation during import/encrypt cannot change the emitted
    // binding/content.
    ownedPlaintext = plaintext.slice()
    ownedAad = aad.slice()
    importBuffer = arrayBuffer(ownedKey)
    const key = await crypto.subtle.importKey('raw', importBuffer, 'AES-GCM', false, ['encrypt'])
    iv = crypto.getRandomValues(new Uint8Array(IV_BYTES))
    aadBuffer = arrayBuffer(ownedAad)
    plaintextBuffer = arrayBuffer(ownedPlaintext)
    const body = new Uint8Array(await crypto.subtle.encrypt({
      name: 'AES-GCM',
      iv: arrayBuffer(iv),
      additionalData: aadBuffer,
      tagLength: TAG_BITS,
    }, key, plaintextBuffer))
    return concat([Uint8Array.of(TINK_PREFIX_BYTE), writeUint32(keyId), iv, body])
  } catch (error) {
    if (error instanceof ProtocolError) throw error
    // A WebCrypto operation failure is the artifact-level rejection; other
    // (programming) errors are NOT broadly silenced.
    if (typeof DOMException !== 'undefined' && error instanceof DOMException) {
      throw new ProtocolError('ARTIFACT_REJECTED')
    }
    throw error
  } finally {
    if (ownedKey) ownedKey.fill(0)
    if (ownedPlaintext) ownedPlaintext.fill(0)
    if (ownedAad) ownedAad.fill(0)
    if (importBuffer) new Uint8Array(importBuffer).fill(0)
    if (aadBuffer) new Uint8Array(aadBuffer).fill(0)
    if (plaintextBuffer) new Uint8Array(plaintextBuffer).fill(0)
    if (iv) iv.fill(0)
  }
}

function startsWith(bytes: Uint8Array, head: Uint8Array): boolean {
  if (bytes.length < head.length) return false
  for (let i = 0; i < head.length; i++) if (bytes[i] !== head[i]) return false
  return true
}
