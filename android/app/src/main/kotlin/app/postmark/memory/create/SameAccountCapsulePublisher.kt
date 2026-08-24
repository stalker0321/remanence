package app.postmark.memory.create

import app.postmark.protocol.v1.RecipientEnvelopePlaintext
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.protobuf.ByteString
import java.security.MessageDigest
import postmark.core.crypto.CapsuleKeysetGenerator
import postmark.core.crypto.ContentManifestCodec
import postmark.core.crypto.ManifestPhoto
import postmark.core.crypto.PhotoArtifactEncryptor
import postmark.core.crypto.PublishStatementSigner
import postmark.core.crypto.RecipientEnvelopeCryptor
import postmark.core.crypto.RecognitionManifestCodec
import postmark.core.crypto.TinkPrimitives
import postmark.core.data.outbox.OutboxArtifactKind
import postmark.core.data.outbox.PreparedOutboxArtifact
import postmark.core.data.outbox.PreparedOutboxCapsule
import postmark.core.model.ArtifactSlot
import postmark.core.model.BlobId
import postmark.core.model.CapsuleArtifactKind
import postmark.core.model.CapsuleId
import postmark.core.model.KeyBundleId
import postmark.core.model.PublishStatementBuildResult
import postmark.core.model.PublishStatementBuilder
import postmark.core.model.PublishArtifact
import postmark.core.model.PublishStatementInput
import postmark.core.model.RecipientEnvelopeContextInput
import postmark.core.model.UserId

/** Inputs for one same-account capsule: content plus both identity halves. */
data class SameAccountCapsuleRequest(
    val capsuleId: CapsuleId,
    val senderUserId: UserId,
    val senderKeyBundleId: KeyBundleId,
    val senderHandleSnapshot: String,
    val createdAtEpochSeconds: Long,
    /** Exactly 3..5 normalized JPEGs. */
    val photoJpegs: List<ByteArray>,
    val photoWidthsPx: List<Int>,
    val photoHeightsPx: List<Int>,
    val noteUtf8: String?,
    val frontFingerprintBytes: ByteArray,
    val backFingerprintBytes: ByteArray,
    /** Sender's own Ed25519 signing keyset (same account = also the recipient). */
    val signingKeyset: KeysetHandle,
    /** Recipient HPKE public keyset; same account means our own public half. */
    val recipientEncryptionPublicKeyset: KeysetHandle,
)

/**
 * I08: produces the complete ciphertext-only capsule for the SAME ACCOUNT -
 * recognition manifest, content manifest, 3-5 encrypted photos, the recipient
 * envelope sealing the capsule keyset to our own identity, and the signed
 * publish statement - in exactly the shape CapsuleOutboxStager persists.
 * Every artifact uses the canonical AAD/context encodings; no plaintext
 * leaves this class.
 */
class SameAccountCapsulePublisher {

    fun publish(request: SameAccountCapsuleRequest): PreparedOutboxCapsule {
        require(request.photoJpegs.size in 3..5) { "3..5 photos required" }
        TinkPrimitives.ensureRegistered()
        val senderUser = request.senderUserId

        val capsuleKeyset = CapsuleKeysetGenerator().generate()

        // Deterministic client-side blob IDs derived from the capsule UUID.
        fun blob(tag: Int): BlobId {
            val base = request.capsuleId.toProtoBytes().toByteArray()
            base[0] = tag.toByte()
            return BlobId.fromProtoBytes(ByteString.copyFrom(base))
        }

        val artifacts = ArrayList<PreparedOutboxArtifact>(2 + request.photoJpegs.size)
        val bindings = ArrayList<PublishArtifact>(artifacts.size)

        // 1. Recognition manifest (fingerprints + minimal chooser hint only).
        val recognitionBlob = blob(RECOGNITION_BLOB_BYTE)
        val recognitionCiphertext = RecognitionManifestCodec().buildAndEncrypt(
            capsuleKeyset,
            routingFor(request.capsuleId, recognitionBlob, senderUser),
            request.senderHandleSnapshot,
            request.createdAtEpochSeconds,
            null,
            request.frontFingerprintBytes,
            request.backFingerprintBytes,
        )
        artifacts += PreparedOutboxArtifact(
            recognitionBlob.value, OutboxArtifactKind.RECOGNITION_MANIFEST, NON_PHOTO_ORDINAL, recognitionCiphertext,
        )
        bindings += PublishArtifact(
            ArtifactSlot(recognitionBlob, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1),
            recognitionCiphertext.size.toLong(),
            ByteString.copyFrom(sha256(recognitionCiphertext)),
        )

        // 2. Content manifest over the exact photo set.
        val contentBlob = blob(CONTENT_BLOB_BYTE)
        val manifestPhotos = request.photoJpegs.mapIndexed { index, _ ->
            ManifestPhoto(blob(PHOTO_BLOB_BASE + index).value, index, request.photoWidthsPx[index], request.photoHeightsPx[index])
        }
        val contentCiphertext = ContentManifestCodec().buildAndEncrypt(
            capsuleKeyset,
            routingFor(request.capsuleId, contentBlob, senderUser),
            manifestPhotos,
            request.noteUtf8,
        )
        artifacts += PreparedOutboxArtifact(
            contentBlob.value, OutboxArtifactKind.CONTENT_MANIFEST, NON_PHOTO_ORDINAL, contentCiphertext,
        )
        bindings += PublishArtifact(
            ArtifactSlot(contentBlob, CapsuleArtifactKind.CONTENT_MANIFEST, -1),
            contentCiphertext.size.toLong(),
            ByteString.copyFrom(sha256(contentCiphertext)),
        )

        // 3. Photos, each under its own PHOTO-ordinal AAD.
        val encryptor = PhotoArtifactEncryptor()
        request.photoJpegs.forEachIndexed { index, jpeg ->
            val photoBlob = blob(PHOTO_BLOB_BASE + index)
            val encrypted = encryptor.encryptPhoto(
                capsuleKeyset,
                routingFor(request.capsuleId, photoBlob, senderUser),
                index,
                jpeg,
            )
            artifacts += PreparedOutboxArtifact(
                photoBlob.value, OutboxArtifactKind.PHOTO, index, encrypted.ciphertext,
            )
            bindings += PublishArtifact(
                ArtifactSlot(photoBlob, CapsuleArtifactKind.PHOTO, index),
                encrypted.ciphertext.size.toLong(),
                ByteString.copyFrom(encrypted.ciphertextSha256),
            )
        }

        // 4. Signed publish statement over the exact declared set.
        val built = PublishStatementBuilder.build(
            PublishStatementInput(
                request.capsuleId, senderUser, senderUser,
                request.senderKeyBundleId, request.senderKeyBundleId, request.createdAtEpochSeconds, bindings,
            ),
        ) as? PublishStatementBuildResult.Success
            ?: throw IllegalStateException("statement layout rejected")
        val signed = PublishStatementSigner().sign(request.signingKeyset, built.deterministicBytes.toByteArray())

        // 5. Recipient envelope sealing the capsule keyset (same account).
        val envelopePlaintext = RecipientEnvelopePlaintext.newBuilder()
            .setProtocolVersion(1)
            .setCapsuleId(built.statement.capsuleId)
            .setSenderUserId(built.statement.senderUserId)
            .setRecipientUserId(built.statement.recipientUserId)
            .setSenderKeyBundleId(built.statement.senderKeyBundleId)
            .setRecipientKeyBundleId(built.statement.recipientKeyBundleId)
            .setCapsuleAeadKeyset(serializedCapsuleKeyset(capsuleKeyset))
            .setPublishStatementSha256(ByteString.copyFrom(sha256(signed.deterministicStatementBytes)))
            .build()
            .toByteArray()
        val envelope = RecipientEnvelopeCryptor().seal(
            request.recipientEncryptionPublicKeyset,
            RecipientEnvelopeContextInput(request.capsuleId, senderUser, senderUser, request.senderKeyBundleId),
            envelopePlaintext,
        )

        return PreparedOutboxCapsule(
            capsuleId = request.capsuleId.value,
            idempotencyKey = "publish-${request.capsuleId.toRestString()}",
            recipientUserId = senderUser.value,
            recipientKeyBundleId = request.senderKeyBundleId.value,
            envelopeCiphertext = envelope,
            artifacts = artifacts,
            publishStatementBytes = signed.deterministicStatementBytes,
            publishStatementSignature = signed.signature,
        )
    }

    private fun routingFor(capsuleId: CapsuleId, blobId: BlobId, user: UserId) =
        RecognitionManifestCodec.RoutingContext(capsuleId, blobId, user, user)

    private fun serializedCapsuleKeyset(handle: KeysetHandle): ByteString =
        ByteString.copyFrom(TinkProtoKeysetFormat.serializeKeyset(handle, com.google.crypto.tink.InsecureSecretKeyAccess.get()))

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    internal companion object {
        const val RECOGNITION_BLOB_BYTE = 0x01
        const val CONTENT_BLOB_BYTE = 0x02
        const val PHOTO_BLOB_BASE = 0x10
        const val NON_PHOTO_ORDINAL = -1
    }
}
