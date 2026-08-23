package postmark.core.recognition

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Normalizes one picked photo to the v1 protocol limits: applies EXIF
 * orientation, strips all metadata by re-encoding from pixels only, resizes
 * the long edge to the protocol maximum, and steps compression quality down
 * until the encoded size fits the plaintext budget (docs/protocol.md section 12).
 */
class PhotoNormalizer(
    private val maxLongEdgePx: Int = DEFAULT_MAX_LONG_EDGE_PX,
    private val maxPlaintextBytes: Int = DEFAULT_MAX_PLAINTEXT_BYTES,
) {

    data class NormalizedPhoto(
        val jpegBytes: ByteArray,
        val width: Int,
        val height: Int,
        val finalQuality: Int,
    )

    init {
        require(maxLongEdgePx > 0 && maxPlaintextBytes > 0)
    }

    fun normalize(inputJpeg: ByteArray): NormalizedPhoto {
        val decoder = CaptureDecoder(maxWorkingEdgePx = maxLongEdgePx)
        val decoded = try {
            decoder.decode(inputJpeg)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("input is not a decodable photo")
        }

        // Long edge is already <= maxLongEdgePx thanks to power-of-two sampling;
        // exact-fit resize brings e.g. 1281..2559 edges up/down precisely when needed.
        val needsResize = decoded.bitmap.width > maxLongEdgePx || decoded.bitmap.height > maxLongEdgePx ||
            maxOf(decoded.bitmap.width, decoded.bitmap.height) < maxLongEdgePx / 2
        val working: Bitmap = if (needsResize) {
            val scale = maxLongEdgePx.toDouble() / maxOf(decoded.bitmap.width, decoded.bitmap.height)
            val targetW = kotlin.math.round(decoded.bitmap.width * scale).toInt().coerceAtLeast(1)
            val targetH = kotlin.math.round(decoded.bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded.bitmap, targetW, targetH, true)
        } else {
            decoded.bitmap
        }

        try {
            var qualityIndex = QUALITY_LADDER.first()
            var bytes = encode(working, qualityIndex)
            while (bytes.size > maxPlaintextBytes && qualityIndex < QUALITY_LADDER.last()) {
                qualityIndex = QUALITY_LADDER[QUALITY_LADDER.indexOf(qualityIndex) + 1]
                bytes = encode(working, qualityIndex)
            }
            if (bytes.size > maxPlaintextBytes) {
                throw IllegalArgumentException("photo cannot fit plaintext size budget")
            }
            return NormalizedPhoto(
                jpegBytes = bytes,
                width = working.width,
                height = working.height,
                finalQuality = qualityIndex,
            )
        } finally {
            if (working != decoded.bitmap) working.recycle()
            decoded.bitmap.recycle()
        }
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "jpeg encode failed" }
        return out.toByteArray()
    }

    companion object {
        /** Protocol v1: max long edge 2560 px, max plaintext 8 MiB. */
        const val DEFAULT_MAX_LONG_EDGE_PX: Int = 2560
        const val DEFAULT_MAX_PLAINTEXT_BYTES: Int = 8 * 1024 * 1024

        internal val QUALITY_LADDER = intArrayOf(90, 80, 70, 55, 40)
    }
}

/** True when [jpegBytes] still contain an EXIF APP1 segment (metadata not stripped). */
fun containsExifSegment(jpegBytes: ByteArray): Boolean {
    val needle = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00) // "Exif\0"
    var match = 0
    for (byte in jpegBytes) {
        match = if (byte == needle[match]) match + 1 else 0
        if (match == needle.size) return true
    }
    return false
}
