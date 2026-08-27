package dev.hryshyn.remanence.core.model

import dev.hryshyn.remanence.protocol.v1.ArtifactBinding
import dev.hryshyn.remanence.protocol.v1.ArtifactKind
import dev.hryshyn.remanence.protocol.v1.PublishStatement
import com.google.protobuf.ByteString
import com.google.protobuf.CodedOutputStream

data class PublishArtifact(
    val slot: ArtifactSlot,
    val ciphertextSize: Long,
    val ciphertextSha256: ByteString,
)

data class PublishStatementInput(
    val capsuleId: CapsuleId,
    val senderUserId: UserId,
    val recipientUserId: UserId,
    val senderKeyBundleId: KeyBundleId,
    val recipientKeyBundleId: KeyBundleId,
    val createdAtEpochSeconds: Long,
    val artifacts: List<PublishArtifact>,
)

sealed interface PublishStatementBuildResult {
    data class Success(
        val statement: PublishStatement,
        val deterministicBytes: ByteString,
    ) : PublishStatementBuildResult

    data class InvalidLayout(val reason: ArtifactLayoutError) : PublishStatementBuildResult

    data object InvalidCiphertextSha256 : PublishStatementBuildResult

    data object InvalidCiphertextSize : PublishStatementBuildResult
}

object PublishStatementBuilder {
    fun build(input: PublishStatementInput): PublishStatementBuildResult {
        val layout = ArtifactLayoutValidator.validate(input.artifacts.map { it.slot })
        if (layout is ArtifactLayoutValidation.Invalid) {
            return PublishStatementBuildResult.InvalidLayout(layout.reason)
        }
        for (artifact in input.artifacts) {
            if (artifact.ciphertextSha256.size() != SHA256_BYTES) {
                return PublishStatementBuildResult.InvalidCiphertextSha256
            }
        }
        for (artifact in input.artifacts) {
            val cap = ciphertextCap(artifact.slot.kind)
            if (artifact.ciphertextSize <= 0L || artifact.ciphertextSize > cap) {
                return PublishStatementBuildResult.InvalidCiphertextSize
            }
        }
        val sorted = input.artifacts.sortedWith { left, right ->
            CanonicalArtifactOrder.compare(left.slot, right.slot)
        }
        val statement = PublishStatement.newBuilder()
            .setProtocolVersion(ProtocolV1Limits.PROTOCOL_VERSION)
            .setCapsuleId(input.capsuleId.toProtoBytes())
            .setSenderUserId(input.senderUserId.toProtoBytes())
            .setRecipientUserId(input.recipientUserId.toProtoBytes())
            .setSenderKeyBundleId(input.senderKeyBundleId.toProtoBytes())
            .setRecipientKeyBundleId(input.recipientKeyBundleId.toProtoBytes())
            .setCreatedAtEpochSeconds(input.createdAtEpochSeconds)
            .apply {
                for (artifact in sorted) {
                    addArtifacts(toBinding(artifact))
                }
            }
            .build()
        val bytes = ByteArray(statement.serializedSize)
        val output = CodedOutputStream.newInstance(bytes)
        output.useDeterministicSerialization()
        statement.writeTo(output)
        output.flush()
        output.checkNoSpaceLeft()
        return PublishStatementBuildResult.Success(statement, ByteString.copyFrom(bytes))
    }

    private fun toBinding(artifact: PublishArtifact): ArtifactBinding =
        ArtifactBinding.newBuilder()
            .setBlobId(artifact.slot.blobId.toProtoBytes())
            .setKind(generatedKind(artifact.slot.kind))
            .setOrdinal(artifact.slot.ordinal)
            .setCiphertextSize(artifact.ciphertextSize)
            .setCiphertextSha256(artifact.ciphertextSha256)
            .build()

    private fun generatedKind(kind: CapsuleArtifactKind): ArtifactKind =
        when (kind) {
            CapsuleArtifactKind.RECOGNITION_MANIFEST -> ArtifactKind.RECOGNITION_MANIFEST
            CapsuleArtifactKind.CONTENT_MANIFEST -> ArtifactKind.CONTENT_MANIFEST
            CapsuleArtifactKind.PHOTO -> ArtifactKind.PHOTO
        }

    private fun ciphertextCap(kind: CapsuleArtifactKind): Long =
        when (kind) {
            CapsuleArtifactKind.RECOGNITION_MANIFEST ->
                ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES
            CapsuleArtifactKind.CONTENT_MANIFEST ->
                ProtocolV1Limits.CONTENT_MANIFEST_MAX_CIPHERTEXT_BYTES
            CapsuleArtifactKind.PHOTO ->
                ProtocolV1Limits.ENCRYPTED_PHOTO_MAX_CIPHERTEXT_BYTES
        }

    private const val SHA256_BYTES = 32
}
