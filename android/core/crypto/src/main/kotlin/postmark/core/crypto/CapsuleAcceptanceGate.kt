package postmark.core.crypto

import app.postmark.protocol.v1.ArtifactBinding
import app.postmark.protocol.v1.PublishStatement
import app.postmark.protocol.v1.RecipientEnvelopePlaintext
import com.google.crypto.tink.KeysetHandle
import com.google.protobuf.ByteString
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.MessageLite
import java.security.GeneralSecurityException
import java.security.MessageDigest
import postmark.core.model.ArtifactLayoutValidator
import postmark.core.model.ArtifactSlot
import postmark.core.model.BlobId
import postmark.core.model.CapsuleArtifactKind
import postmark.core.model.CapsuleId
import postmark.core.model.KeyBundleId
import postmark.core.model.ProtocolV1Limits
import postmark.core.model.UserId

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

    fun verify(input: CapsuleAcceptanceInput): CapsuleAcceptanceResult {
        val envelope = parseEnvelope(input.envelopePlaintextBytes)
            ?: return CapsuleAcceptanceResult.Rejected(RejectionReason.MALFORMED_ENVELOPE)
        if (!isCanonical(envelope, input.envelopePlaintextBytes)) {
            return CapsuleAcceptanceResult.Rejected(RejectionReason.NON_CANONICAL_BYTES)
        }
        if (!isExactProtocolV1CapsuleKeyset(envelope.capsuleAeadKeyset)) {
            return CapsuleAcceptanceResult.Rejected(RejectionReason.MALFORMED_CAPSULE_KEYSET)
        }

        val statement = parseStatement(input.statementBytes)
            ?: return CapsuleAcceptanceResult.Rejected(RejectionReason.MALFORMED_STATEMENT)
        if (!isCanonical(statement, input.statementBytes)) {
            return CapsuleAcceptanceResult.Rejected(RejectionReason.NON_CANONICAL_BYTES)
        }

        try {
            signer.verify(
                input.senderVerifyingKeyset,
                SignedPublishStatement(input.statementBytes, input.signature),
            )
        } catch (_: Exception) {
            // Includes the ADR-007 structural guard for short/foreign-framed
            // signatures; verification failures never escape this gate.
            return CapsuleAcceptanceResult.Rejected(RejectionReason.SIGNATURE_INVALID)
        }

        if (!idsAgree(input, envelope, statement)) {
            return CapsuleAcceptanceResult.Rejected(RejectionReason.ID_MISMATCH)
        }
        if (!MessageDigest.isEqual(
                sha256(input.statementBytes),
                envelope.publishStatementSha256.toByteArray(),
            )
        ) {
            return CapsuleAcceptanceResult.Rejected(RejectionReason.STATEMENT_HASH_MISMATCH)
        }

        val slots = bindingSlots(statement.artifactsList)
            ?: return CapsuleAcceptanceResult.Rejected(RejectionReason.LAYOUT_INVALID)
        if (ArtifactLayoutValidator.validate(slots) !is postmark.core.model.ArtifactLayoutValidation.Valid) {
            return CapsuleAcceptanceResult.Rejected(RejectionReason.LAYOUT_INVALID)
        }
        if (!blobsMatch(statement.artifactsList, input.deliveredBlobs)) {
            return CapsuleAcceptanceResult.Rejected(RejectionReason.BLOB_SUBSTITUTION)
        }

        return CapsuleAcceptanceResult.Accepted(statement)
    }

    private fun isExactProtocolV1CapsuleKeyset(serializedKeyset: ByteString): Boolean = try {
        capsuleKeysetParser.parseExactAes256GcmTink(serializedKeyset.toByteArray())
        true
    } catch (_: GeneralSecurityException) {
        false
    }

    private fun idsAgree(
        input: CapsuleAcceptanceInput,
        envelope: RecipientEnvelopePlaintext,
        statement: PublishStatement,
    ): Boolean {
        val expectedCapsule = input.expectedCapsuleId.toProtoBytes()
        val expectedUser = input.authenticatedUserId.toProtoBytes()
        val expectedSenderBundle = input.expectedSenderKeyBundleId.toProtoBytes()
        return statement.capsuleId == expectedCapsule &&
            statement.recipientUserId == expectedUser &&
            statement.senderKeyBundleId == expectedSenderBundle &&
            envelope.capsuleId == expectedCapsule &&
            envelope.recipientUserId == expectedUser &&
            envelope.senderKeyBundleId == expectedSenderBundle &&
            envelope.capsuleId == statement.capsuleId &&
            envelope.senderUserId == statement.senderUserId &&
            envelope.recipientUserId == statement.recipientUserId &&
            envelope.senderKeyBundleId == statement.senderKeyBundleId &&
            envelope.recipientKeyBundleId == statement.recipientKeyBundleId &&
            statement.protocolVersion == ProtocolV1Limits.PROTOCOL_VERSION &&
            envelope.protocolVersion == ProtocolV1Limits.PROTOCOL_VERSION
    }

    private fun parseEnvelope(bytes: ByteArray): RecipientEnvelopePlaintext? =
        try {
            val parsed = RecipientEnvelopePlaintext.parseFrom(bytes)
            if (parsed.capsuleAeadKeyset.isEmpty) null else parsed
        } catch (_: Exception) {
            null
        }

    private fun parseStatement(bytes: ByteArray): PublishStatement? =
        try {
            val parsed = PublishStatement.parseFrom(bytes)
            if (parsed.artifactsCount == 0) null else parsed
        } catch (_: Exception) {
            null
        }

    private fun isCanonical(message: MessageLite, original: ByteArray): Boolean =
        original.contentEquals(canonicalBytes(message))

    private fun canonicalBytes(message: MessageLite): ByteArray {
        val bytes = ByteArray(message.serializedSize)
        val output = CodedOutputStream.newInstance(bytes)
        output.useDeterministicSerialization()
        message.writeTo(output)
        output.flush()
        output.checkNoSpaceLeft()
        return bytes
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

    /**
     * Converts signed artifact bindings into layout slots, or null when any
     * binding carries an unknown enum kind or a malformed (non-16-byte) typed
     * blob ID — both reject closed instead of throwing.
     */
    private fun bindingSlots(bindings: List<ArtifactBinding>): List<ArtifactSlot>? = try {
        bindings.map { it.toSlot() }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun ArtifactBinding.toSlot(): ArtifactSlot = ArtifactSlot(
        blobId = BlobId.fromProtoBytes(blobId),
        kind = when (kind) {
            app.postmark.protocol.v1.ArtifactKind.RECOGNITION_MANIFEST -> CapsuleArtifactKind.RECOGNITION_MANIFEST
            app.postmark.protocol.v1.ArtifactKind.CONTENT_MANIFEST -> CapsuleArtifactKind.CONTENT_MANIFEST
            app.postmark.protocol.v1.ArtifactKind.PHOTO -> CapsuleArtifactKind.PHOTO
            else -> throw IllegalArgumentException("unknown artifact kind")
        },
        ordinal = ordinal,
    )

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private companion object {
        const val SHA256_BYTES = 32
    }
}
