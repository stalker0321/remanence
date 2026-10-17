import { readUint32 } from '../protocol/bytes'
import { ProtocolError } from '../protocol/errors'
import { fieldBytes, fieldNumber, readMessage } from '../protocol/proto'

const AES_URL = 'type.googleapis.com/google.crypto.tink.AesGcmKey'
const HPKE_PRIVATE_URL = 'type.googleapis.com/google.crypto.tink.HpkePrivateKey'
const ED25519_PRIVATE_URL = 'type.googleapis.com/google.crypto.tink.Ed25519PrivateKey'
const ED25519_PUBLIC_URL = 'type.googleapis.com/google.crypto.tink.Ed25519PublicKey'

export interface AesKey {
  keyId: number
  key: Uint8Array
}

export interface PrivateScalar {
  keyId: number
  scalar: Uint8Array
}

export function parseAes256GcmKeyset(bytes: Uint8Array): AesKey {
  const { keyId, value } = oneKey(bytes, AES_URL, 1)
  const key = readMessage(value, [1, 2])
  if (fieldNumber(key, 1, 0) !== 0) throw new ProtocolError('UNKNOWN_VERSION')
  const raw = fieldBytes(key, 2)
  if (raw.length !== 32) throw new ProtocolError('INVALID_KEY')
  return { keyId, key: raw }
}

export function parseHpkePrivateKeyset(bytes: Uint8Array): PrivateScalar {
  const { keyId, value } = oneKey(bytes, HPKE_PRIVATE_URL, 2)
  const key = readMessage(value, [1, 2, 3])
  if (fieldNumber(key, 1, 0) !== 0) throw new ProtocolError('UNKNOWN_VERSION')
  const pub = readMessage(fieldBytes(key, 2), [1, 2, 3])
  const params = readMessage(fieldBytes(pub, 2), [1, 2, 3])
  if (fieldNumber(params, 1) !== 1 || fieldNumber(params, 2) !== 1 || fieldNumber(params, 3) !== 2) {
    throw new ProtocolError('UNSUPPORTED_HPKE')
  }
  if (fieldNumber(pub, 1, 0) !== 0 || fieldBytes(pub, 3).length !== 32) throw new ProtocolError('INVALID_KEY')
  const scalar = fieldBytes(key, 3)
  if (scalar.length !== 32) throw new ProtocolError('INVALID_KEY')
  return { keyId, scalar }
}

export function parseEd25519PrivateKeyset(bytes: Uint8Array): PrivateScalar {
  const { keyId, value } = oneKey(bytes, ED25519_PRIVATE_URL, 2)
  const key = readMessage(value, [1, 2, 3])
  if (fieldNumber(key, 1, 0) !== 0) throw new ProtocolError('UNKNOWN_VERSION')
  const scalar = fieldBytes(key, 2)
  const pub = readMessage(fieldBytes(key, 3), [1, 2])
  if (scalar.length !== 32 || fieldNumber(pub, 1, 0) !== 0 || fieldBytes(pub, 2).length !== 32) {
    throw new ProtocolError('INVALID_KEY')
  }
  return { keyId, scalar }
}

export function parseEd25519PublicKeyset(bytes: Uint8Array): { keyId: number; raw: Uint8Array } {
  const { keyId, value } = oneKey(bytes, ED25519_PUBLIC_URL, 3)
  const pub = readMessage(value, [1, 2])
  if (fieldNumber(pub, 1, 0) !== 0) throw new ProtocolError('UNKNOWN_VERSION')
  const raw = fieldBytes(pub, 2)
  if (raw.length !== 32) throw new ProtocolError('INVALID_KEY')
  return { keyId, raw }
}

export function tinkPrefixKeyId(bytes: Uint8Array): number {
  if (bytes.length < 5 || bytes[0] !== 1) throw new ProtocolError('BAD_PREFIX')
  return readUint32(bytes, 1)
}

function oneKey(bytes: Uint8Array, typeUrl: string, material: number): { keyId: number; value: Uint8Array } {
  if (bytes.length === 0) throw new ProtocolError('INVALID_KEY')
  const set = readMessage(bytes, [1, 2])
  const primary = fieldNumber(set, 1)
  const key = readMessage(fieldBytes(set, 2), [1, 2, 3, 4])
  const keyId = fieldNumber(key, 3)
  if (primary === 0 || primary !== keyId || fieldNumber(key, 2) !== 1 || fieldNumber(key, 4) !== 1) {
    throw new ProtocolError('INVALID_KEY')
  }
  const data = readMessage(fieldBytes(key, 1), [1, 2, 3])
  let url = ''
  try {
    url = new TextDecoder('utf-8', { fatal: true }).decode(fieldBytes(data, 1))
  } catch {
    throw new ProtocolError('UNSUPPORTED_KEY')
  }
  if (url !== typeUrl || fieldNumber(data, 3) !== material) throw new ProtocolError('UNSUPPORTED_KEY')
  return { keyId, value: fieldBytes(data, 2) }
}
