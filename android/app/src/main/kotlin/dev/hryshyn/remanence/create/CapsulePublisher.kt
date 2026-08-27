package dev.hryshyn.remanence.create

import dev.hryshyn.remanence.protocol.v1.RecipientEnvelopePlaintext
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.protobuf.ByteString
import java.security.MessageDigest
import dev.hryshyn.remanence.core.crypto.CapsuleKeysetGenerator
import dev.hryshyn.remanence.core.crypto.ContentManifestCodec
import dev.hryshyn.remanence.core.crypto.ManifestPhoto
import dev.hryshyn.remanence.core.crypto.PhotoArtifactEncryptor
import dev.hryshyn.remanence.core.crypto.PublishStatementSigner
import dev.hryshyn.remanence.core.crypto.RecipientEnvelopeCryptor
import dev.hryshyn.remanence.core.crypto.RecognitionManifestCodec
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.outbox.OutboxArtifactKind
import dev.hryshyn.remanence.core.data.outbox.PreparedOutboxArtifact
import dev.hryshyn.remanence.core.data.outbox.PreparedOutboxCapsule
import dev.hryshyn.remanence.core.model.ArtifactSlot
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.PublishStatementBuildResult
import dev.hryshyn.remanence.core.model.PublishStatementBuilder
import dev.hryshyn.remanence.core.model.PublishArtifact
import dev.hryshyn.remanence.core.model.PublishStatementInput
import dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput
import dev.hryshyn.remanence.core.model.UserId

/** Inputs for one capsule: content plus both identity halves. */
data class CapsulePublishRequest(
    val capsuleId: CapsuleId,
    val senderUserId: UserId,
    /**
     * FIX-REVIEW-04: the RECIPIENT identity is explicit and separate. M1
     * self-send defaults to the same account VALUES - naturally equal, never
     * conflated by assumption.
     */
    val recipientUserId: UserId = senderUserId,
    val senderKeyBundleId: KeyBundleId,
    val recipientKeyBundleId: KeyBundleId = senderKeyBundleId,
    /**
     * M2-P02: immutable local account that will own every staged outbox row.
     * M1 self-send defaults to the sender's own account VALUES - the
     * authenticated local account under the single-account milestone. The
     * distinct-owner form is passed explicitly once P03 wires scoping.
     */
    val ownerUserId: String = senderUserId.toRestString(),
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
class CapsulePublisher {

    fun publish(request: CapsulePublishRequest): PreparedOutboxCapsule {
        require(request.photoJpegs.size in 3..5) { "3..5 photos required" }
        TinkPrimitives.ensureRegistered()
        val senderUser = request.senderUserId
        // FIX-REVIEW-04: distinct routing identities for every context/AAD.
        val recipientUser = request.recipientUserId
        val senderBundle = request.senderKeyBundleId
        val recipientBundle = request.recipientKeyBundleId

        val capsuleKeyset = CapsuleKeysetGenerator().generate()

        // Deterministic client-side blob IDs derived from the capsule UUID.
        fun blob(tag: Int): BlobId {
            val base = request.capsuleId.toProtoBytes().toByteArray()
            base[0] = tag.toByte()
            return BlobId.fromProtoBytes(ByteString.copyFrom(base))
        }

        val artifacts = ArrayList<PreparedOutboxArtifact>(2 + request.photoJpegs.size)
        val bindings = ArrayList<PublishArtifact>(artifacts.size)

        fun routing(capsuleId: CapsuleId, blobId: BlobId) =
            RecognitionManifestCodec.RoutingContext(capsuleId, blobId, senderUser, recipientUser)

        // 1. Recognition manifest (fingerprints + minimal chooser hint only).
        val recognitionBlob = blob(RECOGNITION_BLOB_BYTE)
        val recognitionCiphertext = RecognitionManifestCodec().buildAndEncrypt(
            capsuleKeyset,
            routing(request.capsuleId, recognitionBlob),
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
            routing(request.capsuleId, contentBlob),
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
                routingFor(request.capsuleId, photoBlob, senderUser, recipientUser),
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
                request.capsuleId, senderUser, recipientUser,
                senderBundle, recipientBundle, request.createdAtEpochSeconds, bindings,
            ),
        ) as? PublishStatementBuildResult.Success
            ?: throw IllegalStateException("statement layout rejected")
        val signed = PublishStatementSigner().sign(request.signingKeyset, built.deterministicBytes.toByteArray())

        // 5. Recipient envelope sealing the capsule keyset.
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
            RecipientEnvelopeContextInput(request.capsuleId, senderUser, recipientUser, recipientBundle),
            envelopePlaintext,
        )

        return PreparedOutboxCapsule(
            capsuleId = request.capsuleId.value,
            idempotencyKey = "publish-${request.capsuleId.toRestString()}",
            ownerUserId = request.ownerUserId,
            senderUserId = senderUser.value,
            recipientUserId = recipientUser.value,
            senderKeyBundleId = senderBundle.value,
            recipientKeyBundleId = recipientBundle.value,
            // Public verification material only: the sender's Ed25519 public half.
            senderSigningPublicKeysetB64Url =
                com.google.crypto.tink.subtle.Base64.urlSafeEncode(
                    TinkProtoKeysetFormat.serializeKeyset(
                        request.signingKeyset.publicKeysetHandle,
                        com.google.crypto.tink.InsecureSecretKeyAccess.get(),
                    ),
                ),
            envelopeCiphertext = envelope,
            artifacts = artifacts,
            publishStatementBytes = signed.deterministicStatementBytes,
            publishStatementSignature = signed.signature,
        )
    }

    private fun routingFor(capsuleId: CapsuleId, blobId: BlobId, sender: UserId, recipient: UserId) =
        RecognitionManifestCodec.RoutingContext(capsuleId, blobId, sender, recipient)

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
