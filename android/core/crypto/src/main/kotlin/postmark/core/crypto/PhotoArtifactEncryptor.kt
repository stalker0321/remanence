package postmark.core.crypto

import java.security.GeneralSecurityException
import java.security.MessageDigest
import postmark.core.model.ArtifactAadInput

/** One encrypted photo artifact plus its transport binding. */
data class EncryptedPhoto(
    val ciphertext: ByteArray,
    val sizeBytes: Long,
    val ciphertextSha256: ByteArray,
)

/**
 * Encrypts a single normalized photo under the capsule keyset with its
 * PHOTO-ordinal canonical AAD and computes the SHA-256 transport binding
 * that the publish statement later pins (docs/protocol.md sections 7 and 12).
 */
class PhotoArtifactEncryptor {

    fun encryptPhoto(
        capsuleKeyset: com.google.crypto.tink.KeysetHandle,
        routingContext: RecognitionManifestCodec.RoutingContext,
        ordinal: Int,
        normalizedJpeg: ByteArray,
    ): EncryptedPhoto {
        if (normalizedJpeg.isEmpty()) throw IllegalArgumentException("photo bytes are empty")
        if (normalizedJpeg.size > MAX_PLAINTEXT_BYTES) {
            throw IllegalArgumentException("photo exceeds plaintext budget")
        }
        val context = ArtifactAadInput(
            capsuleId = routingContext.capsuleId,
            blobId = routingContext.blobId,
            artifactKind = postmark.core.model.CapsuleArtifactKind.PHOTO,
            ordinal = ordinal,
            senderUserId = routingContext.senderUserId,
            recipientUserId = routingContext.recipientUserId,
        )
        val ciphertext = try {
            CapsuleArtifactCryptor().encrypt(capsuleKeyset, context, normalizedJpeg)
        } catch (failure: GeneralSecurityException) {
            throw GeneralSecurityException("photo encryption failed")
        }
        return EncryptedPhoto(
            ciphertext = ciphertext,
            sizeBytes = ciphertext.size.toLong(),
            ciphertextSha256 = sha256(ciphertext),
        )
    }

    fun decryptPhoto(
        capsuleKeyset: com.google.crypto.tink.KeysetHandle,
        routingContext: RecognitionManifestCodec.RoutingContext,
        ordinal: Int,
        encrypted: EncryptedPhoto,
    ): ByteArray {
        val context = ArtifactAadInput(
            capsuleId = routingContext.capsuleId,
            blobId = routingContext.blobId,
            artifactKind = postmark.core.model.CapsuleArtifactKind.PHOTO,
            ordinal = ordinal,
            senderUserId = routingContext.senderUserId,
            recipientUserId = routingContext.recipientUserId,
        )
        return CapsuleArtifactCryptor().decrypt(capsuleKeyset, context, encrypted.ciphertext)
    }

    internal fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    companion object {
        const val MAX_PLAINTEXT_BYTES: Int = 8 * 1024 * 1024
    }
}
