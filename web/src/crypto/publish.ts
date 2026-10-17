import { arrayBuffer, concat, writeUint32 } from '../protocol/bytes'
import { publishInput } from '../protocol/context'
import { ProtocolError } from '../protocol/errors'
import { parseEd25519PrivateKeyset, parseEd25519PublicKeyset, tinkPrefixKeyId } from './tink'

export async function signPublishStatement(privateKeyset: Uint8Array, statement: Uint8Array): Promise<Uint8Array> {
  const parsed = parseEd25519PrivateKeyset(privateKeyset)
  const key = await importSeed(parsed.scalar)
  parsed.scalar.fill(0)
  return signWithKey(key, parsed.keyId, statement)
}

export async function signWithKey(key: CryptoKey, keyId: number, statement: Uint8Array): Promise<Uint8Array> {
  const signature = new Uint8Array(await crypto.subtle.sign('Ed25519', key, arrayBuffer(publishInput(statement))))
  if (signature.length !== 64) throw new ProtocolError('BAD_SIGNATURE')
  return concat([new Uint8Array([1]), writeUint32(keyId), signature])
}

export async function verifyPublishStatement(
  publicKeyset: Uint8Array,
  statement: Uint8Array,
  signature: Uint8Array,
): Promise<void> {
  if (signature.length !== 69) throw new ProtocolError('BAD_SIGNATURE')
  const parsed = parseEd25519PublicKeyset(publicKeyset)
  if (tinkPrefixKeyId(signature) !== parsed.keyId) throw new ProtocolError('WRONG_KEY')
  const key = await crypto.subtle.importKey('raw', arrayBuffer(parsed.raw), 'Ed25519', false, ['verify'])
  const ok = await crypto.subtle.verify('Ed25519', key, arrayBuffer(signature.subarray(5)), arrayBuffer(publishInput(statement)))
  if (!ok) throw new ProtocolError('BAD_SIGNATURE')
}

async function importSeed(seed: Uint8Array): Promise<CryptoKey> {
  const der = new Uint8Array(16 + seed.length)
  der.set([0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20])
  der.set(seed, 16)
  try {
    return await crypto.subtle.importKey('pkcs8', arrayBuffer(der), 'Ed25519', false, ['sign'])
  } finally {
    der.fill(0)
  }
}
