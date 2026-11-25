import { describe, expect, it } from 'vitest'
import vector from '../../protocol/fixtures/crypto-context-v1.json'
import { bytesToHex } from '../src/protocol/bytes'
import { artifactAad, recipientEnvelopeInfo } from '../src/protocol/context'
import { ProtocolError } from '../src/protocol/errors'

const shared = {
  capsuleId: vector.capsule_id,
  senderUserId: vector.sender_user_id,
  recipientUserId: vector.recipient_user_id,
}

describe('crypto context fixtures', () => {
  it('matches the committed artifact AAD and envelope info bytes', () => {
    const aad = artifactAad({
      ...shared,
      blobId: vector.blob_id,
      kind: 'PHOTO',
      ordinal: vector.ordinal,
    })
    const info = recipientEnvelopeInfo({
      ...shared,
      recipientKeyBundleId: vector.recipient_key_bundle_id,
    })
    expect(bytesToHex(aad)).toBe(vector.expected_artifact_aad_hex)
    expect(bytesToHex(info)).toBe(vector.expected_envelope_info_hex)
  })

  it('leaves a zero photo ordinal out, as protobuf (Android, the server) does', () => {
    // Python protobuf: ArtifactAadContext(..., artifact_kind=PHOTO, ordinal=0) for the fixture ids.
    const protobuf = '706f73746d61726b2f61727469666163742f76310008011210000102030405060708090a0b0c0d0e0f1a10000000000000000000000000000000e5'
      + '20033210101112131415161718191a1b1c1d1e1f3a10202122232425262728292a2b2c2d2e2f'
    const input = { ...shared, blobId: vector.blob_id, kind: 'PHOTO' as const, ordinal: 0 }
    expect(bytesToHex(artifactAad(input))).toBe(protobuf)
    // Earlier web builds wrote the zero; their photo 0 still opens with it.
    expect(bytesToHex(artifactAad({ ...input, legacyZeroOrdinal: true }))).toBe(protobuf.replace('20033210', '200328003210'))
  })

  it('rejects ordinals outside the v1 kind rules', () => {
    const base = {
      ...shared,
      blobId: vector.blob_id,
      kind: 'PHOTO' as const,
      ordinal: 5,
    }
    expect(() => artifactAad(base)).toThrow(ProtocolError)
    expect(() => artifactAad({ ...base, kind: 'RECOGNITION_MANIFEST', ordinal: 0 })).toThrow(ProtocolError)
    expect(bytesToHex(artifactAad({ ...base, kind: 'CONTENT_MANIFEST', ordinal: -1 })).length).toBeGreaterThan(0)
  })

  it('rejects a non-canonical account id', () => {
    expect(() => recipientEnvelopeInfo({
      ...shared,
      senderUserId: 'AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE',
      recipientKeyBundleId: vector.recipient_key_bundle_id,
    })).toThrow(ProtocolError)
  })
})
