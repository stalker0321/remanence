import { describe, expect, it } from 'vitest'
import vector from '../../protocol/fixtures/publish-signature-v1.json'
import { signPublishStatement, verifyPublishStatement } from '../src/crypto/publish'
import { bytesToHex, hexToBytes } from '../src/protocol/bytes'
import { ProtocolError } from '../src/protocol/errors'
import { keysetFromJson } from './keysetJson'

const statement = hexToBytes(vector.expected_deterministic_hex)
const signature = hexToBytes(vector.expected_signature_hex)
const privateKeyset = keysetFromJson(vector.fixed_private_keyset_json)
const publicKeyset = keysetFromJson(vector.fixed_public_keyset_json)

describe('publish signature golden vector', () => {
  it('verifies and reproduces the tink Ed25519 signature', async () => {
    await verifyPublishStatement(publicKeyset, statement, signature)
    const signed = await signPublishStatement(privateKeyset, statement)
    expect(bytesToHex(signed)).toBe(vector.expected_signature_hex)
  })

  it('rejects a flipped signature, a short signature, and a flipped statement', async () => {
    const flipped = signature.slice()
    flipped[flipped.length - 1] ^= 1
    await expect(verifyPublishStatement(publicKeyset, statement, flipped)).rejects.toBeInstanceOf(ProtocolError)
    await expect(verifyPublishStatement(publicKeyset, statement, signature.slice(0, 68))).rejects.toBeInstanceOf(ProtocolError)
    const changed = statement.slice()
    changed[0] ^= 1
    await expect(verifyPublishStatement(publicKeyset, changed, signature)).rejects.toBeInstanceOf(ProtocolError)
  })
})
