package dev.hryshyn.remanence.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Maximum dimension (width or height) of the in-memory captured-still display frame. */
const val CAPTURE_DISPLAY_MAX_DIMENSION = 480

/**
 * Decodes a bounded display frame from captured JPEG bytes. Replaceable in
 * tests; production uses [decodeCaptureDisplayStill].
 */
fun interface CaptureDisplayDecoder {
    fun decode(jpegBytes: ByteArray): Bitmap?
}

/**
 * Derives a small in-memory frame from the captured JPEG for display while
 * the pipeline runs. The full-resolution bytes are never held for display:
 * the frame is subsampled during decode (RGB_565, capped at
 * [CAPTURE_DISPLAY_MAX_DIMENSION]) and this function returns null for
 * anything undecodable instead of throwing. Callers must still zeroize the
 * source bytes on their existing path; the returned frame is a separate
 * buffer with its own [clearCaptureDisplayStill] lifecycle.
 */
fun decodeCaptureDisplayStill(
    jpegBytes: ByteArray,
    maxDimension: Int = CAPTURE_DISPLAY_MAX_DIMENSION,
): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
    val srcWidth = bounds.outWidth
    val srcHeight = bounds.outHeight
    require(srcWidth > 0 && srcHeight > 0) { "undecodable still" }
    bounds.inJustDecodeBounds = false
    bounds.inSampleSize = captureDisplaySampleSize(srcWidth, srcHeight, maxDimension)
    bounds.inPreferredConfig = Bitmap.Config.RGB_565
    BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        ?: error("undecodable still")
}.getOrNull()

/**
 * Power-of-two subsample so the longer side fits [maxDimension]. Pure math,
 * pinned by JVM unit tests.
 */
internal fun captureDisplaySampleSize(srcWidth: Int, srcHeight: Int, maxDimension: Int): Int {
    require(srcWidth > 0 && srcHeight > 0) { "source dimensions must be positive" }
    require(maxDimension > 0) { "max dimension must be positive" }
    var sampleSize = 1
    while (maxOf(srcWidth, srcHeight) / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

/**
 * Zeroizes the display frame's pixels and drops the reference. Never
 * recycles: the frame may still be composed on screen while a terminal
 * transition lands, and drawing a recycled bitmap crashes — eraseColor is
 * safe there, and the collector reclaims the buffer once unreferenced.
 */
fun clearCaptureDisplayStill(still: Bitmap?) {
    if (still == null || still.isRecycled) return
    runCatching { still.eraseColor(android.graphics.Color.TRANSPARENT) }
}
