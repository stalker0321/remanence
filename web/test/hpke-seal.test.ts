import { afterEach, describe, expect, it, vi } from 'vitest'
import vector from '../../protocol/fixtures/recipient-envelope-v1.json'
import * as hpkeSealModule from '../src/crypto/hpkeSeal'
import { sealRecipientEnvelope } from '../src/crypto/hpkeSeal'
import { openRecipientEnvelope } from '../src/crypto/hpke'
import { hexToBytes, readUint32 } from '../src/protocol/bytes'
import { ProtocolError } from '../src/protocol/errors'
import { limits } from '../src/protocol/limits'
import { keysetFromJson } from './keysetJson'

const PUBLIC_KEYSET = keysetFromJson(vector.fixed_public_keyset_json)
const PRIVATE_KEYSET = keysetFromJson(vector.fixed_private_keyset_json)
const INFO = hexToBytes(vector.expected_context_info_hex)
const PLAINTEXT = hexToBytes(vector.envelope_plaintext_hex)
const KEY_ID = vector.signing_key_id

function sameBytes(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index])
}

interface OwnedCopy {
  from: unknown
  to: Uint8Array
}

/** TEST-ONLY spy on the existing `Uint8Array.prototype.set` copy call. */
function captureCopies(): OwnedCopy[] {
  const copies: OwnedCopy[] = []
  const realSet = Uint8Array.prototype.set
  vi.spyOn(Uint8Array.prototype, 'set').mockImplementation((function (this: Uint8Array, source: ArrayLike<number>, offset?: number) {
    copies.push({ from: source, to: this })
    return realSet.call(this, source, offset ?? 0)
  }) as unknown as typeof Uint8Array.prototype.set)
  return copies
}

function ownedCopyOf(copies: OwnedCopy[], source: unknown): Uint8Array | undefined {
  return copies.find((copy) => copy.from === source)?.to
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('recipient HPKE seal', () => {
  it('exports only the production seal API (no test hook/observer)', () => {
    expect(Object.keys(hpkeSealModule)).toEqual(['sealRecipientEnvelope'])
  })

  it('seals with the committed recipient public keyset and the existing open recovers the plaintext', async () => {
    const frame = await sealRecipientEnvelope(PUBLIC_KEYSET, INFO, PLAINTEXT)
    expect(frame.length).toBe(PLAINTEXT.length + 53)
    expect(frame[0]).toBe(1)
    expect(readUint32(frame, 1)).toBe(KEY_ID)
    expect(frame.slice(5, 37).length).toBe(32)
    expect(sameBytes(await openRecipientEnvelope(PRIVATE_KEYSET, INFO, frame), PLAINTEXT)).toBe(true)
  })

  it('seals randomized (fresh ephemeral key) but every frame opens to the same plaintext', async () => {
    const first = await sealRecipientEnvelope(PUBLIC_KEYSET, INFO, PLAINTEXT)
    const second = await sealRecipientEnvelope(PUBLIC_KEYSET, INFO, PLAINTEXT)
    expect(sameBytes(first, second)).toBe(false)
    expect(sameBytes(await openRecipientEnvelope(PRIVATE_KEYSET, INFO, first), PLAINTEXT)).toBe(true)
    expect(sameBytes(await openRecipientEnvelope(PRIVATE_KEYSET, INFO, second), PLAINTEXT)).toBe(true)
  })

  it('refuses wrong info/private key/key id/prefix/enc/tag/body/truncation/over-cap', async () => {
    const frame = await sealRecipientEnvelope(PUBLIC_KEYSET, INFO, PLAINTEXT)
    const wrongInfo = INFO.slice()
    wrongInfo[wrongInfo.length - 1] ^= 1
    await expect(openRecipientEnvelope(PRIVATE_KEYSET, wrongInfo, frame)).rejects.toBeInstanceOf(ProtocolError)
    // A public keyset is not an HPKE private keyset.
    await expect(openRecipientEnvelope(PUBLIC_KEYSET, INFO, frame)).rejects.toBeInstanceOf(ProtocolError)
    const wrongId = frame.slice()
    wrongId[4] ^= 1
    await expect(openRecipientEnvelope(PRIVATE_KEYSET, INFO, wrongId)).rejects.toMatchObject({ code: 'WRONG_KEY' })
    const badPrefix = frame.slice()
    badPrefix[0] = 0
    await expect(openRecipientEnvelope(PRIVATE_KEYSET, INFO, badPrefix)).rejects.toBeInstanceOf(ProtocolError)
    const badEnc = frame.slice()
    badEnc[10] ^= 1
    await expect(openRecipientEnvelope(PRIVATE_KEYSET, INFO, badEnc)).rejects.toBeInstanceOf(ProtocolError)
    const badTag = frame.slice()
    badTag[badTag.length - 1] ^= 1
    await expect(openRecipientEnvelope(PRIVATE_KEYSET, INFO, badTag)).rejects.toBeInstanceOf(ProtocolError)
    await expect(openRecipientEnvelope(PRIVATE_KEYSET, INFO, frame.slice(0, 40))).rejects.toBeInstanceOf(ProtocolError)
    await expect(openRecipientEnvelope(PRIVATE_KEYSET, INFO, new Uint8Array(limits.recipientEnvelopeCiphertext + 1))).rejects.toBeInstanceOf(ProtocolError)
  })

  it('refuses seal preflight failures before any import/seal/RNG effect', async () => {
    const importSpy = vi.spyOn(crypto.subtle, 'importKey')
    const encryptSpy = vi.spyOn(crypto.subtle, 'encrypt')
    const randomSpy = vi.spyOn(crypto, 'getRandomValues')
    await expect(sealRecipientEnvelope(PUBLIC_KEYSET, new Uint8Array(0), PLAINTEXT)).rejects.toMatchObject({ code: 'OVERSIZED' })
    await expect(sealRecipientEnvelope(PUBLIC_KEYSET, new Uint8Array(129), PLAINTEXT)).rejects.toMatchObject({ code: 'OVERSIZED' })
    await expect(sealRecipientEnvelope(PUBLIC_KEYSET, INFO, new Uint8Array(0))).rejects.toMatchObject({ code: 'EMPTY' })
    await expect(sealRecipientEnvelope(PUBLIC_KEYSET, INFO, new Uint8Array(16332))).rejects.toMatchObject({ code: 'OVERSIZED' })
    await expect(sealRecipientEnvelope(new Uint8Array(3), INFO, PLAINTEXT)).rejects.toBeInstanceOf(ProtocolError)
    expect(importSpy).not.toHaveBeenCalled()
    expect(encryptSpy).not.toHaveBeenCalled()
    expect(randomSpy).not.toHaveBeenCalled()
  })

  it('accepts the exact envelope plaintext cap and refuses the next byte', async () => {
    const maxPlaintext = limits.recipientEnvelopeCiphertext - 53
    const at = new Uint8Array(maxPlaintext).fill(7)
    const frame = await sealRecipientEnvelope(PUBLIC_KEYSET, INFO, at)
    expect(frame.length).toBe(limits.recipientEnvelopeCiphertext)
    expect(sameBytes(await openRecipientEnvelope(PRIVATE_KEYSET, INFO, frame), at)).toBe(true)
    await expect(sealRecipientEnvelope(PUBLIC_KEYSET, INFO, new Uint8Array(maxPlaintext + 1))).rejects.toMatchObject({ code: 'OVERSIZED' })
  })

  it('wipes owned info/plaintext copies on success and never the caller arrays', async () => {
    const copies = captureCopies()
    const info = INFO.slice()
    const plaintext = PLAINTEXT.slice()
    const frame = await sealRecipientEnvelope(PUBLIC_KEYSET, info, plaintext)
    const ownedInfo = ownedCopyOf(copies, info)
    const ownedPlaintext = ownedCopyOf(copies, plaintext)
    expect(ownedInfo).toBeDefined()
    expect(ownedPlaintext).toBeDefined()
    expect(ownedInfo!.every((value) => value === 0)).toBe(true)
    expect(ownedPlaintext!.every((value) => value === 0)).toBe(true)
    expect(sameBytes(info, INFO)).toBe(true)
    expect(sameBytes(plaintext, PLAINTEXT)).toBe(true)
    expect(sameBytes(await openRecipientEnvelope(PRIVATE_KEYSET, INFO, frame), PLAINTEXT)).toBe(true)
  })

  it('wipes owned copies on import rejection', async () => {
    const copies = captureCopies()
    const info = INFO.slice()
    const plaintext = PLAINTEXT.slice()
    vi.spyOn(crypto.subtle, 'importKey').mockRejectedValue(new DOMException('nope', 'OperationError'))
    await expect(sealRecipientEnvelope(PUBLIC_KEYSET, info, plaintext)).rejects.toBeInstanceOf(ProtocolError)
    expect(ownedCopyOf(copies, info)!.every((value) => value === 0)).toBe(true)
    expect(ownedCopyOf(copies, plaintext)!.every((value) => value === 0)).toBe(true)
    expect(sameBytes(info, INFO)).toBe(true)
    expect(sameBytes(plaintext, PLAINTEXT)).toBe(true)
  })

  it('wipes owned copies on seal rejection', async () => {
    const copies = captureCopies()
    const info = INFO.slice()
    const plaintext = PLAINTEXT.slice()
    vi.spyOn(crypto.subtle, 'encrypt').mockRejectedValue(new DOMException('nope', 'OperationError'))
    await expect(sealRecipientEnvelope(PUBLIC_KEYSET, info, plaintext)).rejects.toBeInstanceOf(ProtocolError)
    expect(ownedCopyOf(copies, info)!.every((value) => value === 0)).toBe(true)
    expect(ownedCopyOf(copies, plaintext)!.every((value) => value === 0)).toBe(true)
    expect(sameBytes(info, INFO)).toBe(true)
    expect(sameBytes(plaintext, PLAINTEXT)).toBe(true)
  })

  it('wipes already-acquired copies on a partial input-copy failure and rethrows', async () => {
    const realSet = Uint8Array.prototype.set
    const copies: OwnedCopy[] = []
    const info = INFO.slice()
    const plaintext = PLAINTEXT.slice()
    vi.spyOn(Uint8Array.prototype, 'set').mockImplementation((function (this: Uint8Array, source: ArrayLike<number>, offset?: number) {
      if (source === plaintext) throw new Error('partial-copy-failure')
      copies.push({ from: source, to: this })
      return realSet.call(this, source, offset ?? 0)
    }) as unknown as typeof Uint8Array.prototype.set)
    await expect(sealRecipientEnvelope(PUBLIC_KEYSET, info, plaintext)).rejects.toThrow('partial-copy-failure')
    expect(ownedCopyOf(copies, info)).toBeDefined()
    expect(ownedCopyOf(copies, info)!.every((value) => value === 0)).toBe(true)
    expect(sameBytes(info, INFO)).toBe(true)
    expect(sameBytes(plaintext, PLAINTEXT)).toBe(true)
  })

  it('snapshots caller inputs during a deferred import (mutation cannot change the frame)', async () => {
    const info = INFO.slice()
    const plaintext = PLAINTEXT.slice()
    const keyset = PUBLIC_KEYSET.slice()
    const realImport = crypto.subtle.importKey.bind(crypto.subtle) as (...args: unknown[]) => Promise<CryptoKey>
    vi.spyOn(crypto.subtle, 'importKey').mockImplementation(((async (...args: unknown[]) => {
      await new Promise((resolve) => setTimeout(resolve, 10))
      return realImport(...args)
    }) as unknown) as typeof crypto.subtle.importKey)
    const pending = sealRecipientEnvelope(keyset, info, plaintext)
    info[0] ^= 0xff
    plaintext[0] ^= 0xff
    keyset[10] ^= 0xff
    const frame = await pending
    expect(readUint32(frame, 1)).toBe(KEY_ID)
    expect(sameBytes(await openRecipientEnvelope(PRIVATE_KEYSET, INFO, frame), PLAINTEXT)).toBe(true)
    expect(sameBytes(info, INFO)).toBe(false)
  })
})
