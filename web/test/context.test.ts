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
