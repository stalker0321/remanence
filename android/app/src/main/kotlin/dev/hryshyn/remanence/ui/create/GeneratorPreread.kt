package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.create.GeneratorExifDecoder
import dev.hryshyn.remanence.create.GeneratorSourceBinding
import dev.hryshyn.remanence.create.PhotoSource
import dev.hryshyn.remanence.create.PhotoStagingPipeline
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** One owned original-byte preread used only for publish admission. */
internal data class PrereadOriginal(
    val bytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
)

/**
 * Caller-owned preread custody. It is allocated before the dispatcher hop so
 * prompt cancellation while returning from `withContext` cannot discard the
 * only references to original bytes.
 */
internal class GeneratorPrereadOwner(
    private val ownedObserver: (ByteArray) -> Unit = {},
) {
    private val completed = ArrayList<PrereadOriginal>()
    private var current: ByteArray? = null

    @Synchronized
    fun ownCurrent(bytes: ByteArray) {
        current = bytes
        ownedObserver(bytes)
    }

    @Synchronized
    fun completeCurrent(bytes: ByteArray, widthPx: Int, heightPx: Int) {
        check(current === bytes) { "completed preread is not the current owned buffer" }
        completed += PrereadOriginal(bytes, widthPx, heightPx)
        current = null
    }

    @Synchronized
    fun originals(): List<PrereadOriginal> = completed.toList()

    @Synchronized
    fun wipe() {
        current?.fill(0)
        current = null
        completed.forEach { it.bytes.fill(0) }
        completed.clear()
    }
}

/**
 * Reads originals in authored order while retaining ownership of every byte
 * array as soon as it is allocated. A partial open/read/decode failure wipes
 * both completed buffers and the current decode buffer; cancellation does the
 * same before propagating. A successful caller owns the returned arrays and
 * must wipe them in its `finally` block.
 *
 * [ownedObserver] and [decoder] are narrow deterministic test seams. The
 * production defaults preserve the real EXIF decoder and observe nothing.
 */
internal suspend fun prereadGeneratorOriginals(
    photoIds: List<String>,
    openPhotoSource: (String) -> PhotoSource,
    owner: GeneratorPrereadOwner,
    decoder: suspend (ByteArray) -> GeneratorSourceBinding.UprightPhoto = GeneratorExifDecoder::decodeUpright,
    limitBytes: Int = PhotoStagingPipeline.MAX_SOURCE_BYTES,
): Boolean {
    try {
        for (pickerId in photoIds) {
            require(limitBytes >= 0) { "negative plaintext budget: $limitBytes" }
            val scratch = ByteArray(limitBytes)
            owner.ownCurrent(scratch)
            val size = openPhotoSource(pickerId).openInputStream().use { stream ->
                var total = 0
                while (total < limitBytes) {
                    val read = stream.read(scratch, total, minOf(64 * 1024, limitBytes - total))
                    if (read < 0) break
                    if (read == 0) throw IOException("hostile stream returned a zero-length read")
                    total += read
                }
                if (total == limitBytes && stream.read() >= 0) {
                    throw IllegalArgumentException("source exceeds $limitBytes-byte plaintext budget")
                }
                total
            }
            val bytes = scratch.copyOf(size)
            scratch.fill(0)
            owner.ownCurrent(bytes)
            val upright = decoder(bytes)
            owner.completeCurrent(bytes, upright.widthPx, upright.heightPx)
        }
        return true
    } catch (cancelled: CancellationException) {
        owner.wipe()
        throw cancelled
    } catch (_: Exception) {
        owner.wipe()
        return false
    }
}
