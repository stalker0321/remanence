package dev.hryshyn.remanence.create

import dev.hryshyn.remanence.protocol.v1.RecipientEnvelopePlaintext
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.protobuf.ByteString
import java.security.MessageDigest
import java.util.UUID
import dev.hryshyn.remanence.core.crypto.CapsuleArtifactCryptor
import dev.hryshyn.remanence.core.crypto.CapsuleKeysetGenerator
import dev.hryshyn.remanence.core.crypto.ManifestVideo
import dev.hryshyn.remanence.core.crypto.ManifestVoice
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
import dev.hryshyn.remanence.core.model.ArtifactAadInput
import dev.hryshyn.remanence.core.model.ArtifactSlot
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.CapsulePhotoIdentity
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.PublishStatementBuildResult
import dev.hryshyn.remanence.core.model.PublishStatementBuilder
import dev.hryshyn.remanence.core.model.PublishArtifact
import dev.hryshyn.remanence.core.model.PublishStatementInput
import dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput
import dev.hryshyn.remanence.core.model.SenderRetryPurpose
import dev.hryshyn.remanence.core.model.SenderRetryWrapContextInput
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.model.CanonicalSiftFingerprintValidator
import dev.hryshyn.remanence.core.recognition.RecognitionProfile

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
    /** Required FRONT recognition fingerprint; BACK is not a publication input. */
    val frontFingerprintBytes: ByteArray,
    /** Profile identifier paired with [frontFingerprintBytes]. */
    val frontFingerprintProfileId: String = RecognitionProfile.SIFT_ROOTSIFT_V1_ID,
    /** Sender's own Ed25519 signing keyset (same account = also the recipient). */
    val signingKeyset: KeysetHandle,
    /** Recipient HPKE public keyset; same account means our own public half. */
    val recipientEncryptionPublicKeyset: KeysetHandle,
    /**
     * ADR-018 v2: optional authenticated BER1 expression artifact. Null = v1
     * (byte-frozen). When present the content manifest becomes v2 and the
     * expression is bound by the same signed manifest ciphertext hash.
     */
    val expression: CapsuleExpressionArtifact? = null,
    /**
     * S2b-sender: optional own-catalog track snapshot. Null = absent (v1
     * and snapshot-less v2 unchanged). When present the manifest becomes
     * v2 via [expression]; a snapshot without an expression is refused
     * fail-closed in [CapsulePublisher.publish], never silently dropped.
     */
    val trackSnapshot: dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1? = null,
    /** Long note read beside the sheet (manifest field 8); requires [expression]. */
    val letter: String? = null,
    /** The recorded voice note (ADR-021); requires [expression] with a voice band and no song. */
    val voice: CapsuleVoiceNote? = null,
    /** The video square (ADR-021); requires [expression] with its square poster among the photos. */
    val video: CapsuleVideoSquare? = null,
) {
    /** Redacted so note text / photo bytes / keys can never reach logs. */
    override fun toString(): String =
        "CapsulePublishRequest(capsuleId=$capsuleId, senderUserId=$senderUserId, " +
            "recipientUserId=$recipientUserId, senderKeyBundleId=$senderKeyBundleId, " +
            "recipientKeyBundleId=$recipientKeyBundleId, ownerUserId=$ownerUserId, " +
            "senderHandleSnapshot=<redacted>, createdAtEpochSeconds=$createdAtEpochSeconds, " +
            "photoCount=${photoJpegs.size}, notePresent=${noteUtf8 != null}, " +
            "expression=${if (expression == null) "v1" else "v2"}, " +
            "trackSnapshot=${if (trackSnapshot == null) "absent" else "present"}, " +
            "letterPresent=${letter != null}, voicePresent=${voice != null}, videoPresent=${video != null}, " +
            "frontFingerprintProfileId=$frontFingerprintProfileId)"
}

/**
 * A recorded voice note: the AAC/MP4 the recipient plays from the band, its
 * length and the [ManifestVoice.PEAKS] amplitudes (0..255) the band draws.
 */
class CapsuleVoiceNote(
    val mp4: ByteArray,
    val durationMs: Int,
    val peaks: IntArray,
) {
    init {
        require(mp4.size >= 8 && String(mp4, 4, 4, Charsets.US_ASCII) == "ftyp") { "voice note must be an MP4" }
        require(mp4.size + ProtocolV1Limits.ARTIFACT_AEAD_OVERHEAD_BYTES <= ProtocolV1Limits.ENCRYPTED_MEDIA_MAX_CIPHERTEXT_BYTES) { "voice note is too large" }
        require(durationMs in 1..ManifestVoice.MAX_MS) { "voice note length out of bounds" }
        require(peaks.size == ManifestVoice.PEAKS && peaks.all { it in 0..255 }) { "voice note peaks out of bounds" }
    }

    override fun toString(): String = "CapsuleVoiceNote(<redacted>, durationMs=$durationMs)"
}

/**
 * A recorded video square: the H.264 + AAC MP4 the recipient plays over its
 * first frame's tile, its length, square side, and that photo's content id.
 */
class CapsuleVideoSquare(
    val mp4: ByteArray,
    val durationMs: Int,
    val size: Int,
    val posterContentId: String,
) {
    init {
        require(mp4.size >= 8 && String(mp4, 4, 4, Charsets.US_ASCII) == "ftyp") { "video square must be an MP4" }
        require(mp4.size + ProtocolV1Limits.ARTIFACT_AEAD_OVERHEAD_BYTES <= ProtocolV1Limits.ENCRYPTED_VIDEO_MAX_CIPHERTEXT_BYTES) { "video square is too large" }
        require(durationMs in 1..ManifestVideo.MAX_MS) { "video square length out of bounds" }
        require(size in ManifestVideo.MIN_SIZE..ManifestVideo.MAX_SIZE) { "video square side out of bounds" }
        require(posterContentId.isNotBlank()) { "video square needs its first frame" }
    }

    override fun toString(): String = "CapsuleVideoSquare(<redacted>, durationMs=$durationMs, size=$size)"
}

/** ADR-018 v2 expression payload; [contentIds] are authored order, one per photoJpeg. */
data class CapsuleExpressionArtifact(
    val candidateId: String,
    val contentIds: List<String>,
    val expression: dev.hryshyn.remanence.core.model.GeneratorExpression.ResolvedExpression,
) {
    /** Redacted: the expression may contain the authored note text. */
    override fun toString(): String =
        "CapsuleExpressionArtifact(candidateId=$candidateId, contentCount=${contentIds.size}, " +
            "contentIds=<redacted>, expression=<redacted>)"
}

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
 * AND recipient identities - inner-v2 FRONT-only recognition manifest,
 * content manifest, 3-5 encrypted photos, the recipient envelope sealing the
 * capsule keyset to the recipient, and the signed publish statement - in
 * exactly the shape CapsuleOutboxStager persists. Outer REST/statement/
 * envelope/AAD remain protocol v1. Every artifact uses the canonical
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
        require(request.frontFingerprintBytes.isNotEmpty()) { "front fingerprint is required" }
        CanonicalSiftFingerprintValidator.requireCanonical(
            profileId = request.frontFingerprintProfileId,
            bytes = request.frontFingerprintBytes,
        )
        // S2b-sender: a track snapshot rides only the v2 expression frame.
        // A snapshot without an expression is refused before any crypto
        // work — never silently dropped into a v1 manifest.
        require(request.letter == null || request.expression != null) {
            "a letter requires the v2 expression artifact"
        }
        require(request.trackSnapshot == null || request.expression != null) {
            "track snapshot requires the v2 expression frame"
        }
        require(request.voice == null || (request.expression != null && request.trackSnapshot == null)) {
            "a voice note requires the v2 expression frame and no song"
        }
        require(request.video == null || request.expression?.contentIds?.contains(request.video.posterContentId) == true) {
            "a video square requires the v2 expression frame with its first frame among the photos"
        }
        // M2-P08: the current sender owns the retry key; ownerUserId
        // must equal senderUserId before any crypto work begins.
        require(request.ownerUserId == request.senderUserId.value.toString()) {
            "ownerUserId must equal senderUserId (current sender owns the retry key)"
        }
        val frontContentSha256 = sha256(request.frontFingerprintBytes)
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

        // 1. Recognition manifest (required FRONT + minimal chooser hint only).
        val recognitionBlob = blob(RECOGNITION_BLOB_BYTE)
        val recognitionCiphertext = RecognitionManifestCodec().buildAndEncrypt(
            capsuleKeyset,
            routing(request.capsuleId, recognitionBlob),
            request.senderHandleSnapshot,
            request.createdAtEpochSeconds,
            null,
            request.frontFingerprintBytes,
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
            ManifestPhoto(CapsulePhotoIdentity.photoBlobId(request.capsuleId, index).value, index, request.photoWidthsPx[index], request.photoHeightsPx[index])
        }
        val mediaBlob = blob(MEDIA_BLOB_BYTE)
        val manifestVoice = request.voice?.let { ManifestVoice(mediaBlob.value, it.durationMs, ByteArray(it.peaks.size) { index -> it.peaks[index].toByte() }) }
        val videoBlob = blob(VIDEO_BLOB_BYTE)
        val manifestVideo = request.video?.let { ManifestVideo(videoBlob.value, it.durationMs, it.size, it.posterContentId) }
        val contentCiphertext = if (request.expression != null) {
            val artifact = request.expression
            require(artifact.contentIds.size == request.photoJpegs.size) {
                "expression content ids must match the photo set"
            }
            require(artifact.contentIds.toSet().size == artifact.contentIds.size) {
                "expression content ids must be unique"
            }
            require(artifact.expression.input.photos.map { it.contentId } == artifact.contentIds) {
                "expression photos must be authored order matching contentIds"
            }
            val blobIdByContentId = artifact.contentIds.mapIndexed { index, contentId ->
                contentId to CapsulePhotoIdentity.photoBlobBytes(request.capsuleId, index)
            }.toMap()
            ContentManifestCodec().buildAndEncryptV2(
                capsuleKeyset,
                routing(request.capsuleId, contentBlob),
                manifestPhotos,
                request.noteUtf8,
                artifact.candidateId,
                artifact.expression,
                blobIdByContentId,
                request.trackSnapshot,
                request.letter,
                manifestVoice,
                manifestVideo,
            )
        } else {
            ContentManifestCodec().buildAndEncrypt(
                capsuleKeyset,
                routing(request.capsuleId, contentBlob),
                manifestPhotos,
                request.noteUtf8,
            )
        }
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
            val photoBlob = CapsulePhotoIdentity.photoBlobId(request.capsuleId, index)
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

        // 3b. The voice note, one MEDIA artifact after the photos.
        request.voice?.let { voice ->
            val ciphertext = CapsuleArtifactCryptor().encrypt(
                capsuleKeyset,
                ArtifactAadInput(
                    capsuleId = request.capsuleId,
                    blobId = mediaBlob,
                    artifactKind = CapsuleArtifactKind.MEDIA,
                    ordinal = NON_PHOTO_ORDINAL,
                    senderUserId = senderUser,
                    recipientUserId = recipientUser,
                ),
                voice.mp4,
            )
            artifacts += PreparedOutboxArtifact(mediaBlob.value, OutboxArtifactKind.MEDIA, NON_PHOTO_ORDINAL, ciphertext)
            bindings += PublishArtifact(
                ArtifactSlot(mediaBlob, CapsuleArtifactKind.MEDIA, NON_PHOTO_ORDINAL),
                ciphertext.size.toLong(),
                ByteString.copyFrom(sha256(ciphertext)),
            )
        }

        // 3c. The video square, one VIDEO artifact after the voice note.
        request.video?.let { video ->
            val ciphertext = CapsuleArtifactCryptor().encrypt(
                capsuleKeyset,
                ArtifactAadInput(
                    capsuleId = request.capsuleId,
                    blobId = videoBlob,
                    artifactKind = CapsuleArtifactKind.VIDEO,
                    ordinal = NON_PHOTO_ORDINAL,
                    senderUserId = senderUser,
                    recipientUserId = recipientUser,
                ),
                video.mp4,
            )
            artifacts += PreparedOutboxArtifact(videoBlob.value, OutboxArtifactKind.VIDEO, NON_PHOTO_ORDINAL, ciphertext)
            bindings += PublishArtifact(
                ArtifactSlot(videoBlob, CapsuleArtifactKind.VIDEO, NON_PHOTO_ORDINAL),
                ciphertext.size.toLong(),
                ByteString.copyFrom(sha256(ciphertext)),
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
            // Exact local duplicate identity: this is the digest of the same
            // captured FRONT bytes consumed by the recognition manifest
            // builder, never ciphertext or a recognition score.
            frontContentSha256 = frontContentSha256,
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
        const val MEDIA_BLOB_BYTE = 0x03
        const val VIDEO_BLOB_BYTE = 0x04
        const val PHOTO_BLOB_BASE = 0x10
        const val NON_PHOTO_ORDINAL = -1
    }
}
