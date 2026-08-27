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
 * M2-P11 adds the recognition-index check [matchesRecognition]: given the
 * canonical signed statement, an explicit recognition [BlobId], and the
 * actual delivered recognition ciphertext bytes, the statement must
 * contain exactly one `RECOGNITION_MANIFEST` binding with ordinal `-1`
 * and that binding must match the supplied identity, the byte length of
 * [recognitionCiphertext], and the SHA-256 of [recognitionCiphertext]
 * (constant-time). Other content/photo bindings may be absent — they
 * are not consulted and not marked verified. The transport identity is
 * always derived from the ciphertext bytes the caller actually holds;
 * no caller-supplied size/SHA fields are trusted independently. This is
 * for control/index sync only; the full [CapsuleAcceptanceGate] still
 * demands every declared blob. Both binding methods verify signed
 * transport identity only: an exact delivered byte sequence of any
 * length passes size plus SHA-256, while AEAD validity belongs to the
 * later decrypt gate.
 *
 * M2-P12a adds the full-material check [matchesFullCoverage]: given the
 * canonical signed statement and the actual delivered ciphertext bytes
 * for every declared artifact, every signed [ArtifactBinding] must
 * exist exactly once in the delivered set, and each binding's signed
 * size and SHA-256 must equal the size and SHA-256 derived from the
 * actual bytes the caller holds. The method rejects missing, extra,
 * duplicate IDs, wrong size/hash, and substitution. No
 * caller-supplied size or digest is trusted: the bytes are the only
 * source of transport identity. Both binding methods verify signed
 * transport identity only: an exact delivered byte sequence of any
 * length passes size plus SHA-256, while AEAD validity belongs to the
 * later decrypt gate. This is the P12a prerequisite used by the future
 * presentation gate; the existing metadata-based [matches] method and
 * the current [CapsuleAcceptanceGate] behavior remain unchanged in
 * this commit.
 */
internal class DeliveredBlobBindingVerifier {

    fun matches(
        bindings: List<ArtifactBinding>,
        delivered: List<DeliveredBlob>,
    ): Boolean = blobsMatch(bindings, delivered)

    fun matchesRecognition(
        bindings: List<ArtifactBinding>,
        recognitionBlobId: BlobId,
        recognitionCiphertext: ByteArray,
    ): Boolean = recognitionBindingMatches(
        bindings,
        recognitionBlobId,
        recognitionCiphertext,
    )

    fun matchesFullCoverage(
        bindings: List<ArtifactBinding>,
        delivered: List<DeliveredCiphertext>,
    ): Boolean = fullCoverageMatches(bindings, delivered)

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
        recognitionCiphertext: ByteArray,
    ): Boolean {
        val recognitionBindings = bindings.filter { binding ->
            binding.kind == ArtifactKind.RECOGNITION_MANIFEST && binding.ordinal == RECOGNITION_ORDINAL
        }
        if (recognitionBindings.size != 1) return false
        val binding = recognitionBindings.single()
        if (binding.blobId != recognitionBlobId.toProtoBytes()) return false
        if (binding.ciphertextSize != recognitionCiphertext.size.toLong()) return false
        val actualSha = MessageDigest.getInstance("SHA-256").digest(recognitionCiphertext)
        return MessageDigest.isEqual(binding.ciphertextSha256.toByteArray(), actualSha)
    }

    private fun fullCoverageMatches(
        bindings: List<ArtifactBinding>,
        delivered: List<DeliveredCiphertext>,
    ): Boolean {
        if (bindings.size != delivered.size) return false
        val byId = delivered.groupBy { it.blobId.toProtoBytes() }
        if (byId.any { it.value.size != 1 }) return false
        if (bindings.map { it.blobId }.toSet().size != bindings.size) return false
        return bindings.all { binding ->
            val ciphertext = byId[binding.blobId]?.single()?.ciphertext ?: return@all false
            if (binding.ciphertextSize != ciphertext.size.toLong()) return@all false
            val actualSha = MessageDigest.getInstance("SHA-256").digest(ciphertext)
            MessageDigest.isEqual(binding.ciphertextSha256.toByteArray(), actualSha)
        }
    }

    private companion object {
        const val SHA256_BYTES = 32
        const val RECOGNITION_ORDINAL = -1
    }
}
