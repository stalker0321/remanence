package postmark.core.crypto

/**
 * Encrypts the staged photos strictly one at a time, in ascending ordinal
 * order, keeping only the running result list alive (docs/security.md
 * section 6.2). Any failure discards the partial result before propagating,
 * so callers can never observe or persist a half-encrypted set.
 */
class SequentialPhotoEncryptionBatch(
    private val encryptor: PhotoArtifactEncryptor,
) {

    /** Progress hook invoked after each successful artifact; never called again after a failure. */
    fun encryptInOrder(
        capsuleKeyset: com.google.crypto.tink.KeysetHandle,
        routingContext: RecognitionManifestCodec.RoutingContext,
        photos: List<OrdinalPhoto>,
        onEachEncrypted: (index: Int, total: Int) -> Unit = { _, _ -> },
    ): List<EncryptedPhoto> {
        if (photos.size !in MIN_PHOTOS..MAX_PHOTOS) {
            throw IllegalArgumentException("exactly $MIN_PHOTOS..$MAX_PHOTOS photos required")
        }
        photos.forEachIndexed { index, photo ->
            if (photo.ordinal != index) {
                throw IllegalArgumentException("ordinals must be sequential from 0")
            }
        }

        val results = ArrayList<EncryptedPhoto>(photos.size)
        try {
            photos.forEachIndexed { index, photo ->
                // Only the current plaintext is reachable inside this iteration;
                // the reference is dropped when the loop advances.
                val encrypted = encryptor.encryptPhoto(capsuleKeyset, routingContext, photo.ordinal, photo.normalizedJpeg)
                results += encrypted
                onEachEncrypted(index, photos.size)
            }
            return results
        } catch (failure: Exception) {
            results.clear()
            throw failure
        }
    }

    companion object {
        const val MIN_PHOTOS: Int = 3
        const val MAX_PHOTOS: Int = 5
    }
}

/** One queued photo for batch encryption: its fixed ordinal and normalized bytes. */
data class OrdinalPhoto(
    val ordinal: Int,
    val normalizedJpeg: ByteArray,
)
