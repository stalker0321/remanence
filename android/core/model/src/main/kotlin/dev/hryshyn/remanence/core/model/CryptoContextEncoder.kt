package dev.hryshyn.remanence.core.model

import dev.hryshyn.remanence.protocol.v1.ArtifactAadContext
import dev.hryshyn.remanence.protocol.v1.ArtifactKind
import dev.hryshyn.remanence.protocol.v1.RecipientEnvelopeContext
import dev.hryshyn.remanence.protocol.v1.SenderRetryWrapContext
import com.google.protobuf.ByteString
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.MessageLite

data class ArtifactAadInput(
    val capsuleId: CapsuleId,
    val blobId: BlobId,
    val artifactKind: CapsuleArtifactKind,
    val ordinal: Int,
    val senderUserId: UserId,
    val recipientUserId: UserId,
)

data class RecipientEnvelopeContextInput(
    val capsuleId: CapsuleId,
    val senderUserId: UserId,
    val recipientUserId: UserId,
    val recipientKeyBundleId: KeyBundleId,
)

object CryptoContextEncoder {
    const val ARTIFACT_AAD_PREFIX = "postmark/artifact/v1"
    const val ENVELOPE_INFO_PREFIX = "postmark/envelope/v1"

    /**
     * M2-P08: frozen ASCII prefix binding the sender-retry wrap AAD to
     * its exact context kind. MUST stay distinct from
     * [ARTIFACT_AAD_PREFIX] and [ENVELOPE_INFO_PREFIX] so a wrapped
     * retry keyset can never be confused with an artifact AAD or a
     * recipient envelope context.
     */
    const val SENDER_RETRY_WRAP_AAD_PREFIX = "postmark/sender-retry-wrap/v1"

    fun artifactAad(input: ArtifactAadInput): ByteString {
        validateOrdinal(input.artifactKind, input.ordinal)
        val context = ArtifactAadContext.newBuilder()
            .setProtocolVersion(ProtocolV1Limits.PROTOCOL_VERSION)
            .setCapsuleId(input.capsuleId.toProtoBytes())
            .setBlobId(input.blobId.toProtoBytes())
            .setArtifactKind(generatedKind(input.artifactKind))
            .setOrdinal(input.ordinal)
            .setSenderUserId(input.senderUserId.toProtoBytes())
            .setRecipientUserId(input.recipientUserId.toProtoBytes())
            .build()
        return encode(ARTIFACT_AAD_PREFIX, context)
    }

    fun recipientEnvelopeInfo(input: RecipientEnvelopeContextInput): ByteString {
        val context = RecipientEnvelopeContext.newBuilder()
            .setProtocolVersion(ProtocolV1Limits.PROTOCOL_VERSION)
            .setCapsuleId(input.capsuleId.toProtoBytes())
            .setSenderUserId(input.senderUserId.toProtoBytes())
            .setRecipientUserId(input.recipientUserId.toProtoBytes())
            .setRecipientKeyBundleId(input.recipientKeyBundleId.toProtoBytes())
            .build()
        return encode(ENVELOPE_INFO_PREFIX, context)
    }

    /**
     * M2-P08: deterministic AAD for a sender-retry wrap. Re-derives
     * the AAD on every wrap and every unwrap so a wrapped keyset can
     * only be opened by a caller that supplies the matching
     * owner_user_id, capsule_id, sender_key_bundle_id, and purpose.
     * The proto payload is built with `useDeterministicSerialization`
     * inside [encode] and is therefore bit-for-bit reproducible
     * across processes, architectures, and protobuf-library versions.
     */
    fun senderRetryWrapAad(input: SenderRetryWrapContextInput): ByteString {
        require(input.purpose in SenderRetryPurpose.ACCEPTED) {
            "only ${SenderRetryPurpose.ACCEPTED} are accepted; got ${input.purpose}"
        }
        val context = SenderRetryWrapContext.newBuilder()
            .setProtocolVersion(ProtocolV1Limits.PROTOCOL_VERSION)
            .setOwnerUserId(input.ownerUserId.toProtoBytes())
            .setCapsuleId(input.capsuleId.toProtoBytes())
            .setSenderKeyBundleId(input.senderKeyBundleId.toProtoBytes())
            .setPurpose(input.purpose.toProto())
            .build()
        return encode(SENDER_RETRY_WRAP_AAD_PREFIX, context)
    }

    private fun validateOrdinal(kind: CapsuleArtifactKind, ordinal: Int) {
        val valid = when (kind) {
            CapsuleArtifactKind.PHOTO ->
                ordinal in ProtocolV1Limits.PHOTO_ORDINAL_MIN..ProtocolV1Limits.PHOTO_ORDINAL_MAX
            CapsuleArtifactKind.RECOGNITION_MANIFEST, CapsuleArtifactKind.CONTENT_MANIFEST ->
                ordinal == ProtocolV1Limits.NON_PHOTO_ORDINAL
        }
        if (!valid) {
            throw IllegalArgumentException(INVALID_ORDINAL)
        }
    }

    private fun generatedKind(kind: CapsuleArtifactKind): ArtifactKind =
        when (kind) {
            CapsuleArtifactKind.RECOGNITION_MANIFEST -> ArtifactKind.RECOGNITION_MANIFEST
            CapsuleArtifactKind.CONTENT_MANIFEST -> ArtifactKind.CONTENT_MANIFEST
            CapsuleArtifactKind.PHOTO -> ArtifactKind.PHOTO
        }

    private fun encode(prefix: String, context: MessageLite): ByteString {
        val contextBytes = ByteArray(context.serializedSize)
        val output = CodedOutputStream.newInstance(contextBytes)
        output.useDeterministicSerialization()
        context.writeTo(output)
        output.flush()
        output.checkNoSpaceLeft()
        val whole = ByteArray(prefix.length + DELIMITER_BYTES + contextBytes.size)
        prefix.toByteArray(Charsets.US_ASCII).copyInto(whole)
        whole[prefix.length] = DELIMITER
        contextBytes.copyInto(whole, prefix.length + DELIMITER_BYTES)
        return ByteString.copyFrom(whole)
    }

    private const val DELIMITER: Byte = 0x00
    private const val DELIMITER_BYTES = 1
    private const val INVALID_ORDINAL = "invalid crypto context ordinal"
}
