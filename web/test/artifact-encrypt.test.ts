import { afterEach, describe, expect, it, vi } from 'vitest'
import * as artifactEncryptModule from '../src/crypto/artifactEncrypt'
import { encryptArtifact } from '../src/crypto/artifactEncrypt'
import { decryptArtifact } from '../src/crypto/artifact'
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

function allZero(bytes: Uint8Array | undefined): boolean {
  return bytes !== undefined && bytes.every((value) => value === 0)
}

interface OwnedCapture {
  ownedPlain?: Uint8Array
  ownedAad?: Uint8Array
  ownedKey?: Uint8Array
}

/**
 * TEST-ONLY spy on the existing `Uint8Array.prototype.slice` call the encoder
 * uses to copy caller inputs. It captures the references to the ACTUAL owned
 * copies (and the parser's 32-byte key copy) in test scope; optionally it can
 * throw on the AAD copy to exercise the partial-copy cleanup path. Restored by
 * `afterEach`. No production hook.
 */
function captureOwnedSlices(
  plaintext: Uint8Array,
  aad: Uint8Array,
  rawKey: Uint8Array,
  options: { throwOnAad?: boolean } = {},
): OwnedCapture {
  const capture: OwnedCapture = {}
  const realSlice = Uint8Array.prototype.slice
  vi.spyOn(Uint8Array.prototype, 'slice').mockImplementation((function (this: Uint8Array, start?: number, end?: number) {
    if (options.throwOnAad && this === aad) throw new Error('partial-copy-failure')
    const result = realSlice.call(this, start, end)
    if (this === plaintext) capture.ownedPlain = result
    if (this === aad) capture.ownedAad = result
    if (result.length === rawKey.length && sameBytes(result, rawKey)) capture.ownedKey = result
    return result
  }) as unknown as typeof Uint8Array.prototype.slice)
  return capture
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('artifact WebCrypto encrypt', () => {
  it('exports only the production encryptArtifact API (no secret observer/setter)', () => {
    expect(Object.keys(artifactEncryptModule)).toEqual(['encryptArtifact'])
    expect('artifactEncryptTestPort' in artifactEncryptModule).toBe(false)
  })

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
    const capture = captureOwnedSlices(plaintext, aad, RAW_KEY)
    const importSpy = vi.spyOn(crypto.subtle, 'importKey')
    const encryptSpy = vi.spyOn(crypto.subtle, 'encrypt')
    await encryptArtifact(keyset, aad, plaintext, 'PHOTO')
    expect(allZero(capture.ownedPlain)).toBe(true)
    expect(allZero(capture.ownedAad)).toBe(true)
    expect(allZero(capture.ownedKey)).toBe(true)
    expect(allZero(new Uint8Array(importSpy.mock.calls[0]![1] as ArrayBuffer))).toBe(true)
    const algorithm = encryptSpy.mock.calls[0]![0] as AesGcmParams
    expect(allZero(new Uint8Array(algorithm.additionalData as ArrayBuffer))).toBe(true)
    expect(allZero(new Uint8Array(encryptSpy.mock.calls[0]![2] as ArrayBuffer))).toBe(true)
    expect(sameBytes(plaintext, utf8('owned-success'))).toBe(true)
    expect(sameBytes(aad, aadFor())).toBe(true)
    expect(sameBytes(keyset, aesKeyset(RAW_KEY))).toBe(true)
  })

  it('wipes owned copies on import rejection', async () => {
    const keyset = aesKeyset(RAW_KEY)
    const aad = aadFor()
    const plaintext = utf8('owned-import-fail')
    const capture = captureOwnedSlices(plaintext, aad, RAW_KEY)
    const importSpy = vi.spyOn(crypto.subtle, 'importKey').mockRejectedValue(new DOMException('nope', 'OperationError'))
    await expect(encryptArtifact(keyset, aad, plaintext, 'PHOTO')).rejects.toMatchObject({ code: 'ARTIFACT_REJECTED' })
    expect(allZero(capture.ownedPlain)).toBe(true)
    expect(allZero(capture.ownedAad)).toBe(true)
    expect(allZero(capture.ownedKey)).toBe(true)
    expect(allZero(new Uint8Array(importSpy.mock.calls[0]![1] as ArrayBuffer))).toBe(true)
    expect(sameBytes(plaintext, utf8('owned-import-fail'))).toBe(true)
    expect(sameBytes(aad, aadFor())).toBe(true)
  })

  it('wipes owned copies on encrypt rejection', async () => {
    const keyset = aesKeyset(RAW_KEY)
    const aad = aadFor()
    const plaintext = utf8('owned-encrypt-fail')
    const capture = captureOwnedSlices(plaintext, aad, RAW_KEY)
    const encryptSpy = vi.spyOn(crypto.subtle, 'encrypt').mockRejectedValue(new DOMException('nope', 'OperationError'))
    await expect(encryptArtifact(keyset, aad, plaintext, 'PHOTO')).rejects.toMatchObject({ code: 'ARTIFACT_REJECTED' })
    expect(allZero(capture.ownedPlain)).toBe(true)
    expect(allZero(capture.ownedAad)).toBe(true)
    expect(allZero(capture.ownedKey)).toBe(true)
    const algorithm = encryptSpy.mock.calls[0]![0] as AesGcmParams
    expect(allZero(new Uint8Array(algorithm.additionalData as ArrayBuffer))).toBe(true)
    expect(allZero(new Uint8Array(encryptSpy.mock.calls[0]![2] as ArrayBuffer))).toBe(true)
    expect(sameBytes(plaintext, utf8('owned-encrypt-fail'))).toBe(true)
    expect(sameBytes(aad, aadFor())).toBe(true)
  })

  it('wipes already-acquired copies on a partial-copy failure and rethrows the unknown error', async () => {
    const keyset = aesKeyset(RAW_KEY)
    const aad = aadFor()
    const plaintext = utf8('partial-copy-failure')
    const capture = captureOwnedSlices(plaintext, aad, RAW_KEY, { throwOnAad: true })
    await expect(encryptArtifact(keyset, aad, plaintext, 'PHOTO')).rejects.toThrow('partial-copy-failure')
    expect(allZero(capture.ownedPlain)).toBe(true)
    expect(allZero(capture.ownedKey)).toBe(true)
    expect(capture.ownedAad).toBeUndefined()
    expect(sameBytes(plaintext, utf8('partial-copy-failure'))).toBe(true)
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
