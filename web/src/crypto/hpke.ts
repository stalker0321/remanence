import { Aes256Gcm, CipherSuite, DhkemX25519HkdfSha256, HkdfSha256 } from '@hpke/core'
import { ProtocolError } from '../protocol/errors'
import { limits } from '../protocol/limits'
import { parseHpkePrivateKeyset, tinkPrefixKeyId } from './tink'

const suite = new CipherSuite({
  kem: new DhkemX25519HkdfSha256(),
  kdf: new HkdfSha256(),
  aead: new Aes256Gcm(),
})

/**
 * Opens one TINK-prefixed recipient envelope.
 * Wire: 0x01 || key id || X25519 enc (32) || RFC 9180 AES-256-GCM ciphertext.
 * RAW and any other HPKE suite fail closed.
 */
export async function openRecipientEnvelope(
  privateKeyset: Uint8Array,
  info: Uint8Array,
  ciphertext: Uint8Array,
): Promise<Uint8Array> {
  if (info.length === 0 || info.length > 128) throw new ProtocolError('OVERSIZED')
  if (ciphertext.length < 53 || ciphertext.length > limits.recipientEnvelopeCiphertext) {
    throw new ProtocolError('OVERSIZED')
  }
  const keyId = tinkPrefixKeyId(ciphertext)
  const parsed = parseHpkePrivateKeyset(privateKeyset)
  if (parsed.keyId !== keyId) {
    parsed.scalar.fill(0)
    throw new ProtocolError('WRONG_KEY')
  }
  const raw = new Uint8Array(parsed.scalar)
  parsed.scalar.fill(0)
  const recipientKey = await suite.kem.importKey('raw', raw.buffer, false)
  raw.fill(0)
  return openHpke(recipientKey, keyId, info, ciphertext)
}

export async function openHpke(
  recipientKey: CryptoKey,
  keyId: number,
  info: Uint8Array,
  ciphertext: Uint8Array,
): Promise<Uint8Array> {
  if (info.length === 0 || info.length > 128) throw new ProtocolError('OVERSIZED')
  if (ciphertext.length < 53 || ciphertext.length > limits.recipientEnvelopeCiphertext) {
    throw new ProtocolError('OVERSIZED')
  }
  if (tinkPrefixKeyId(ciphertext) !== keyId) throw new ProtocolError('WRONG_KEY')
  try {
    const opened = await suite.open({
      recipientKey,
      enc: ciphertext.subarray(5, 37),
      info,
    }, ciphertext.subarray(37))
    return new Uint8Array(opened)
  } catch (error) {
    if (error instanceof ProtocolError) throw error
    throw new ProtocolError('ENVELOPE_REJECTED')
  }
}
