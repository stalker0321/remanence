package app.postmark.memory.create

import java.io.File
import java.io.InputStream

/** One successfully staged normalized photo inside the current create session. */
data class StagedPhoto(
    val file: File,
    val width: Int,
    val height: Int,
)

/** Port over [PhotoNormalizer][postmark.core.recognition.PhotoNormalizer]. */
fun interface PhotoNormalizerPort {
    fun normalize(inputJpeg: ByteArray): NormalizedPhotoDto
}

/** Decoupled transport shape so this package never imports image internals. */
data class NormalizedPhotoDto(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
)

/**
 * Lazy handle to one picked photo (file/content-URI backed). Opening must be
 * cheap and deferred: [PhotoStagingPipeline] holds at most one open source at
 * any moment and closes it before advancing to the next photo.
 */
fun interface PhotoSource {
    fun openInputStream(): InputStream
}

/**
 * Bounded sequential staging of picked photos (docs/architecture.md section 9
 * step 8): every photo is read from its lazy source one at a time (never two
 * sources open at once), normalized, written atomically to the session staging
 * directory, and released before the next source is touched. Any mid-way
 * failure removes every artifact created by THIS invocation before propagating;
 * plaintext source bytes are held only for the active photo.
 */
class PhotoStagingPipeline(
    private val stagingDirectory: File,
    private val normalizer: PhotoNormalizerPort,
) {

    fun stageAll(sourcePhotos: List<PhotoSource>): List<StagedPhoto> {
        if (sourcePhotos.size !in MIN_PHOTOS..MAX_PHOTOS) {
            throw IllegalArgumentException(
                "exactly $MIN_PHOTOS..$MAX_PHOTOS photos required, got ${sourcePhotos.size}",
            )
        }
        stagingDirectory.mkdirs()
        val created = ArrayList<File>(sourcePhotos.size)
        try {
            return sourcePhotos.mapIndexed { index, source ->
                val jpeg = source.openInputStream().use(InputStream::readBytes)
                val normalized = normalizer.normalize(jpeg)
                val target = File(stagingDirectory, "photo-%02d.jpg".format(index))
                atomicWrite(target, normalized.jpegBytes)
                created += target
                StagedPhoto(target, normalized.width, normalized.height)
            }
        } catch (failure: Exception) {
            created.forEach { it.delete() }
            throw failure
        }
    }

    /** Removes every staged artifact; safe to call when nothing was staged. */
    fun clearStaged() {
        stagingDirectory.listFiles()?.forEach { it.delete() }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temporary = File(stagingDirectory, "${target.name}.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IllegalStateException("could not persist ${target.name}")
        }
    }

    companion object {
        const val MIN_PHOTOS: Int = 3
        const val MAX_PHOTOS: Int = 5
    }
}
