package dev.hryshyn.remanence.create

import dev.hryshyn.remanence.protocol.v1.RecipientEnvelopePlaintext
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.protobuf.ByteString
import java.security.MessageDigest
import java.util.UUID
import dev.hryshyn.remanence.core.crypto.CapsuleKeysetGenerator
import dev.hryshyn.remanence.core.crypto.ContentManifestCodec
import dev.hryshyn.remanence.core.crypto.ManifestPhoto
import dev.hryshyn.remanence.core.crypto.PhotoArtifactEncryptor
import dev.hryshyn.remanence.core.crypto.PublishStatementSigner
import dev.hryshyn.remanence.core.crypto.RecipientEnvelopeCryptor
import dev.hryshyn.remanence.core.crypto.RecognitionManifestCodec
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.crypto.WrappedKeysetRecord
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
import dev.hryshyn.remanence.core.model.SenderRetryPurpose
import dev.hryshyn.remanence.core.model.SenderRetryWrapContextInput
import dev.hryshyn.remanence.core.model.UserId

/**
 * Inputs for one capsule: content plus BOTH identity halves - the SENDER and
 * the RECIPIENT. M2-P06: the three routing-side fields
 * ([recipientUserId], [recipientKeyBundleId], [ownerUserId]) are explicit,
 * required, and must NEVER be inferred from the sender. A self-send passes
 * equal VALUES explicitly; a cross-identity publication passes distinct ones.
 * No value may default from the sender identity; identity conflation is
 * impossible at construction.
 */
data class CapsulePublishRequest(
    val capsuleId: CapsuleId,
    val senderUserId: UserId,
    /**
     * The RECIPIENT user identity - explicit, separate from [senderUserId],
     * and required. M1 self-send passes the same account VALUE explicitly;
     * a cross-identity publication passes a distinct one.
     */
    val recipientUserId: UserId,
    val senderKeyBundleId: KeyBundleId,
    /**
     * The RECIPIENT key-bundle identity - explicit, separate from
     * [senderKeyBundleId], and required.
     */
    val recipientKeyBundleId: KeyBundleId,
    /**
     * The immutable local account that will own every staged outbox row.
     * M2-P02 / M2-P04: explicit and required. A self-send passes the
     * sender's own account VALUE explicitly; a distinct-owner form is
     * passed explicitly once P03/P07 wire scoping.
     */
    val ownerUserId: String,
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

/** A06: rebinds only recipient-facing material to a newly resolved bundle. */
internal data class RecipientRewrapRequest(
    val capsuleId: CapsuleId,
    val senderUserId: UserId,
    val recipientUserId: UserId,
    val senderKeyBundleId: KeyBundleId,
    val recipientKeyBundleId: KeyBundleId,
    val createdAtEpochSeconds: Long,
    val artifacts: List<PublishArtifact>,
    val capsuleKeyset: KeysetHandle,
    val signingKeyset: KeysetHandle,
    val recipientEncryptionPublicKeyset: KeysetHandle,
)

/** The three ciphertext-only files replaced by an A06 rewrap. */
internal data class RewrappedRecipientMaterial(
    val envelopeCiphertext: ByteArray,
    val publishStatementBytes: ByteArray,
    val publishStatementSignature: ByteArray,
)

/**
 * I08: produces the complete ciphertext-only capsule for the given sender
 * AND recipient identities - recognition manifest, content manifest, 3-5
 * encrypted photos, the recipient envelope sealing the capsule keyset to the
 * recipient, and the signed publish statement - in exactly the shape
 * CapsuleOutboxStager persists. Every artifact uses the canonical
 * AAD/context encodings; no plaintext leaves this class.
 *
 * M2-P06: the recipient identity is now passed in the request itself, not
 * inferred from the sender. The cryptographic framing is unchanged from the
 * prior M1 same-account path; only the routing inputs became explicit and
 * required.
 *
 * M2-P08: the publisher now receives a [SenderRetryKeysetWrapper] and the
 * dedicated KEK [alias] to wrap the freshly generated capsule keyset as a
 * sender-owned retry material record. The [ownerUserId] must equal the
 * [senderUserId] (the current sender owns the retry key). The wrapped
 * record is serialized and set on [PreparedOutboxCapsule.senderRetryWrappedKeysetBytes].
 */
class CapsulePublisher(
    private val senderRetryKeysetWrapper: SenderRetryKeysetWrapper,
    private val alias: String,
) {

    fun publish(request: CapsulePublishRequest): PreparedOutboxCapsule {
        require(
            request.photoWidthsPx.size == request.photoJpegs.size &&
                request.photoHeightsPx.size == request.photoJpegs.size,
        ) { "photo metadata cardinality must match photoJpegs" }
        require(request.photoJpegs.size in 3..5) { "3..5 photos required" }
        // M2-P08: the current sender owns the retry key; ownerUserId
        // must equal senderUserId before any crypto work begins.
        require(request.ownerUserId == request.senderUserId.value.toString()) {
            "ownerUserId must equal senderUserId (current sender owns the retry key)"
        }
        TinkPrimitives.ensureRegistered()
        val senderUser = request.senderUserId
        // M2-P06: distinct routing identities for every context/AAD. Read
        // them out of the request - never derived from the sender.
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

        // 6. M2-P08: wrap the capsule keyset as sender-owned retry
        //    material. The same capsuleKeyset that encrypts the artifacts
        //    is wrapped; no copy, no extra keyset.
        val retryContext = SenderRetryWrapContextInput(
            ownerUserId = UserId(request.senderUserId.value),
            capsuleId = request.capsuleId,
            senderKeyBundleId = senderBundle,
            purpose = SenderRetryPurpose.RECIPIENT_KEY_STALE_REWRAP,
        )
        val wrappedRetryRecord = senderRetryKeysetWrapper.wrap(
            alias = alias,
            keyset = capsuleKeyset,
            context = retryContext,
        )

        return PreparedOutboxCapsule(
            capsuleId = request.capsuleId.value,
            // A01 sends this persisted key to the server's UUID-bound draft
            // contract; it is generated once and retained in the outbox.
            idempotencyKey = UUID.randomUUID().toString(),
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
            // M2-P08: serialized wrapped retry record; the stager
            // persists this through SenderRetryMaterialStore.
            senderRetryWrappedKeysetBytes = wrappedRetryRecord.serialize(),
        )
    }

    /**
     * Rebuilds the recipient envelope and signed publish statement without
     * generating a capsule key or touching any encrypted artifact bytes.
     * The caller must have recovered the original capsule key from the
     * owner/capsule-scoped sender retry material.
     */
    internal fun rewrapRecipient(request: RecipientRewrapRequest): RewrappedRecipientMaterial {
        TinkPrimitives.ensureRegistered()
        val built = PublishStatementBuilder.build(
            PublishStatementInput(
                request.capsuleId,
                request.senderUserId,
                request.recipientUserId,
                request.senderKeyBundleId,
                request.recipientKeyBundleId,
                request.createdAtEpochSeconds,
                request.artifacts,
            ),
        ) as? PublishStatementBuildResult.Success
            ?: throw IllegalArgumentException("statement layout rejected")
        val signed = PublishStatementSigner().sign(
            request.signingKeyset,
            built.deterministicBytes.toByteArray(),
        )
        val envelopePlaintext = RecipientEnvelopePlaintext.newBuilder()
            .setProtocolVersion(1)
            .setCapsuleId(built.statement.capsuleId)
            .setSenderUserId(built.statement.senderUserId)
            .setRecipientUserId(built.statement.recipientUserId)
            .setSenderKeyBundleId(built.statement.senderKeyBundleId)
            .setRecipientKeyBundleId(built.statement.recipientKeyBundleId)
            .setCapsuleAeadKeyset(serializedCapsuleKeyset(request.capsuleKeyset))
            .setPublishStatementSha256(ByteString.copyFrom(sha256(signed.deterministicStatementBytes)))
            .build()
            .toByteArray()
        val envelope = RecipientEnvelopeCryptor().seal(
            request.recipientEncryptionPublicKeyset,
            RecipientEnvelopeContextInput(
                request.capsuleId,
                request.senderUserId,
                request.recipientUserId,
                request.recipientKeyBundleId,
            ),
            envelopePlaintext,
        )
        return RewrappedRecipientMaterial(
            envelopeCiphertext = envelope,
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
