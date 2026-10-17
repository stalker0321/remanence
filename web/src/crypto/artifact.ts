import { arrayBuffer, utf8 } from '../protocol/bytes'
import { ARTIFACT_AAD_PREFIX, type ArtifactKind } from '../protocol/context'
import { ProtocolError } from '../protocol/errors'
import { limits } from '../protocol/limits'
import { parseAes256GcmKeyset, tinkPrefixKeyId } from './tink'

const prefix = utf8(ARTIFACT_AAD_PREFIX)

export async function decryptArtifact(
  keyset: Uint8Array,
  aad: Uint8Array,
  ciphertext: Uint8Array,
  kind: ArtifactKind,
): Promise<Uint8Array> {
  const cap = kind === 'PHOTO'
    ? limits.photoCiphertext
    : kind === 'RECOGNITION_MANIFEST'
      ? limits.recognitionCiphertext
      : kind === 'CONTENT_MANIFEST'
        ? limits.contentCiphertext
        : 0
  if (cap === 0) throw new ProtocolError('UNKNOWN_KIND')
  if (ciphertext.length < limits.artifactOverhead || ciphertext.length > cap) throw new ProtocolError('OVERSIZED')
  if (!startsWith(aad, prefix) || aad[prefix.length] !== 0) throw new ProtocolError('AAD_REJECTED')
  const keyId = tinkPrefixKeyId(ciphertext)
  const parsed = parseAes256GcmKeyset(keyset)
  if (parsed.keyId !== keyId) {
    parsed.key.fill(0)
    throw new ProtocolError('WRONG_KEY')
  }
  const raw = arrayBuffer(parsed.key)
  parsed.key.fill(0)
  const aes = await crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['decrypt'])
  new Uint8Array(raw).fill(0)
  try {
    const plain = new Uint8Array(await crypto.subtle.decrypt({
      name: 'AES-GCM',
      iv: arrayBuffer(ciphertext.subarray(5, 17)),
      additionalData: arrayBuffer(aad),
      tagLength: 128,
    }, aes, arrayBuffer(ciphertext.subarray(17))))
    if (plain.length === 0) throw new ProtocolError('EMPTY')
    return plain
  } catch (error) {
    if (error instanceof ProtocolError) throw error
    throw new ProtocolError('ARTIFACT_REJECTED')
  }
}

function startsWith(bytes: Uint8Array, head: Uint8Array): boolean {
  if (bytes.length < head.length) return false
  for (let i = 0; i < head.length; i++) if (bytes[i] !== head[i]) return false
  return true
}
