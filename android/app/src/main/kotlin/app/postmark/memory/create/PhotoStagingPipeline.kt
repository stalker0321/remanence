package app.postmark.memory.create

import java.io.File

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
 * Bounded sequential staging of picked photos (docs/architecture.md section 9
 * step 8): every photo is normalized one at a time, written atomically to the
 * session staging directory, and any mid-way failure removes every artifact
 * created by THIS invocation before propagating.
 */
class PhotoStagingPipeline(
    private val stagingDirectory: File,
    private val normalizer: PhotoNormalizerPort,
) {

    fun stageAll(sourcePhotos: List<ByteArray>): List<StagedPhoto> {
        if (sourcePhotos.size !in MIN_PHOTOS..MAX_PHOTOS) {
            throw IllegalArgumentException(
                "exactly $MIN_PHOTOS..$MAX_PHOTOS photos required, got ${sourcePhotos.size}",
            )
        }
        stagingDirectory.mkdirs()
        val created = ArrayList<File>(sourcePhotos.size)
        try {
            return sourcePhotos.mapIndexed { index, jpeg ->
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
