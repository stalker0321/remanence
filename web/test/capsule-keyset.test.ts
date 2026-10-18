import { afterEach, describe, expect, it, vi } from 'vitest'
import * as capsuleKeysetModule from '../src/crypto/capsuleKeyset'
import { generateCapsuleKeyset } from '../src/crypto/capsuleKeyset'
import { decryptArtifact } from '../src/crypto/artifact'
import { encryptArtifact } from '../src/crypto/artifactEncrypt'
import { parseAes256GcmKeyset } from '../src/crypto/tink'
import { readUint32, utf8 } from '../src/protocol/bytes'
import { artifactAad } from '../src/protocol/context'
import { fieldBytes, fieldNumber, readMessage } from '../src/protocol/proto'

const CAPSULE = '00010203-0405-0607-0809-0a0b0c0d0e0f'
const BLOB = '00000000-0000-0000-0000-0000000000e5'
const SENDER = '10111213-1415-1617-1819-1a1b1c1d1e1f'
const RECIPIENT = '20212223-2425-2627-2829-2a2b2c2d2e2f'

function aadFor(): Uint8Array {
  return artifactAad({
    capsuleId: CAPSULE,
    blobId: BLOB,
    kind: 'PHOTO',
    ordinal: 2,
    senderUserId: SENDER,
    recipientUserId: RECIPIENT,
  })
}

function sameBytes(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index])
}

interface ExplicitKeyset {
  primary: number
  keyId: number
  status: number
  prefix: number
  url: string
  material: number
  version: number
  raw: Uint8Array
}

/** Explicit proto read via the existing bounded reader (rejects unknown/dup fields). */
function explicitRead(keyset: Uint8Array): ExplicitKeyset {
  const set = readMessage(keyset, [1, 2])
  const primary = fieldNumber(set, 1)
  const key = readMessage(fieldBytes(set, 2), [1, 2, 3, 4])
  const keyId = fieldNumber(key, 3)
  const status = fieldNumber(key, 2)
  const prefix = fieldNumber(key, 4)
  const data = readMessage(fieldBytes(key, 1), [1, 2, 3])
  const url = new TextDecoder('utf-8', { fatal: true }).decode(fieldBytes(data, 1))
  const material = fieldNumber(data, 3)
  const aes = readMessage(fieldBytes(data, 2), [1, 2])
  const version = fieldNumber(aes, 1, 0)
  const raw = fieldBytes(aes, 2)
  return { primary, keyId, status, prefix, url, material, version, raw }
}

function installCsprng(idDraws: number[][], keyByte = 0x5a): { rawBuffers: Uint8Array[] } {
  let index = 0
  const rawBuffers: Uint8Array[] = []
  vi.spyOn(crypto, 'getRandomValues').mockImplementation(((array: Uint8Array) => {
    if (array.length === 32) {
      array.fill(keyByte)
      rawBuffers.push(array)
      return array
    }
    if (array.length === 4) {
      const draw = idDraws[index++]
      if (!draw) throw new Error('unexpected extra key-id draw')
      array.set(draw)
      return array
    }
    if (array.length === 12) {
      // IV requested by encryptArtifact during this test.
      array.fill(0x33)
      return array
    }
    throw new Error(`unexpected RNG length ${array.length}`)
  }) as unknown as typeof crypto.getRandomValues)
  return { rawBuffers }
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('capsule keyset generator', () => {
  it('exports only the production generator (no test hook/observer)', () => {
    expect(Object.keys(capsuleKeysetModule)).toEqual(['generateCapsuleKeyset'])
    expect('capsuleKeysetTestPort' in capsuleKeysetModule).toBe(false)
  })

  it('emits the exact single AES256_GCM/TINK/ENABLED keyset shape', () => {
    const keyset = generateCapsuleKeyset()
    const read = explicitRead(keyset)
    expect(read.primary).toBe(read.keyId)
    expect(read.keyId).not.toBe(0)
    expect(read.keyId).toBeGreaterThanOrEqual(1)
    expect(read.keyId).toBeLessThanOrEqual(0xffffffff)
    expect(read.status).toBe(1)
    expect(read.prefix).toBe(1)
    expect(read.url).toBe('type.googleapis.com/google.crypto.tink.AesGcmKey')
    expect(read.material).toBe(1)
    expect(read.version).toBe(0)
    expect(read.raw.length).toBe(32)
    const parsed = parseAes256GcmKeyset(keyset)
    expect(parsed.keyId).toBe(read.keyId)
    expect(parsed.key.length).toBe(32)
    expect(sameBytes(parsed.key, read.raw)).toBe(true)
    // The returned caller-owned secret is not wiped and remains usable.
    expect(keyset.some((value) => value !== 0)).toBe(true)
    keyset.fill(0) // test-owned cleanup
  })

  it('returns fresh independent key bytes and ids on every call', () => {
    const first = generateCapsuleKeyset()
    const second = generateCapsuleKeyset()
    const readFirst = explicitRead(first)
    const readSecond = explicitRead(second)
    expect(sameBytes(first, second)).toBe(false)
    expect(sameBytes(readFirst.raw, readSecond.raw)).toBe(false)
    expect(readFirst.keyId).not.toBe(readSecond.keyId)
    first.fill(0)
    second.fill(0)
  })

  it('generated keyset encrypts and the existing decrypt recovers the plaintext', async () => {
    const keyset = generateCapsuleKeyset()
    const aad = aadFor()
    const plaintext = utf8('generated keyset round-trip')
    const ciphertext = await encryptArtifact(keyset, aad, plaintext, 'PHOTO')
    expect(ciphertext.length).toBe(plaintext.length + 33)
    expect(sameBytes(await decryptArtifact(keyset, aad, ciphertext, 'PHOTO'), plaintext)).toBe(true)
    keyset.fill(0)
  })

  it('redraws only a zero key id and preserves the full uint32 domain including 0x80000000/0xffffffff', async () => {
    {
      installCsprng([[0, 0, 0, 0], [0x12, 0x34, 0x56, 0x78]])
      const keyset = generateCapsuleKeyset()
      expect(parseAes256GcmKeyset(keyset).keyId).toBe(0x12345678)
      keyset.fill(0)
      vi.restoreAllMocks()
    }
    for (const expected of [0x80000000, 0xffffffff]) {
      const bytes = [(expected >>> 24) & 255, (expected >>> 16) & 255, (expected >>> 8) & 255, expected & 255]
      installCsprng([bytes])
      const keyset = generateCapsuleKeyset()
      expect(parseAes256GcmKeyset(keyset).keyId).toBe(expected)
      const aad = aadFor()
      const plaintext = utf8('id-boundary')
      const ciphertext = await encryptArtifact(keyset, aad, plaintext, 'PHOTO')
      // TINK big-endian prefix keeps the full uint32 (not a signed 31-bit value).
      expect(readUint32(ciphertext, 1)).toBe(expected)
      expect(sameBytes(await decryptArtifact(keyset, aad, ciphertext, 'PHOTO'), plaintext)).toBe(true)
      keyset.fill(0)
      vi.restoreAllMocks()
    }
  })

  it('copies the raw key exactly once into the final keyset buffer (no secret wrapper temporaries)', () => {
    let rawCaptured: Uint8Array | null = null
    const realRandom = crypto.getRandomValues.bind(crypto) as (array: Uint8Array) => Uint8Array
    vi.spyOn(crypto, 'getRandomValues').mockImplementation(((array: Uint8Array) => {
      const out = realRandom(array)
      if (array.length === 32) rawCaptured = out
      return out
    }) as unknown as typeof crypto.getRandomValues)

    // Observe every copy of the raw secret BEFORE cleanup. By construction the
    // only `set(rawKey, ...)` is the single copy into the final output buffer —
    // there are no `protoBytes`/`concat` wrapper temporaries holding the key.
    const copies: Array<{ target: Uint8Array; source: Uint8Array }> = []
    const realSet = Uint8Array.prototype.set
    vi.spyOn(Uint8Array.prototype, 'set').mockImplementation((function (this: Uint8Array, source: ArrayLike<number>, offset?: number) {
      if (source instanceof Uint8Array && rawCaptured !== null && sameBytes(source, rawCaptured)) {
        copies.push({ target: this, source })
      }
      return realSet.call(this, source, offset ?? 0)
    }) as unknown as typeof Uint8Array.prototype.set)

    const keyset = generateCapsuleKeyset()
    const raw = rawCaptured as Uint8Array | null
    expect(raw).not.toBeNull()
    expect(raw!.every((value) => value === 0)).toBe(true) // raw wiped in finally
    expect(copies.length).toBe(1) // raw key copied exactly once
    expect(copies[0]!.target).toBe(keyset) // ... straight into the returned final buffer
    // Returned serialized keyset is NOT wiped and remains usable.
    expect(keyset.some((value) => value !== 0)).toBe(true)
    expect(parseAes256GcmKeyset(keyset).key.length).toBe(32)
    keyset.fill(0) // test-owned cleanup
  })

  it('wipes the preallocated raw key when the very first CSPRNG call fills then throws', () => {
    let supplied: Uint8Array | null = null
    vi.spyOn(crypto, 'getRandomValues').mockImplementation(((array: Uint8Array) => {
      supplied = array
      array.fill(0x5a, 0, 8) // partial fill before failure
      throw new Error('csprng-partial-fill')
    }) as unknown as typeof crypto.getRandomValues)
    expect(() => generateCapsuleKeyset()).toThrow('csprng-partial-fill')
    const raw = supplied as Uint8Array | null
    expect(raw).not.toBeNull()
    expect(raw!.some((value) => value !== 0)).toBe(false) // finally wiped the tracked buffer
  })

  it('rethrows a key-id CSPRNG failure after the raw key exists, wiping both owned buffers', () => {
    let rawSupplied: Uint8Array | null = null
    let idSupplied: Uint8Array | null = null
    let calls = 0
    vi.spyOn(crypto, 'getRandomValues').mockImplementation(((array: Uint8Array) => {
      calls += 1
      if (array.length === 32) {
        rawSupplied = array
        array.fill(0x5a)
        return array
      }
      if (array.length === 4) {
        idSupplied = array
        array.fill(0x11, 0, 2)
        throw new Error('csprng-failure')
      }
      throw new Error(`unexpected RNG length ${array.length}`)
    }) as unknown as typeof crypto.getRandomValues)
    expect(() => generateCapsuleKeyset()).toThrow('csprng-failure')
    expect(calls).toBe(2)
    expect((rawSupplied as unknown as Uint8Array).every((value) => value === 0)).toBe(true)
    expect((idSupplied as unknown as Uint8Array).every((value) => value === 0)).toBe(true)
  })

  it('wipes the raw key and the partial output if the final output write fails', () => {
    let rawSupplied: Uint8Array | null = null
    const realRandom = crypto.getRandomValues.bind(crypto) as (array: Uint8Array) => Uint8Array
    vi.spyOn(crypto, 'getRandomValues').mockImplementation(((array: Uint8Array) => {
      const out = realRandom(array)
      if (array.length === 32) rawSupplied = out
      return out
    }) as unknown as typeof crypto.getRandomValues)
    let partial: Uint8Array | null = null
    const realSet = Uint8Array.prototype.set
    vi.spyOn(Uint8Array.prototype, 'set').mockImplementation((function (this: Uint8Array, source: ArrayLike<number>, offset?: number) {
      if (source === rawSupplied) {
        partial = this
        throw new Error('serialization-failure')
      }
      return realSet.call(this, source, offset ?? 0)
    }) as unknown as typeof Uint8Array.prototype.set)
    expect(() => generateCapsuleKeyset()).toThrow('serialization-failure')
    const raw = rawSupplied as Uint8Array | null
    expect(raw).not.toBeNull()
    expect(raw!.every((value) => value === 0)).toBe(true)
    expect(partial).not.toBeNull()
    expect((partial as unknown as Uint8Array).every((value) => value === 0)).toBe(true)
  })
})
