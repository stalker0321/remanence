package postmark.core.crypto

import java.io.InputStream

/**
 * Encrypts the staged photos strictly one at a time, in ascending ordinal
 * order (docs/security.md sections 6.1/6.2). Inputs are lazy descriptors of
 * staged normalized photos: at any moment exactly zero or one source is open
 * and only the active plaintext plus the running ciphertext results are kept
 * alive; each source is closed before the next photo is touched. Any failure
 * discards the partial result before propagating, so callers can never
 * observe or persist a half-encrypted set, and no plaintext is ever returned.
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
                // Exactly one staged source is open here; use{} closes it before
                // the loop advances to the next descriptor.
                val normalizedJpeg = photo.plaintext.openStream().use(InputStream::readBytes)
                val encrypted = encryptor.encryptPhoto(capsuleKeyset, routingContext, photo.ordinal, normalizedJpeg)
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

/** Lazy handle to one staged normalized photo's bytes; opened at most one at a time. */
fun interface PhotoPlaintextSource {
    fun openStream(): InputStream
}

/** One queued photo for batch encryption: its fixed ordinal and lazy staged-bytes descriptor. */
data class OrdinalPhoto(
    val ordinal: Int,
    val plaintext: PhotoPlaintextSource,
)
