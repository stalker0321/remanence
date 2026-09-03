package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.protocol.v1.PublishStatement
import java.security.GeneralSecurityException

/**
 * Identity-bound bundle for control/index acceptance (M2-P11,
 * docs/security.md section 6.7). The control plane delivers the envelope
 * plaintext, the canonical signed statement, the authoritative sender
 * signature, and the actual recognition ciphertext. The transport
 * identity (size, SHA-256) is derived from the ciphertext bytes the
 * caller holds — the gate never trusts caller-supplied size or SHA
 * fields independently of the bytes. Content/photo blobs may be absent
 * at this stage and are not consulted.
 */
data class ControlIndexAcceptanceInput(
    val expectedCapsuleId: CapsuleId,
    val authenticatedUserId: UserId,
    val senderVerifyingKeyset: KeysetHandle,
    val expectedSenderKeyBundleId: KeyBundleId,
    val envelopePlaintextBytes: ByteArray,
    val statementBytes: ByteArray,
    val signature: ByteArray,
    val recognitionBlobId: BlobId,
    val recognitionCiphertext: ByteArray,
)

sealed interface ControlIndexAcceptanceResult {
    /**
     * Control/index acceptance succeeded: the canonical signed statement
     * was verified, its single recognition binding matched the delivered
     * recognition identity (size and SHA-256 of the actual ciphertext),
     * the capsule keyset was parsed, the recognition ciphertext was
     * decrypted and parsed, and the inner manifest agreed with the
     * signed capsule on identity and on the required FRONT fingerprint. The
     * verified statement and decrypted [RecognitionManifestContent] are
     * the only outputs; content/photo plaintext is never returned.
     */
    data class Verified(
        val statement: PublishStatement,
        val recognition: RecognitionManifestContent,
    ) : ControlIndexAcceptanceResult

    data class Rejected(val reason: RejectionReason) : ControlIndexAcceptanceResult
}

/**
 * M2-P11 control/index gate (docs/security.md section 6.7). Verifies the
 * canonical control plane, then the recognition binding against the
 * signed statement using the actual ciphertext bytes, then decrypts
 * the single delivered recognition ciphertext and parses it. The full
 * [CapsuleAcceptanceGate] is untouched and still requires every
 * declared content/photo blob before presentation.
 */
class ControlIndexAcceptanceGate(
    signer: PublishStatementSigner = PublishStatementSigner(),
    private val capsuleKeysetParser: CapsuleKeysetParser = CapsuleKeysetParser(),
) {

    private val controlVerifier: CanonicalControlVerifier = CanonicalControlVerifier(
        signer = signer,
        capsuleKeysetParser = capsuleKeysetParser,
    )

    private val blobBindingVerifier: DeliveredBlobBindingVerifier = DeliveredBlobBindingVerifier()

    private val recognitionCodec: RecognitionManifestCodec = RecognitionManifestCodec()

    fun verify(input: ControlIndexAcceptanceInput): ControlIndexAcceptanceResult {
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
                return ControlIndexAcceptanceResult.Rejected(control.reason)
            is CanonicalControlResult.Verified -> control.control
        }

        if (!blobBindingVerifier.matchesRecognition(
                bindings = verified.statement.artifactsList,
                recognitionBlobId = input.recognitionBlobId,
                recognitionCiphertext = input.recognitionCiphertext,
            )
        ) {
            return ControlIndexAcceptanceResult.Rejected(RejectionReason.RECOGNITION_BINDING_INVALID)
        }

        val keyset = try {
            capsuleKeysetParser.parseExactAes256GcmTink(
                verified.envelope.capsuleAeadKeyset.toByteArray(),
            )
        } catch (_: GeneralSecurityException) {
            return ControlIndexAcceptanceResult.Rejected(RejectionReason.RECOGNITION_AEAD_INVALID)
        }

        val routing = try {
            RecognitionManifestCodec.RoutingContext(
                capsuleId = input.expectedCapsuleId,
                blobId = input.recognitionBlobId,
                senderUserId = UserId.fromProtoBytes(verified.statement.senderUserId),
                recipientUserId = UserId.fromProtoBytes(verified.statement.recipientUserId),
            )
        } catch (_: IllegalArgumentException) {
            return ControlIndexAcceptanceResult.Rejected(RejectionReason.ID_MISMATCH)
        }

        val content = try {
            recognitionCodec.decryptAndParse(keyset, routing, input.recognitionCiphertext)
        } catch (_: RecognitionManifestPayloadException) {
            return ControlIndexAcceptanceResult.Rejected(RejectionReason.RECOGNITION_PAYLOAD_INVALID)
        } catch (_: GeneralSecurityException) {
            return ControlIndexAcceptanceResult.Rejected(RejectionReason.RECOGNITION_AEAD_INVALID)
        } catch (_: Exception) {
            return ControlIndexAcceptanceResult.Rejected(RejectionReason.RECOGNITION_AEAD_INVALID)
        }

        if (!content.capsuleIdRaw.contentEquals(verified.statement.capsuleId.toByteArray())) {
            return ControlIndexAcceptanceResult.Rejected(RejectionReason.RECOGNITION_PAYLOAD_INVALID)
        }
        if (content.frontFingerprint.isEmpty()) {
            return ControlIndexAcceptanceResult.Rejected(RejectionReason.RECOGNITION_PAYLOAD_INVALID)
        }

        return ControlIndexAcceptanceResult.Verified(verified.statement, content)
    }
}
