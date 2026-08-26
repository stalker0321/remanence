package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.model.ArtifactAadInput
import dev.hryshyn.remanence.core.model.CryptoContextEncoder

/**
 * Encrypts/decrypts one capsule artifact under the capsule keyset with the
 * canonical versioned AAD (docs/security.md section 6.3). The full AAD
 * context is rebuilt from typed inputs before any primitive runs; any field
 * mismatch on decryption fails closed inside the AEAD.
 */
class CapsuleArtifactCryptor {

    fun encrypt(
        capsuleKeyset: KeysetHandle,
        context: ArtifactAadInput,
        plaintext: ByteArray,
    ): ByteArray = primitive(capsuleKeyset).encrypt(plaintext, associatedData(context))

    fun decrypt(
        capsuleKeyset: KeysetHandle,
        context: ArtifactAadInput,
        ciphertext: ByteArray,
    ): ByteArray = primitive(capsuleKeyset).decrypt(ciphertext, associatedData(context))

    private fun associatedData(context: ArtifactAadInput): ByteArray =
        CryptoContextEncoder.artifactAad(context).toByteArray()

    private fun primitive(capsuleKeyset: KeysetHandle): Aead =
        capsuleKeyset.getPrimitive(Aead::class.java)
}
