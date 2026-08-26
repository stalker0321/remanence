package dev.hryshyn.remanence.core.data.fingerprints

/**
 * Minimal sealing boundary so :core:data can persist sensitive local
 * material as opaque bytes without importing any cryptography types
 * (docs/architecture.md section 4). Implementations bind every operation to
 * the provided associated data and must fail closed.
 */
interface SecretSealer {

    fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray

    fun unseal(ciphertext: ByteArray, aad: ByteArray): ByteArray
}
