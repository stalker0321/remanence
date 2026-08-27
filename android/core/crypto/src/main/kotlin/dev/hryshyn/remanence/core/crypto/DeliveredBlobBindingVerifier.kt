package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.protocol.v1.ArtifactBinding
import dev.hryshyn.remanence.protocol.v1.ArtifactKind
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
 *
 * M2-P11 adds the recognition-index check
 * [matchesRecognition]: given the canonical signed statement, an explicit
 * recognition [BlobId], and the delivered recognition ciphertext size and
 * SHA-256, the statement must contain exactly one
 * `RECOGNITION_MANIFEST` binding with ordinal `-1` and that binding must
 * match the supplied identity, byte size, and constant-time SHA-256. Other
 * content/photo bindings may be absent — they are not consulted and not
 * marked verified. This is for control/index sync only; the full
 * [CapsuleAcceptanceGate] still demands every declared blob.
 */
internal class DeliveredBlobBindingVerifier {

    fun matches(
        bindings: List<ArtifactBinding>,
        delivered: List<DeliveredBlob>,
    ): Boolean = blobsMatch(bindings, delivered)

    fun matchesRecognition(
        bindings: List<ArtifactBinding>,
        recognitionBlobId: BlobId,
        recognitionCiphertextSize: Long,
        recognitionCiphertextSha256: ByteArray,
    ): Boolean = recognitionBindingMatches(
        bindings,
        recognitionBlobId,
        recognitionCiphertextSize,
        recognitionCiphertextSha256,
    )

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

    private fun recognitionBindingMatches(
        bindings: List<ArtifactBinding>,
        recognitionBlobId: BlobId,
        recognitionCiphertextSize: Long,
        recognitionCiphertextSha256: ByteArray,
    ): Boolean {
        val recognitionBindings = bindings.filter { binding ->
            binding.kind == ArtifactKind.RECOGNITION_MANIFEST && binding.ordinal == RECOGNITION_ORDINAL
        }
        if (recognitionBindings.size != 1) return false
        val binding = recognitionBindings.single()
        if (binding.blobId != recognitionBlobId.toProtoBytes()) return false
        if (binding.ciphertextSize != recognitionCiphertextSize) return false
        if (recognitionCiphertextSha256.size != SHA256_BYTES) return false
        return MessageDigest.isEqual(
            binding.ciphertextSha256.toByteArray(),
            recognitionCiphertextSha256,
        )
    }

    private companion object {
        const val SHA256_BYTES = 32
        const val RECOGNITION_ORDINAL = -1
    }
}
