import { describe, expect, it } from 'vitest'
import vector from '../../protocol/fixtures/recipient-envelope-v1.json'
import { openRecipientEnvelope } from '../src/crypto/hpke'
import { hexToBytes } from '../src/protocol/bytes'
import { ProtocolError } from '../src/protocol/errors'
import { keysetFromJson } from './keysetJson'

const info = hexToBytes(vector.expected_context_info_hex)
const ciphertext = hexToBytes(vector.golden_ciphertext_hex)
const privateKeyset = keysetFromJson(vector.fixed_private_keyset_json)

describe('recipient envelope golden vector', () => {
  it('opens the tink-python ciphertext committed for Android', async () => {
    const plain = await openRecipientEnvelope(privateKeyset, info, ciphertext)
    expect(new TextDecoder().decode(plain)).toBe(vector.envelope_plaintext_ascii)
  })

  it('fails closed on tamper, truncation, a bad prefix, and the wrong key id', async () => {
    const flipped = ciphertext.slice()
    flipped[flipped.length - 1] ^= 1
    await expect(openRecipientEnvelope(privateKeyset, info, flipped)).rejects.toBeInstanceOf(ProtocolError)
    await expect(openRecipientEnvelope(privateKeyset, info, ciphertext.slice(0, 40))).rejects.toBeInstanceOf(ProtocolError)
    const raw = ciphertext.slice()
    raw[0] = 0
    await expect(openRecipientEnvelope(privateKeyset, info, raw)).rejects.toBeInstanceOf(ProtocolError)
    const wrongId = ciphertext.slice()
    wrongId[4] ^= 1
    await expect(openRecipientEnvelope(privateKeyset, info, wrongId)).rejects.toBeInstanceOf(ProtocolError)
    const wrongInfo = info.slice()
    wrongInfo[wrongInfo.length - 1] ^= 1
    await expect(openRecipientEnvelope(privateKeyset, wrongInfo, ciphertext)).rejects.toBeInstanceOf(ProtocolError)
  })
})
