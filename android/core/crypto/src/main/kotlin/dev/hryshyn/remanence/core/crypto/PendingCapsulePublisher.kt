package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.CapsulePhotoIdentity
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.GeneratorExpressionProjection
import java.security.MessageDigest

/**
 * T186 M12: sender-side v2 sealed publication (frozen T185
 * `crypto-contract.md` §1–§3). Photo JPEGs in, sealed v2 delivery
 * material out — real Tink/canonical protobuf throughout.
 *
 * Photo-only v2 delivery: each JPEG is sealed under the fresh capsule
 * keyset with the delivery-target-bound v2 AAD
 * (`PendingArtifactCryptor`, kind 3 = PHOTO, ordinal = index,
 * deterministic `CapsulePhotoIdentity` blob ids); D/KC/S/signature/
 * escrow via `PendingPublishCrypto`. The content-manifest/note binding
 * is explicitly NOT faked here — it arrives with the v2
 * content-manifest milestone; recipients open photos through the
 * R12/manifest/blob refs + `PendingArtifactCryptor.decrypt`.
 *
 * Inner payloads are untouched (caller-supplied JPEG bytes are sealed
 * as-is); secrets never log (redacted `toString` on request types is
 * the caller's duty — this object holds no state).
 */
object PendingCapsulePublisher {

    /** Photo count bounds (mirror the v1 pipeline: 1..5 authored photos). */
    const val MIN_PHOTOS: Int = 1
    const val MAX_PHOTOS: Int = 5

    /** Per-photo plaintext ceiling (same as the v1 photo encryptor). */
    const val MAX_PHOTO_BYTES: Int = 8 * 1024 * 1024

    data class SealedArtifact(
        val blobId: String,
        val kind: Int,
        val ordinal: Int,
        val ciphertext: ByteArray,
        val ciphertextSha256: ByteArray,
    )

    /** Authored photo input (JPEG bytes + authored dimensions). */
    data class PhotoInput(
        val jpeg: ByteArray,
        val widthPx: Int,
        val heightPx: Int,
    )

    /**
     * Optional authored expression frame (mirrors the v1
     * `CapsulePublishRequest.expression` + `trackSnapshot` inputs, in
     * crypto-level types). Null = plain content manifest (v1 shape).
     */
    data class ExpressionInput(
        val candidateId: String,
        val expression: GeneratorExpression.ResolvedExpression,
        /** Authored-order content ids, one per photo (required). */
        val contentIds: List<String>,
        val trackSnapshot: CapsuleTrackSnapshotV1?,
    )

    data class Publication(
        val salt32: ByteArray,
        val claimExpiresAt: Long,
        val artifacts: List<SealedArtifact>,
        val statement: ByteArray,
        val signature: ByteArray,
        val escrow: ByteArray,
    )

    @Suppress("LongParameterList")
    fun publish(
        capsuleId: String,
        senderUserId: String,
        senderKeyBundleId: String,
        deliveryTargetId: String,
        serviceKeyId: String,
        channel: Int,
        normalizedDestination: String,
        salt32: ByteArray,
        claimExpiresAt: Long,
        createdAtEpochSeconds: Long,
        signingHandle: KeysetHandle,
        capsuleKeyset: KeysetHandle,
        photos: List<ByteArray>,
    ): Publication {
        require(photos.size in MIN_PHOTOS..MAX_PHOTOS) {
            "pending publication requires 1..5 photos"
        }
        require(salt32.size == 32) { "pending address salt must be 32 bytes" }
        for (photo in photos) {
            require(photo.isNotEmpty() && photo.size <= MAX_PHOTO_BYTES) {
                "pending photo bytes out of budget"
            }
        }
        val capsule = CapsuleId.parseRest(capsuleId)
        val opening = PendingPublishCrypto.addressOpening(channel, normalizedDestination, salt32)
        val commitment = PendingPublishCrypto.addressCommitment(opening)
        val keyBytes = TinkProtoKeysetFormat.serializeKeyset(
            capsuleKeyset, InsecureSecretKeyAccess.get(),
        )
        val keyCommitment = PendingPublishCrypto.keyCommitment(capsuleId, deliveryTargetId, keyBytes)
        val sealed = photos.mapIndexed { index, jpeg ->
            sealPhoto(
                capsuleKeyset, capsuleId, capsule, senderUserId, deliveryTargetId,
                index, jpeg,
            )
        }
        return finalizePublication(
            capsuleId = capsuleId,
            senderUserId = senderUserId,
            senderKeyBundleId = senderKeyBundleId,
            deliveryTargetId = deliveryTargetId,
            serviceKeyId = serviceKeyId,
            claimExpiresAt = claimExpiresAt,
            createdAtEpochSeconds = createdAtEpochSeconds,
            signingHandle = signingHandle,
            commitment = commitment,
            keyCommitment = keyCommitment,
            keyBytes = keyBytes,
            opening = opening,
            salt32 = salt32,
            sealed = sealed,
        )
    }

    /**
     * T186 M12c: full-payload publication — recognition (kind 1, ord
     * -1) + content (kind 2, ord -1, note/music/expression preserved)
     * + photos (kind 3), all sealed under the delivery-target AAD with
     * byte-identical inner bytes to the v1 codecs (differential tests).
     * Declarations feed S sorted `(kind, ordinal, blob_id)` per the
     * frozen server validation. This is the payload the publish
     * boundary uses; [publish] stays for the photos-only unit path.
     */
    @Suppress("LongParameterList")
    fun publishFull(
        capsuleId: String,
        senderUserId: String,
        senderKeyBundleId: String,
        deliveryTargetId: String,
        serviceKeyId: String,
        channel: Int,
        normalizedDestination: String,
        salt32: ByteArray,
        claimExpiresAt: Long,
        createdAtEpochSeconds: Long,
        signingHandle: KeysetHandle,
        capsuleKeyset: KeysetHandle,
        senderHandleSnapshot: String,
        frontFingerprintBytes: ByteArray,
        photos: List<PhotoInput>,
        noteUtf8: String?,
        expression: ExpressionInput? = null,
    ): Publication {
        require(photos.size in MIN_PHOTOS..MAX_PHOTOS) {
            "pending publication requires 1..5 photos"
        }
        require(salt32.size == 32) { "pending address salt must be 32 bytes" }
        for (photo in photos) {
            require(photo.jpeg.isNotEmpty() && photo.jpeg.size <= MAX_PHOTO_BYTES) {
                "pending photo bytes out of budget"
            }
        }
        if (expression != null) {
            require(expression.candidateId.isNotBlank()) { "pending expression candidate id invalid" }
            require(expression.contentIds.size == photos.size) {
                "pending expression content ids must match the photo set"
            }
            require(
                GeneratorExpression.validateResolved(expression.expression) is
                    GeneratorExpression.InputValidation.Valid,
            ) { "pending expression must be structurally valid" }
        }
        val capsule = CapsuleId.parseRest(capsuleId)
        val opening = PendingPublishCrypto.addressOpening(channel, normalizedDestination, salt32)
        val commitment = PendingPublishCrypto.addressCommitment(opening)
        val keyBytes = TinkProtoKeysetFormat.serializeKeyset(
            capsuleKeyset, InsecureSecretKeyAccess.get(),
        )
        val keyCommitment = PendingPublishCrypto.keyCommitment(capsuleId, deliveryTargetId, keyBytes)
        // Recognition manifest: byte-identical inner plaintext to v1.
        val recognitionBlob = blobWithTag(capsule, RECOGNITION_BLOB_BYTE).toRestString()
        val recognitionPlaintext = RecognitionManifestCodec().buildPlaintext(
            capsule, senderHandleSnapshot, createdAtEpochSeconds, null, frontFingerprintBytes,
        )
        val recognitionCiphertext = PendingArtifactCryptor.encrypt(
            capsuleKeyset, capsuleId, recognitionBlob, 1, NON_PHOTO_ORDINAL,
            senderUserId, deliveryTargetId, recognitionPlaintext,
        )
        // Content manifest: byte-identical inner plaintext to v1
        // (plain or expression frame, track snapshot preserved).
        val manifestPhotos = photos.mapIndexed { index, photo ->
            ManifestPhoto(
                CapsulePhotoIdentity.photoBlobId(capsule, index).value,
                index, photo.widthPx, photo.heightPx,
            )
        }
        val contentBlob = blobWithTag(capsule, CONTENT_BLOB_BYTE).toRestString()
        val contentPlaintext = if (expression == null) {
            ContentManifestCodec().buildPlaintext(
                capsule, manifestPhotos, noteUtf8, null, null, null,
            )
        } else {
            val blobIdByContentId = CapsulePhotoIdentity.blobIdByContentId(
                capsule, expression.contentIds,
            )
            val projectionHash = GeneratorExpressionProjection.hash(
                expression.expression, expression.candidateId, blobIdByContentId,
            )
            ContentManifestCodec().buildPlaintext(
                capsule, manifestPhotos, noteUtf8,
                Triple(expression.candidateId, projectionHash, expression.expression),
                blobIdByContentId, expression.trackSnapshot,
            )
        }
        val contentCiphertext = PendingArtifactCryptor.encrypt(
            capsuleKeyset, capsuleId, contentBlob, 2, NON_PHOTO_ORDINAL,
            senderUserId, deliveryTargetId, contentPlaintext,
        )
        val sealed = ArrayList<SealedArtifact>(2 + photos.size)
        sealed += SealedArtifact(
            recognitionBlob, 1, NON_PHOTO_ORDINAL, recognitionCiphertext,
            MessageDigest.getInstance("SHA-256").digest(recognitionCiphertext),
        )
        sealed += SealedArtifact(
            contentBlob, 2, NON_PHOTO_ORDINAL, contentCiphertext,
            MessageDigest.getInstance("SHA-256").digest(contentCiphertext),
        )
        photos.forEachIndexed { index, photo ->
            sealed += sealPhoto(
                capsuleKeyset, capsuleId, capsule, senderUserId, deliveryTargetId,
                index, photo.jpeg,
            )
        }
        // Server-frozen declaration order: (kind, ordinal, blob_id).
        sealed.sortWith(
            compareBy<SealedArtifact>({ it.kind }, { it.ordinal }, { it.blobId }),
        )
        return finalizePublication(
            capsuleId = capsuleId,
            senderUserId = senderUserId,
            senderKeyBundleId = senderKeyBundleId,
            deliveryTargetId = deliveryTargetId,
            serviceKeyId = serviceKeyId,
            claimExpiresAt = claimExpiresAt,
            createdAtEpochSeconds = createdAtEpochSeconds,
            signingHandle = signingHandle,
            commitment = commitment,
            keyCommitment = keyCommitment,
            keyBytes = keyBytes,
            opening = opening,
            salt32 = salt32,
            sealed = sealed,
        )
    }

    private fun sealPhoto(
        capsuleKeyset: KeysetHandle,
        capsuleId: String,
        capsule: CapsuleId,
        senderUserId: String,
        deliveryTargetId: String,
        index: Int,
        jpeg: ByteArray,
    ): SealedArtifact {
        val blobId = CapsulePhotoIdentity.photoBlobId(capsule, index).toRestString()
        val ciphertext = PendingArtifactCryptor.encrypt(
            capsuleKeyset, capsuleId, blobId, 3, index,
            senderUserId, deliveryTargetId, jpeg,
        )
        return SealedArtifact(
            blobId, 3, index, ciphertext,
            MessageDigest.getInstance("SHA-256").digest(ciphertext),
        )
    }

    /** Deterministic non-photo blob id (v1 `0x01`/`0x02` tag layout). */
    private fun blobWithTag(capsule: CapsuleId, tag: Int): BlobId {
        val base = capsule.toProtoBytes().toByteArray()
        base[0] = tag.toByte()
        return BlobId.fromProtoBytes(com.google.protobuf.ByteString.copyFrom(base))
    }

    @Suppress("LongParameterList")
    private fun finalizePublication(
        capsuleId: String,
        senderUserId: String,
        senderKeyBundleId: String,
        deliveryTargetId: String,
        serviceKeyId: String,
        claimExpiresAt: Long,
        createdAtEpochSeconds: Long,
        signingHandle: KeysetHandle,
        commitment: ByteArray,
        keyCommitment: ByteArray,
        keyBytes: ByteArray,
        opening: ByteArray,
        salt32: ByteArray,
        sealed: List<SealedArtifact>,
    ): Publication {
        val statement = PendingPublishCrypto.buildStatement(
            capsuleId = capsuleId,
            senderUserId = senderUserId,
            senderKeyBundleId = senderKeyBundleId,
            deliveryTargetId = deliveryTargetId,
            addressCommitment = commitment,
            keyCommitment = keyCommitment,
            createdAtEpochSeconds = createdAtEpochSeconds,
            claimExpiresAtEpochSeconds = claimExpiresAt,
            serviceKeyId = serviceKeyId,
            artifacts = sealed.map {
                PendingPublishCrypto.ArtifactDeclaration(
                    it.blobId, it.kind, it.ordinal,
                    it.ciphertext.size.toLong(), it.ciphertextSha256,
                )
            },
        )
        val signature = PendingPublishCrypto.signStatement(signingHandle, statement)
        val statementSha = PendingPublishCrypto.statementSha256(statement)
        val escrow = PendingPublishCrypto.buildEscrow(
            capsuleId = capsuleId,
            deliveryTargetId = deliveryTargetId,
            senderUserId = senderUserId,
            statementSha256 = statementSha,
            addressCommitment = commitment,
            keyCommitment = keyCommitment,
            claimExpiresAtEpochSeconds = claimExpiresAt,
            capsuleAeadKeyset = keyBytes,
            addressOpeningCanonical = opening,
        )
        return Publication(
            salt32.copyOf(), claimExpiresAt, sealed,
            statement, signature, escrow,
        )
    }

    private const val RECOGNITION_BLOB_BYTE: Int = 0x01
    private const val CONTENT_BLOB_BYTE: Int = 0x02
    private const val NON_PHOTO_ORDINAL: Int = -1
}
