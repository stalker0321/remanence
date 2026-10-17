/** protocol/fixtures/limits-v1.json. Ciphertext caps, not plaintext caps. */
export const limits = {
  artifactOverhead: 33,
  photoCiphertext: 8_388_641,
  recognitionCiphertext: 1_048_576,
  contentCiphertext: 65_536,
  recipientEnvelopeCiphertext: 16_384,
} as const
