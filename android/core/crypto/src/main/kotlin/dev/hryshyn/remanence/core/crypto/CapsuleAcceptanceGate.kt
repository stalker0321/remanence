package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.protocol.v1.ArtifactBinding
import dev.hryshyn.remanence.protocol.v1.PublishStatement
import java.security.MessageDigest

/** One delivered blob with its transport identity as fetched from storage. */
data class DeliveredBlob(
    val blobId: BlobId,
    val ciphertextSize: Long,
    val ciphertextSha256: ByteArray,
)

/** Everything needed to judge a capsule before a single artifact is decrypted. */
data class CapsuleAcceptanceInput(
    val expectedCapsuleId: CapsuleId,
    val authenticatedUserId: UserId,
    val senderVerifyingKeyset: KeysetHandle,
    val expectedSenderKeyBundleId: KeyBundleId,
    val envelopePlaintextBytes: ByteArray,
    val statementBytes: ByteArray,
    val signature: ByteArray,
    val deliveredBlobs: List<DeliveredBlob>,
)

sealed interface CapsuleAcceptanceResult {
    /** Only this outcome may proceed to artifact decryption downstream. */
    data class Accepted(val statement: PublishStatement) : CapsuleAcceptanceResult

    data class Rejected(val reason: RejectionReason) : CapsuleAcceptanceResult
}

enum class RejectionReason {
    MALFORMED_ENVELOPE,
    MALFORMED_CAPSULE_KEYSET,
    MALFORMED_STATEMENT,
    NON_CANONICAL_BYTES,
    SIGNATURE_INVALID,
    ID_MISMATCH,
    STATEMENT_HASH_MISMATCH,
    LAYOUT_INVALID,
    BLOB_SUBSTITUTION,
}

/**
 * M1-C13 gate (docs/security.md sections 6.4/6.6): verifies the signature,
 * deterministic statement structure, canonical byte encoding, every ID and
 * key-ID agreement, the envelope/statement binding including the statement
 * SHA-256, the exact protocol-v1 AES256_GCM/TINK capsule keyset, artifact
 * layout, and every delivered blob size/hash BEFORE any artifact decrypt can
 * happen. The gate cannot decrypt: accepting is its only effect, and
 * [CapsuleAcceptanceResult.Accepted] is the sole pass-through.
 *
 * The gate ALWAYS returns a result — it never throws. Malformed signatures,
 * unparseable protobuf, unknown enums, malformed typed IDs, invalid layouts,
 * and unusable capsule keysets all reject closed with a reason and perform no
 * cryptographic or storage side effects.
 */
class CapsuleAcceptanceGate(
    private val signer: PublishStatementSigner = PublishStatementSigner(),
    private val capsuleKeysetParser: CapsuleKeysetParser = CapsuleKeysetParser(),
) {

    private val controlVerifier: CanonicalControlVerifier = CanonicalControlVerifier(
        signer = signer,
        capsuleKeysetParser = capsuleKeysetParser,
    )

    fun verify(input: CapsuleAcceptanceInput): CapsuleAcceptanceResult {
        val control = controlVerifier.verify(
            CanonicalControlInput(
                expectedCapsuleId = input.expectedCapsuleId,
                authenticatedUserId = input.authenticatedUserId,
                senderVerifyingKeyset = input.senderVerifyingKeyset,
                expectedSenderKeyBundleId = input.expectedSenderKeyBundleId,
                envelopePlaintextBytes = input.envelopePlaintextBytes,
                statementBytes = input.statementBytes,
                signature = input.signature,
            ),
        )
        val verified = when (control) {
            is CanonicalControlResult.Rejected ->
                return CapsuleAcceptanceResult.Rejected(control.reason)
            is CanonicalControlResult.Verified -> control.control
        }
        if (!blobsMatch(verified.statement.artifactsList, input.deliveredBlobs)) {
            return CapsuleAcceptanceResult.Rejected(RejectionReason.BLOB_SUBSTITUTION)
        }
        return CapsuleAcceptanceResult.Accepted(verified.statement)
    }

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
