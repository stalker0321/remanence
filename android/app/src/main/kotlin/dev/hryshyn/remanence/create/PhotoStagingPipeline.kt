package dev.hryshyn.remanence.create

import dev.hryshyn.remanence.core.crypto.PhotoArtifactEncryptor
import dev.hryshyn.remanence.core.crypto.readBoundedBytes
import java.io.File
import java.io.InputStream

/** One successfully staged normalized photo inside the current create session. */
data class StagedPhoto(
    val file: File,
    val width: Int,
    val height: Int,
)

/**
 * Port over [PhotoNormalizer][dev.hryshyn.remanence.core.recognition.PhotoNormalizer].
 * FIX-STATE-03: suspending so implementations can hop to a CPU dispatcher
 * instead of blocking Main; non-suspending lambdas still conform.
 */
fun interface PhotoNormalizerPort {
    suspend fun normalize(inputJpeg: ByteArray): NormalizedPhotoDto
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
 * any moment and closes it before advancing to the next photo. The selected
 * source may be larger than the protocol limit because it has not been
 * resized or recompressed yet.
 */
fun interface PhotoSource {
    fun openInputStream(): InputStream
}

/**
 * Bounded sequential staging of picked photos (docs/architecture.md section 9
 * step 8): every photo is read from its lazy source one at a time (never two
 * sources open at once) through a bounded read capped at [MAX_SOURCE_BYTES],
 * normalized to the smaller protocol photo plaintext budget
 * ([PhotoArtifactEncryptor.MAX_PLAINTEXT_BYTES]),
 * written atomically to the session staging directory, and released before the
 * next source is touched. Staging refuses to start while the staging directory
 * holds any pre-existing artifact, so leftovers from an earlier session can
 * never be overwritten or removed by this invocation. Any mid-way failure
 * removes every artifact created by THIS invocation before propagating and
 * guarantees no plaintext `.tmp` survives, not even a partially written one;
 * plaintext source bytes are held only for the active photo.
 */
class PhotoStagingPipeline(
    private val stagingDirectory: File,
    private val normalizer: PhotoNormalizerPort,
) {

    suspend fun stageAll(sourcePhotos: List<PhotoSource>): List<StagedPhoto> {
        if (sourcePhotos.size !in MIN_PHOTOS..MAX_PHOTOS) {
            throw IllegalArgumentException(
                "exactly $MIN_PHOTOS..$MAX_PHOTOS photos required, got ${sourcePhotos.size}",
            )
        }
        if (!stagingDirectory.isDirectory && !stagingDirectory.mkdirs()) {
            throw IllegalStateException("cannot prepare staging directory $stagingDirectory")
        }
        val preExisting = stagingDirectory.listFiles()
        if (!preExisting.isNullOrEmpty()) {
            throw IllegalStateException(
                "staging directory must be empty before staging; found ${preExisting.size} pre-existing artifacts",
            )
        }
        val created = ArrayList<File>(sourcePhotos.size)
        try {
            return sourcePhotos.mapIndexed { index, source ->
                val jpeg = source.openInputStream().use {
                    it.readBoundedBytes(MAX_SOURCE_BYTES)
                }
                val normalized = normalizer.normalize(jpeg)
                require(normalized.jpegBytes.isNotEmpty()) { "normalized photo is empty" }
                require(normalized.jpegBytes.size <= PhotoArtifactEncryptor.MAX_PLAINTEXT_BYTES) {
                    "normalized photo exceeds plaintext budget"
                }
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

    /**
     * Writes [bytes] so that no plaintext temporary ever outlives the call:
     * the `.tmp` is deleted in `finally` whether the write fails midway, the
     * rename fails, or both. Internal only so tests can drive the rename-
     * failure path deterministically.
     */
    internal fun atomicWrite(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                throw IllegalStateException("could not persist ${target.name}")
            }
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val MIN_PHOTOS: Int = 3
        const val MAX_PHOTOS: Int = 5
        const val MAX_SOURCE_BYTES: Int = 32 * 1024 * 1024
    }
}
