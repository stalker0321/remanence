package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.protocol.v1.ArtifactBinding
import java.security.MessageDigest

/**
 * M2-P10 full-material stage: pins the exact duplicate/missing/extra/size/
 * SHA-256 check that [CapsuleAcceptanceGate] already applied to its
 * delivered-blob list. Verifies that every binding declared by the
 * canonical, signed statement exists once in the delivered set, with the
 * same blob ID, ciphertext byte length, and SHA-256 transport hash.
 *
 * The check is byte-for-byte equivalent to the previous in-line gate step:
 * the order of comparisons and the [RejectionReason.BLOB_SUBSTITUTION]
 * outcome it produces are pinned by the existing test matrix.
 */
internal class DeliveredBlobBindingVerifier {

    fun matches(
        bindings: List<ArtifactBinding>,
        delivered: List<DeliveredBlob>,
    ): Boolean = blobsMatch(bindings, delivered)

    private fun blobsMatch(bindings: List<ArtifactBinding>, delivered: List<DeliveredBlob>): Boolean {
        if (bindings.size != delivered.size) return false
        val byId = delivered.groupBy { it.blobId.toProtoBytes() }
        if (byId.any { it.value.size != 1 }) return false
        return bindings.all { binding ->
            val blob = byId[binding.blobId]?.single() ?: return@all false
            blob.ciphertextSize == binding.ciphertextSize &&
                blob.ciphertextSha256.size == SHA256_BYTES &&
                MessageDigest.isEqual(blob.ciphertextSha256, binding.ciphertextSha256.toByteArray())
        }
    }

    private companion object {
        const val SHA256_BYTES = 32
    }
}
