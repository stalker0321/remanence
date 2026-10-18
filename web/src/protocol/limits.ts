import fixture from '../../../protocol/fixtures/limits-v1.json'

/**
 * protocol/fixtures/limits-v1.json. Ciphertext caps live in `limits`; the
 * normalized-photo source caps live in `normalizedPhoto`. Unit tests lock
 * every value below to the fixture file.
 */
export const limits = {
  artifactOverhead: 33,
  photoCiphertext: 8_388_641,
  recognitionCiphertext: 1_048_576,
  contentCiphertext: 65_536,
  recipientEnvelopeCiphertext: 16_384,
} as const

export const normalizedPhoto = {
  mediaType: 'image/jpeg',
  maxLongEdgePx: 2560,
  maxPlaintextBytes: 8388608,
} as const

export function protocolLimitsMatchFixture(): boolean {
  return (
    fixture.artifact_aead_overhead_bytes === limits.artifactOverhead &&
    fixture.encrypted_photo_max_ciphertext_bytes === limits.photoCiphertext &&
    fixture.recognition_manifest_max_ciphertext_bytes === limits.recognitionCiphertext &&
    fixture.content_manifest_max_ciphertext_bytes === limits.contentCiphertext &&
    fixture.recipient_envelope_max_ciphertext_bytes === limits.recipientEnvelopeCiphertext &&
    fixture.normalized_photo_media_type === normalizedPhoto.mediaType &&
    fixture.normalized_photo_max_long_edge_px === normalizedPhoto.maxLongEdgePx &&
    fixture.normalized_photo_max_plaintext_bytes === normalizedPhoto.maxPlaintextBytes &&
    fixture.photo_count_min === 3 &&
    fixture.photo_count_max === 5
  )
}
