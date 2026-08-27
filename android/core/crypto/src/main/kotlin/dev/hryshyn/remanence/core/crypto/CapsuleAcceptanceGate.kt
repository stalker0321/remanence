package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.protocol.v1.PublishStatement

/** One delivered blob with its transport identity as fetched from storage. */
data class DeliveredBlob(
    val blobId: BlobId,
    val ciphertextSize: Long,
    val ciphertextSha256: ByteArray,
)

/**
 * One delivered artifact ciphertext as held by the storage transport. The
 * transport identity (size, SHA-256) is always derived from the actual
 * bytes; no caller-supplied size or digest is trusted. Used by the
 * full-material stage of [DeliveredBlobBindingVerifier] (M2-P12a).
 */
data class DeliveredCiphertext(
    val blobId: BlobId,
    val ciphertext: ByteArray,
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
    /**
     * Control/index acceptance only: the signed statement does not carry
     * exactly one RECOGNITION_MANIFEST binding that matches the delivered
     * recognition blob identity. The full [CapsuleAcceptanceGate] does not
     * emit this reason.
     */
    RECOGNITION_BINDING_INVALID,
    /**
     * Control/index acceptance only: the capsule keyset from the verified
     * envelope, the AEAD primitive, or the recognition ciphertext failed
     * integrity verification. The full [CapsuleAcceptanceGate] does not
     * emit this reason.
     */
    RECOGNITION_AEAD_INVALID,
    /**
     * Control/index acceptance only: the decrypted recognition manifest
     * did not agree with the signed capsule on identity or had empty
     * front/back fingerprints. The full [CapsuleAcceptanceGate] does not
     * emit this reason.
     */
    RECOGNITION_PAYLOAD_INVALID,
    /**
     * Presentation acceptance only: parsing the capsule keyset from the
     * verified envelope, decrypting the content manifest, or
     * structurally validating the parsed manifest (including the inner
     * capsule id binding) failed. The full [CapsuleAcceptanceGate] and
     * the control/index gate do not emit this reason.
     */
    CONTENT_AEAD_INVALID,
    /**
     * Presentation acceptance only: the decrypted content manifest's
     * photo descriptors did not exactly match the signed PHOTO
     * bindings by blob id and ordinal. The full [CapsuleAcceptanceGate]
     * and the control/index gate do not emit this reason.
     */
    CONTENT_DESCRIPTORS_MISMATCH,
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
 *
 * M2-P10 composition: the gate composes [CanonicalControlVerifier] (the
 * canonical, signature, ID-agreement, statement-hash, and layout stages) and
 * [DeliveredBlobBindingVerifier] (the exact full-material blob-binding
 * stage). Both are package-internal; neither decrypts artifacts.
 */
class CapsuleAcceptanceGate(
    signer: PublishStatementSigner = PublishStatementSigner(),
    capsuleKeysetParser: CapsuleKeysetParser = CapsuleKeysetParser(),
) {

    private val controlVerifier: CanonicalControlVerifier = CanonicalControlVerifier(
        signer = signer,
        capsuleKeysetParser = capsuleKeysetParser,
    )

    private val blobBindingVerifier: DeliveredBlobBindingVerifier = DeliveredBlobBindingVerifier()

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
        if (!blobBindingVerifier.matches(verified.statement.artifactsList, input.deliveredBlobs)) {
            return CapsuleAcceptanceResult.Rejected(RejectionReason.BLOB_SUBSTITUTION)
        }
        return CapsuleAcceptanceResult.Accepted(verified.statement)
    }
}
