package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.MessageLite
import dev.hryshyn.remanence.core.model.ArtifactLayoutValidation
import dev.hryshyn.remanence.core.model.ArtifactLayoutValidator
import dev.hryshyn.remanence.core.model.ArtifactSlot
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CanonicalArtifactOrder
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.protocol.v1.ArtifactBinding
import dev.hryshyn.remanence.protocol.v1.ArtifactKind
import dev.hryshyn.remanence.protocol.v1.PublishStatement
import dev.hryshyn.remanence.protocol.v1.RecipientEnvelopePlaintext
import java.security.GeneralSecurityException
import java.security.MessageDigest

/**
 * Bundle of identifiers the verifier compares parsed payloads against.
 * Mirrors the four identity fields from [CapsuleAcceptanceInput] needed for
 * the canonical, signature, ID-agreement, and statement-hash stages of
 * [CapsuleAcceptanceGate] without the delivered-blob list.
 */
internal data class CanonicalControlInput(
    val expectedCapsuleId: CapsuleId,
    val authenticatedUserId: UserId,
    val senderVerifyingKeyset: KeysetHandle,
    val expectedSenderKeyBundleId: KeyBundleId,
    val envelopePlaintextBytes: ByteArray,
    val statementBytes: ByteArray,
    val signature: ByteArray,
)

/**
 * Immutable carrier returned on success: the already-parsed statement and
 * envelope that the gate has agreed are canonical, signed, ID-consistent, and
 * bound to the expected sender. The byte buffers of the keyset, signature,
 * and envelope plaintext are not exposed beyond what the existing gate
 * already holds.
 */
internal data class VerifiedCanonicalControl(
    val statement: PublishStatement,
    val envelope: RecipientEnvelopePlaintext,
)

internal sealed interface CanonicalControlResult {
    data class Verified(val control: VerifiedCanonicalControl) : CanonicalControlResult
    data class Rejected(val reason: RejectionReason) : CanonicalControlResult
}

/**
 * M2-P10 control core. Verifies the exact v1 capsule keyset, canonical
 * deterministic protobuf bytes, authoritative sender signature, every
 * ID/key agreement, the statement SHA-256 binding, and the artifact layout
 * BEFORE any blob binding or artifact decrypt. The byte ordering and
 * [RejectionReason] mapping are pinned by [CapsuleAcceptanceGateTest] and
 * MUST stay byte-for-byte equivalent to the previous in-line checks.
 */
internal class CanonicalControlVerifier(
    private val signer: PublishStatementSigner = PublishStatementSigner(),
    private val capsuleKeysetParser: CapsuleKeysetParser = CapsuleKeysetParser(),
) {

    fun verify(input: CanonicalControlInput): CanonicalControlResult {
        val envelope = parseEnvelope(input.envelopePlaintextBytes)
            ?: return CanonicalControlResult.Rejected(RejectionReason.MALFORMED_ENVELOPE)
        if (!isCanonical(envelope, input.envelopePlaintextBytes)) {
            return CanonicalControlResult.Rejected(RejectionReason.NON_CANONICAL_BYTES)
        }
        if (!isExactProtocolV1CapsuleKeyset(envelope.capsuleAeadKeyset.toByteArray())) {
            return CanonicalControlResult.Rejected(RejectionReason.MALFORMED_CAPSULE_KEYSET)
        }

        val statement = parseStatement(input.statementBytes)
            ?: return CanonicalControlResult.Rejected(RejectionReason.MALFORMED_STATEMENT)
        if (!isCanonical(statement, input.statementBytes)) {
            return CanonicalControlResult.Rejected(RejectionReason.NON_CANONICAL_BYTES)
        }

        try {
            signer.verify(
                input.senderVerifyingKeyset,
                SignedPublishStatement(input.statementBytes, input.signature),
            )
        } catch (_: Exception) {
            return CanonicalControlResult.Rejected(RejectionReason.SIGNATURE_INVALID)
        }

        if (!idsAgree(input, envelope, statement)) {
            return CanonicalControlResult.Rejected(RejectionReason.ID_MISMATCH)
        }
        if (!MessageDigest.isEqual(
                sha256(input.statementBytes),
                envelope.publishStatementSha256.toByteArray(),
            )
        ) {
            return CanonicalControlResult.Rejected(RejectionReason.STATEMENT_HASH_MISMATCH)
        }

        val slots = bindingSlots(statement.artifactsList)
            ?: return CanonicalControlResult.Rejected(RejectionReason.LAYOUT_INVALID)
        if (ArtifactLayoutValidator.validate(slots) !is ArtifactLayoutValidation.Valid) {
            return CanonicalControlResult.Rejected(RejectionReason.LAYOUT_INVALID)
        }
        if (!CanonicalArtifactOrder.isCanonical(slots)) {
            return CanonicalControlResult.Rejected(RejectionReason.NON_CANONICAL_BYTES)
        }

        return CanonicalControlResult.Verified(VerifiedCanonicalControl(statement, envelope))
    }

    private fun isExactProtocolV1CapsuleKeyset(serializedKeyset: ByteArray): Boolean = try {
        capsuleKeysetParser.parseExactAes256GcmTink(serializedKeyset)
        true
    } catch (_: GeneralSecurityException) {
        false
    }

    private fun idsAgree(
        input: CanonicalControlInput,
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

    private fun bindingSlots(bindings: List<ArtifactBinding>): List<ArtifactSlot>? = try {
        bindings.map { it.toSlot() }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun ArtifactBinding.toSlot(): ArtifactSlot = ArtifactSlot(
        blobId = BlobId.fromProtoBytes(blobId),
        kind = when (kind) {
            ArtifactKind.RECOGNITION_MANIFEST -> CapsuleArtifactKind.RECOGNITION_MANIFEST
            ArtifactKind.CONTENT_MANIFEST -> CapsuleArtifactKind.CONTENT_MANIFEST
            ArtifactKind.PHOTO -> CapsuleArtifactKind.PHOTO
            else -> throw IllegalArgumentException("unknown artifact kind")
        },
        ordinal = ordinal,
    )

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
