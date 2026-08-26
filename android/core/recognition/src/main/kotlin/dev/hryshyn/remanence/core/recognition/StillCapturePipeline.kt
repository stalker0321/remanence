package dev.hryshyn.remanence.core.recognition

import android.graphics.Bitmap

/**
 * Turns one deliberate CameraX still into the bounded upright working image
 * consumed by contour detection, quality gates, and perspective warping
 * (docs/recognition.md section 3 step 3). Exactly one raw capture is held per
 * call; every intermediate bitmap created along the way is recycled before
 * the call returns, including on failure, and the delivered working image is
 * released deterministically through [WorkingCapture.close].
 */
class StillCapturePipeline(
    private val maxWorkingEdgePx: Int = DEFAULT_WORKING_EDGE_PX,
    private val decoderProvider: () -> CaptureDecoder = { CaptureDecoder(maxWorkingEdgePx) },
) {

    init {
        require(maxWorkingEdgePx > 0) { "maxWorkingEdgePx must be positive" }
    }

    /**
     * Bounded upright working image plus the geometry later stages need.
     * Must be closed exactly once when the caller is done; closing twice is
     * harmless, using it after closing is not supported.
     */
    class WorkingCapture internal constructor(
        internal val bitmap: Bitmap,
        val exifOrientationApplied: Int,
    ) : AutoCloseable {

        val width: Int get() = bitmap.width
        val height: Int get() = bitmap.height

        /**
         * Copies the working image into ARGB pixels for detector/warper/meter
         * consumption. Fails closed after release; the bitmap itself is
         * recycled by [close].
         */
        fun copyArgbPixels(): IntArray {
            checkOpen()
            val target = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(target, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return target
        }

        private var closed = false

        override fun close() {
            if (!closed) {
                closed = true
                bitmap.recycle()
            }
        }

        internal fun checkOpen() {
            check(!closed) { "working capture already released" }
        }
    }

    /**
     * Decodes, orients, bounds, and hands back exactly one working image.
     * Throws [IllegalArgumentException] for non-decodable bytes without ever
     * exposing a partially prepared bitmap.
     */
    fun process(captureJpeg: ByteArray): WorkingCapture {
        val decoder = decoderProvider()
        val decoded = try {
            decoder.decode(captureJpeg)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("capture bytes are not a decodable still")
        }

        // Power-of-two sampling leaves the long edge within [bound/2, 2*bound);
        // an exact-fit downscale guarantees the documented working bound.
        val needsExactFit = maxOf(decoded.bitmap.width, decoded.bitmap.height) > maxWorkingEdgePx
        val working: Bitmap = if (needsExactFit) {
            try {
                val scale = maxWorkingEdgePx.toDouble() / maxOf(decoded.bitmap.width, decoded.bitmap.height)
                val targetW = kotlin.math.round(decoded.bitmap.width * scale).toInt().coerceAtLeast(1)
                val targetH = kotlin.math.round(decoded.bitmap.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded.bitmap, targetW, targetH, true)
            } finally {
                decoded.bitmap.recycle()
            }
        } else {
            decoded.bitmap
        }

        return WorkingCapture(working, decoded.exifOrientation)
    }

    companion object {
        /** Recognition canonical long edge; working images never exceed it. */
        const val DEFAULT_WORKING_EDGE_PX: Int = 1600
    }
}
