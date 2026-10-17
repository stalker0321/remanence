import { describe, expect, it } from 'vitest'
import { decryptArtifact } from '../src/crypto/artifact'
import { arrayBuffer, concat, utf8, writeUint32 } from '../src/protocol/bytes'
import { artifactAad } from '../src/protocol/context'
import { ProtocolError } from '../src/protocol/errors'
import { limits } from '../src/protocol/limits'
import { protoBytes, protoVarint } from '../src/protocol/proto'

// JS producer. This is the ADR-005 framing (TINK prefix || IV || AES-GCM),
// not a ciphertext produced by Android or Tink.

const keyId = 77
const plain = utf8('artifact-plaintext')
const aad = artifactAad({
  capsuleId: '00010203-0405-0607-0809-0a0b0c0d0e0f',
  blobId: '00000000-0000-0000-0000-0000000000e5',
  kind: 'PHOTO',
  ordinal: 2,
  senderUserId: '10111213-1415-1617-1819-1a1b1c1d1e1f',
  recipientUserId: '20212223-2425-2627-2829-2a2b2c2d2e2f',
})

function aesKeyset(raw: Uint8Array, id = keyId, prefixType = 1): Uint8Array {
  const material = concat([protoBytes(2, raw)])
  const data = concat([
    protoBytes(1, utf8('type.googleapis.com/google.crypto.tink.AesGcmKey')),
    protoBytes(2, material),
    protoVarint(3, 1),
  ])
  const key = concat([
    protoBytes(1, data),
    protoVarint(2, 1),
    protoVarint(3, id),
    protoVarint(4, prefixType),
  ])
  return concat([protoVarint(1, id), protoBytes(2, key)])
}

async function framed(raw: Uint8Array, associated = aad): Promise<Uint8Array> {
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const key = await crypto.subtle.importKey('raw', arrayBuffer(raw), 'AES-GCM', false, ['encrypt'])
  const body = new Uint8Array(await crypto.subtle.encrypt({
    name: 'AES-GCM',
    iv: arrayBuffer(iv),
    additionalData: arrayBuffer(associated),
  }, key, arrayBuffer(plain)))
  return concat([new Uint8Array([1]), writeUint32(keyId), iv, body])
}

describe('artifact AEAD', () => {
  it('decrypts a JS-produced TINK frame and rejects closed failures', async () => {
    const raw = crypto.getRandomValues(new Uint8Array(32))
    const keyset = aesKeyset(raw)
    const ciphertext = await framed(raw)
    expect(new TextDecoder().decode(await decryptArtifact(keyset, aad, ciphertext, 'PHOTO'))).toBe('artifact-plaintext')

    const rawPrefix = ciphertext.slice()
    rawPrefix[0] = 0
    await expect(decryptArtifact(keyset, aad, rawPrefix, 'PHOTO')).rejects.toBeInstanceOf(ProtocolError)

    const wrongKey = ciphertext.slice()
    wrongKey[4] ^= 1
    await expect(decryptArtifact(keyset, aad, wrongKey, 'PHOTO')).rejects.toBeInstanceOf(ProtocolError)

    const flipped = ciphertext.slice()
    flipped[flipped.length - 1] ^= 1
    await expect(decryptArtifact(keyset, aad, flipped, 'PHOTO')).rejects.toBeInstanceOf(ProtocolError)

    const wrongAad = aad.slice()
    wrongAad[wrongAad.length - 1] ^= 1
    await expect(decryptArtifact(keyset, wrongAad, ciphertext, 'PHOTO')).rejects.toBeInstanceOf(ProtocolError)

    await expect(decryptArtifact(keyset, aad, ciphertext.slice(0, 20), 'PHOTO')).rejects.toBeInstanceOf(ProtocolError)
    await expect(decryptArtifact(aesKeyset(raw, keyId, 3), aad, ciphertext, 'PHOTO')).rejects.toBeInstanceOf(ProtocolError)
    await expect(decryptArtifact(keyset, aad, new Uint8Array(limits.contentCiphertext + 1), 'CONTENT_MANIFEST')).rejects.toBeInstanceOf(ProtocolError)
  })
})
