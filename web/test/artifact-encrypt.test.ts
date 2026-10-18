import { afterEach, describe, expect, it, vi } from 'vitest'
import { decryptArtifact } from '../src/crypto/artifact'
import { artifactEncryptTestPort, encryptArtifact, type ArtifactEncryptOwnedBuffers } from '../src/crypto/artifactEncrypt'
import { concat, readUint32, utf8 } from '../src/protocol/bytes'
import { artifactAad } from '../src/protocol/context'
import { ProtocolError } from '../src/protocol/errors'
import { limits } from '../src/protocol/limits'
import { protoBytes, protoVarint } from '../src/protocol/proto'

const AES_URL = 'type.googleapis.com/google.crypto.tink.AesGcmKey'
const KEY_ID = 1_234_567
const RAW_KEY = Uint8Array.from({ length: 32 }, (_value, index) => index + 1)
const CAPSULE = '00010203-0405-0607-0809-0a0b0c0d0e0f'
const BLOB = '00000000-0000-0000-0000-0000000000e5'
const SENDER = '10111213-1415-1617-1819-1a1b1c1d1e1f'
const RECIPIENT = '20212223-2425-2627-2829-2a2b2c2d2e2f'

/** Test-only serialized Tink AES256_GCM/TINK/ENABLED single-key keyset. */
function aesKeyset(raw: Uint8Array, keyId = KEY_ID, prefixType = 1): Uint8Array {
  const material = concat([protoBytes(2, raw)])
  const data = concat([protoBytes(1, utf8(AES_URL)), protoBytes(2, material), protoVarint(3, 1)])
  const key = concat([protoBytes(1, data), protoVarint(2, 1), protoVarint(3, keyId), protoVarint(4, prefixType)])
  return concat([protoVarint(1, keyId), protoBytes(2, key)])
}

function aadFor(overrides: Partial<{ capsuleId: string; blobId: string; kind: 'PHOTO' | 'CONTENT_MANIFEST' | 'RECOGNITION_MANIFEST'; ordinal: number; senderUserId: string; recipientUserId: string }> = {}) {
  return artifactAad({
    capsuleId: CAPSULE,
    blobId: BLOB,
    kind: 'PHOTO',
    ordinal: 2,
    senderUserId: SENDER,
    recipientUserId: RECIPIENT,
    ...overrides,
  })
}

function sameBytes(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index])
}

afterEach(() => {
  artifactEncryptTestPort.onOwnedBuffers = null
  vi.restoreAllMocks()
})

describe('artifact WebCrypto encrypt', () => {
  it('round-trips through the existing decrypt for every artifact kind', async () => {
    const keyset = aesKeyset(RAW_KEY)
    for (const kind of ['PHOTO', 'CONTENT_MANIFEST', 'RECOGNITION_MANIFEST'] as const) {
      const aad = aadFor({ kind, ordinal: kind === 'PHOTO' ? 2 : -1 })
      const plaintext = utf8(`artifact-${kind}`)
      const ciphertext = await encryptArtifact(keyset, aad, plaintext, kind)
      expect(ciphertext.length).toBe(plaintext.length + limits.artifactOverhead)
      expect(sameBytes(await decryptArtifact(keyset, aad, ciphertext, kind), plaintext)).toBe(true)
    }
  })

  it('frames 0x01 || BE keyId || fresh 12-byte IV || AES-GCM body', async () => {
    const keyset = aesKeyset(RAW_KEY)
    const aad = aadFor()
    const plaintext = utf8('frame-check')
    const first = await encryptArtifact(keyset, aad, plaintext, 'PHOTO')
    expect(first[0]).toBe(1)
    expect(readUint32(first, 1)).toBe(KEY_ID)
    expect(first.slice(5, 17).length).toBe(12)
    expect(first.length).toBe(plaintext.length + limits.artifactOverhead)
    const second = await encryptArtifact(keyset, aad, plaintext, 'PHOTO')
    expect(sameBytes(first.slice(5, 17), second.slice(5, 17))).toBe(false)
    expect(readUint32(second, 1)).toBe(KEY_ID)
  })

  it('refuses preflight failures before any RNG/import/encrypt side effect', async () => {
    const keyset = aesKeyset(RAW_KEY)
    const importSpy = vi.spyOn(crypto.subtle, 'importKey')
    const encryptSpy = vi.spyOn(crypto.subtle, 'encrypt')
    const randomSpy = vi.spyOn(crypto, 'getRandomValues')
    const badAad = aadFor()
    badAad[0] ^= 1
    await expect(encryptArtifact(keyset, badAad, utf8('x'), 'PHOTO')).rejects.toMatchObject({ code: 'AAD_REJECTED' })
    await expect(encryptArtifact(keyset, aadFor(), new Uint8Array(0), 'PHOTO')).rejects.toMatchObject({ code: 'EMPTY' })
    await expect(encryptArtifact(keyset, aadFor(), utf8('x'), 'BOGUS' as never)).rejects.toMatchObject({ code: 'UNKNOWN_KIND' })
    await expect(
      encryptArtifact(keyset, aadFor({ kind: 'CONTENT_MANIFEST', ordinal: -1 }), new Uint8Array(limits.contentCiphertext - limits.artifactOverhead + 1), 'CONTENT_MANIFEST'),
    ).rejects.toMatchObject({ code: 'OVERSIZED' })
    await expect(encryptArtifact(new Uint8Array(3), aadFor(), utf8('x'), 'PHOTO')).rejects.toBeInstanceOf(ProtocolError)
    expect(importSpy).not.toHaveBeenCalled()
    expect(encryptSpy).not.toHaveBeenCalled()
    expect(randomSpy).not.toHaveBeenCalled()
  })

  it('accepts the exact plaintext budget boundary and refuses the next byte', async () => {
    const keyset = aesKeyset(RAW_KEY)
    const cases = [
      ['CONTENT_MANIFEST', limits.contentCiphertext - limits.artifactOverhead, -1],
      ['RECOGNITION_MANIFEST', limits.recognitionCiphertext - limits.artifactOverhead, -1],
      ['PHOTO', limits.photoCiphertext - limits.artifactOverhead, 0],
    ] as const
    for (const [kind, maxPlaintext, ordinal] of cases) {
      const aad = aadFor({ kind, ordinal })
      const at = new Uint8Array(maxPlaintext).fill(7)
      const ciphertext = await encryptArtifact(keyset, aad, at, kind)
      expect(ciphertext.length).toBe(maxPlaintext + limits.artifactOverhead)
      expect(sameBytes(await decryptArtifact(keyset, aad, ciphertext, kind), at)).toBe(true)
      await expect(encryptArtifact(keyset, aad, new Uint8Array(maxPlaintext + 1), kind)).rejects.toMatchObject({ code: 'OVERSIZED' })
    }
  })

  it('binds exactly the supplied AAD/route/kind/ordinal', async () => {
    const keyset = aesKeyset(RAW_KEY)
    const plaintext = utf8('aad-binding')
    const ciphertext = await encryptArtifact(keyset, aadFor(), plaintext, 'PHOTO')
    const wrongAads = [
      aadFor({ capsuleId: '99999999-9999-4999-8999-999999999999' }),
      aadFor({ blobId: '99999999-9999-4999-8999-999999999999' }),
      aadFor({ kind: 'CONTENT_MANIFEST', ordinal: -1 }),
      aadFor({ ordinal: 3 }),
      aadFor({ senderUserId: '99999999-9999-4999-8999-999999999999' }),
      aadFor({ recipientUserId: '99999999-9999-4999-8999-999999999999' }),
    ]
    for (const wrong of wrongAads) {
      await expect(decryptArtifact(keyset, wrong, ciphertext, 'PHOTO')).rejects.toBeInstanceOf(ProtocolError)
    }
  })

  it('refuses wrong key/prefix and tampered IV/body/tag', async () => {
    const keyset = aesKeyset(RAW_KEY)
    const aad = aadFor()
    const ciphertext = await encryptArtifact(keyset, aad, utf8('tamper'), 'PHOTO')
    const otherKey = Uint8Array.from({ length: 32 }, (_value, index) => 255 - index)
    await expect(decryptArtifact(aesKeyset(otherKey, KEY_ID + 1), aad, ciphertext, 'PHOTO')).rejects.toMatchObject({ code: 'WRONG_KEY' })
    await expect(decryptArtifact(aesKeyset(otherKey, KEY_ID), aad, ciphertext, 'PHOTO')).rejects.toMatchObject({ code: 'ARTIFACT_REJECTED' })
    const badPrefix = ciphertext.slice()
    badPrefix[0] = 0
    await expect(decryptArtifact(keyset, aad, badPrefix, 'PHOTO')).rejects.toBeInstanceOf(ProtocolError)
    for (const index of [5, 12, ciphertext.length - 1]) {
      const tampered = ciphertext.slice()
      tampered[index] ^= 1
      await expect(decryptArtifact(keyset, aad, tampered, 'PHOTO')).rejects.toBeInstanceOf(ProtocolError)
    }
  })

  it('wipes owned copies on success and never wipes caller inputs', async () => {
    const keyset = aesKeyset(RAW_KEY)
    const aad = aadFor()
    const plaintext = utf8('owned-success')
    let owned: ArtifactEncryptOwnedBuffers | null = null
    artifactEncryptTestPort.onOwnedBuffers = (buffers) => {
      owned = buffers
    }
    await encryptArtifact(keyset, aad, plaintext, 'PHOTO')
    expect(owned).not.toBeNull()
    expect(owned!.plaintext.every((value) => value === 0)).toBe(true)
    expect(owned!.aad.every((value) => value === 0)).toBe(true)
    expect(owned!.key.every((value) => value === 0)).toBe(true)
    expect(sameBytes(plaintext, utf8('owned-success'))).toBe(true)
    expect(sameBytes(aad, aadFor())).toBe(true)
    expect(sameBytes(keyset, aesKeyset(RAW_KEY))).toBe(true)
  })

  it('wipes owned copies on import rejection', async () => {
    const keyset = aesKeyset(RAW_KEY)
    const aad = aadFor()
    const plaintext = utf8('owned-import-fail')
    let owned: ArtifactEncryptOwnedBuffers | null = null
    artifactEncryptTestPort.onOwnedBuffers = (buffers) => {
      owned = buffers
    }
    vi.spyOn(crypto.subtle, 'importKey').mockRejectedValue(new DOMException('nope', 'OperationError'))
    await expect(encryptArtifact(keyset, aad, plaintext, 'PHOTO')).rejects.toMatchObject({ code: 'ARTIFACT_REJECTED' })
    expect(owned).not.toBeNull()
    expect(owned!.plaintext.every((value) => value === 0)).toBe(true)
    expect(owned!.aad.every((value) => value === 0)).toBe(true)
    expect(owned!.key.every((value) => value === 0)).toBe(true)
    expect(sameBytes(plaintext, utf8('owned-import-fail'))).toBe(true)
    expect(sameBytes(aad, aadFor())).toBe(true)
  })

  it('wipes owned copies on encrypt rejection', async () => {
    const keyset = aesKeyset(RAW_KEY)
    const aad = aadFor()
    const plaintext = utf8('owned-encrypt-fail')
    let owned: ArtifactEncryptOwnedBuffers | null = null
    artifactEncryptTestPort.onOwnedBuffers = (buffers) => {
      owned = buffers
    }
    vi.spyOn(crypto.subtle, 'encrypt').mockRejectedValue(new DOMException('nope', 'OperationError'))
    await expect(encryptArtifact(keyset, aad, plaintext, 'PHOTO')).rejects.toMatchObject({ code: 'ARTIFACT_REJECTED' })
    expect(owned).not.toBeNull()
    expect(owned!.plaintext.every((value) => value === 0)).toBe(true)
    expect(owned!.aad.every((value) => value === 0)).toBe(true)
    expect(owned!.key.every((value) => value === 0)).toBe(true)
    expect(sameBytes(plaintext, utf8('owned-encrypt-fail'))).toBe(true)
    expect(sameBytes(aad, aadFor())).toBe(true)
  })

  it('snapshots caller inputs during a deferred import (mutation cannot change the frame)', async () => {
    const keyset = aesKeyset(RAW_KEY)
    const aad = aadFor()
    const plaintext = utf8('deferred-import')
    const originalKeyset = keyset.slice()
    const originalAad = aad.slice()
    const originalPlaintext = plaintext.slice()
    const realImport = crypto.subtle.importKey.bind(crypto.subtle) as (...args: unknown[]) => Promise<CryptoKey>
    vi.spyOn(crypto.subtle, 'importKey').mockImplementation(((async (...args: unknown[]) => {
      await new Promise((resolve) => setTimeout(resolve, 10))
      return realImport(...args)
    }) as unknown) as typeof crypto.subtle.importKey)
    const pending = encryptArtifact(keyset, aad, plaintext, 'PHOTO')
    keyset[10] ^= 0xff
    aad[0] ^= 0xff
    plaintext[0] ^= 0xff
    const ciphertext = await pending
    expect(sameBytes(await decryptArtifact(originalKeyset, originalAad, ciphertext, 'PHOTO'), originalPlaintext)).toBe(true)
    // Deliberate caller mutations are NOT undone by the encoder.
    expect(sameBytes(plaintext, originalPlaintext)).toBe(false)
  })
})
