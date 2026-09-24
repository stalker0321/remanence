package dev.hryshyn.remanence.create

import dev.hryshyn.remanence.core.crypto.readBoundedBytes
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

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
) : GeneratorPreviewLoader {

    override suspend fun load(pickerId: String): LoadedPreviewSource? {
        val original = try {
            openSource(pickerId).openInputStream().use { it.readBoundedBytes(maxSourceBytes) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
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
            return LoadedPreviewSource(
                originalHash = sha256Hex(original),
                uprightWidthPx = upright.widthPx,
                uprightHeightPx = upright.heightPx,
                previewJpegBytes = normalized.jpegBytes,
                previewWidthPx = normalized.width,
                previewHeightPx = normalized.height,
            )
        } finally {
            original.fill(0)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
