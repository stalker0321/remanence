package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.CapsulePhotoIdentity
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
            val blobId = CapsulePhotoIdentity.photoBlobId(capsule, index).toRestString()
            val ciphertext = PendingArtifactCryptor.encrypt(
                capsuleKeyset, capsuleId, blobId, 3, index,
                senderUserId, deliveryTargetId, jpeg,
            )
            SealedArtifact(
                blobId, 3, index, ciphertext,
                MessageDigest.getInstance("SHA-256").digest(ciphertext),
            )
        }
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
}
