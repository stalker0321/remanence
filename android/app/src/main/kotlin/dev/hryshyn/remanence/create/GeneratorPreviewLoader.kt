package dev.hryshyn.remanence.create

import dev.hryshyn.remanence.core.crypto.readBoundedBytes
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Stage1-C preview source: one bounded, EXIF-upright original adapted
 * in-memory for on-screen preview only. [originalHash]/upright dims feed G1
 * identity ([originalHash] is over the ORIGINAL bytes); [previewJpegBytes] is
 * the upright, metadata-stripped, downscaled artifact used only for drawing.
 * No disk writes, no durable caching; the caller owns the bytes.
 */
data class LoadedPreviewSource(
    val originalHash: String,
    val uprightWidthPx: Int,
    val uprightHeightPx: Int,
    val previewJpegBytes: ByteArray,
    val previewWidthPx: Int,
    val previewHeightPx: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is LoadedPreviewSource &&
            originalHash == other.originalHash &&
            uprightWidthPx == other.uprightWidthPx &&
            uprightHeightPx == other.uprightHeightPx &&
            previewWidthPx == other.previewWidthPx &&
            previewHeightPx == other.previewHeightPx &&
            previewJpegBytes.contentEquals(other.previewJpegBytes)

    override fun hashCode(): Int {
        var result = originalHash.hashCode()
        result = 31 * result + uprightWidthPx
        result = 31 * result + uprightHeightPx
        result = 31 * result + previewWidthPx
        result = 31 * result + previewHeightPx
        result = 31 * result + previewJpegBytes.contentHashCode()
        return result
    }
}

/**
 * Loads one picker id into a bounded upright preview source, or `null` on any
 * failure (fail closed — a caller must never fabricate a placeholder bitmap).
 */
fun interface GeneratorPreviewLoader {
    suspend fun load(pickerId: String): LoadedPreviewSource?
}

/**
 * Host for an optional diagnostic original-bytes retain callback. The Create
 * session installs a generation-gated sink here; `null` (default) disables
 * retaining entirely and preserves existing behavior exactly.
 */
interface OriginalRetainSinkHost {
    var retainOriginal: ((bytes: ByteArray, hash: String) -> Unit)?
}

/**
 * Production loader: bounded original read ([PhotoStagingPipeline.MAX_SOURCE_BYTES]),
 * EXIF-upright dimension check, normalization for the on-screen JPEG, and the
 * original SHA-256 for G1 identity. Mirrors G4B's original-vs-derived split
 * without staging anything.
 */
class DefaultGeneratorPreviewLoader(
    private val openSource: (pickerId: String) -> PhotoSource,
    private val decoder: GeneratorSourceBinding.PhotoDecoderPort,
    private val normalizer: PhotoNormalizerPort,
    private val maxSourceBytes: Int = PhotoStagingPipeline.MAX_SOURCE_BYTES,
    /**
     * Diagnostic retain sink, invoked synchronously with the live ORIGINAL
     * bytes and their SHA-256 after the hash is computed and only once
     * cancellation is rechecked — never the derived preview bytes, never a
     * reopened URI. A throwing sink never breaks preview; stale (cancelled)
     * loads never reach it. Null disables retaining.
     *
     * The sink always runs on the ORIGINAL caller lane: worker stages below
     * compute off-caller, then control returns here before the sink fires,
     * so ViewModel state is never mutated from the worker.
     */
    override var retainOriginal: ((bytes: ByteArray, hash: String) -> Unit)? = null,
    /**
     * Lane for the read/decode/normalize/hash stages (production IO: the
     * stages mix MediaStore reads with JPEG decode work). Injectable so
     * hermetic tests can prove placement without real threads; the caller
     * lane (and its Job, for cancellation) is preserved across the hop.
     */
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GeneratorPreviewLoader, OriginalRetainSinkHost {

    /**
     * Worker-lane product of one load: the publishable preview plus the
     * live original bytes and hash reserved for the caller-lane sink.
     * Ownership transfers to the caller exactly once, on success only.
     */
    private data class PreparedOriginal(
        val preview: LoadedPreviewSource,
        val original: ByteArray,
        val hash: String,
    )

    /**
     * Cleanup owner spanning the whole withContext/caller retain
     * lifecycle. A cancelled caller continuation drops the withContext
     * result (F1: prompt cancellation on dispatcher return) and the
     * caller recheck can throw before any local cleanup (F2) — in both
     * cases the produced original must still be wiped, so every exit
     * path below runs through [wipe]. Volatile: the publish happens on
     * the worker while the wipe can run on the caller after a
     * resume-cancellation.
     */
    private class OriginalHandoff {
        @Volatile
        var prepared: PreparedOriginal? = null

        fun wipe() {
            prepared?.original?.fill(0)
            prepared = null
        }
    }

    override suspend fun load(pickerId: String): LoadedPreviewSource? {
        val handoff = OriginalHandoff()
        try {
            val preview = withContext(workerDispatcher) {
                val prepared = prepareOnWorker(pickerId) ?: return@withContext null
                handoff.prepared = prepared
                prepared.preview
            } ?: return null
            // Back on the caller lane: cancellation recheck, then the retain
            // callback. A cancel landing across the hop must not produce a
            // stale retain.
            currentCoroutineContext().ensureActive()
            val handed = handoff.prepared ?: return null
            retainOriginal?.let { sink ->
                try {
                    sink(handed.original, handed.hash)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Diagnostic only: a sink failure never breaks preview.
                }
            }
            return preview
        } finally {
            handoff.wipe()
        }
    }

    /**
     * Read/decode/normalize/hash on the worker lane. Exception and
     * cancellation semantics match the historical inline version exactly:
     * read/sink cancellation rethrows; decode/normalize failures (of any
     * type) fail closed to null. Partial originals are zeroized before any
     * failure return, including worker cancellation (finally always runs).
     */
    private suspend fun prepareOnWorker(pickerId: String): PreparedOriginal? {
        val original = try {
            openSource(pickerId).openInputStream().use { it.readBoundedBytes(maxSourceBytes) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        var prepared: PreparedOriginal? = null
        try {
            val upright = try {
                decoder.decodeUpright(original)
            } catch (_: Exception) {
                return null
            }
            if (upright.widthPx <= 0 || upright.heightPx <= 0) return null
            val normalized = try {
                normalizer.normalize(original)
            } catch (_: Exception) {
                return null
            }
            if (normalized.jpegBytes.isEmpty()) return null
            val hash = sha256Hex(original)
            prepared = PreparedOriginal(
                preview = LoadedPreviewSource(
                    originalHash = hash,
                    uprightWidthPx = upright.widthPx,
                    uprightHeightPx = upright.heightPx,
                    previewJpegBytes = normalized.jpegBytes,
                    previewWidthPx = normalized.width,
                    previewHeightPx = normalized.height,
                ),
                original = original,
                hash = hash,
            )
            return prepared
        } finally {
            if (prepared == null) {
                original.fill(0)
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
