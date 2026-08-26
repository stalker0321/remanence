package dev.hryshyn.remanence.core.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Bounded still-capture decoder: decodes a JPEG at reduced sample resolution,
 * then applies the EXIF orientation so downstream normalization always sees
 * an upright image. Raw capture bytes never leave the caller's process and
 * are not cached here.
 */
class CaptureDecoder(
    private val maxWorkingEdgePx: Int,
) {

    data class Decoded(
        val bitmap: Bitmap,
        val exifOrientation: Int,
        val appliedSampleSize: Int,
    )

    init {
        require(maxWorkingEdgePx > 0) { "maxWorkingEdgePx must be positive" }
    }

    fun decode(jpegBytes: ByteArray): Decoded {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType == null) {
            throw IllegalArgumentException("bytes are not a decodable image")
        }
        val sampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
            ?: throw IllegalArgumentException("image decode failed")
        val orientation = readExifOrientation(jpegBytes)
        val upright = applyOrientation(decoded, orientation)
        return Decoded(upright, orientation, sampleSize)
    }

    /** Power-of-two sampling that brings the long edge under the working bound without undershooting half of it. */
    internal fun computeSampleSize(width: Int, height: Int): Int {
        require(width > 0 && height > 0) { "invalid image dimensions" }
        val longEdge = maxOf(width, height)
        var sample = 1
        while (longEdge / (sample * 2) >= maxWorkingEdgePx) {
            sample *= 2
        }
        return sample
    }

    private fun readExifOrientation(jpegBytes: ByteArray): Int =
        try {
            val raw = ByteArrayInputStream(jpegBytes).use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
            // Undefined (0) or out-of-range markers mean "no rotation recorded".
            if (raw >= ExifInterface.ORIENTATION_NORMAL && raw <= ExifInterface.ORIENTATION_TRANSVERSE) {
                raw
            } else {
                ExifInterface.ORIENTATION_NORMAL
            }
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

    private fun applyOrientation(bitmap: Bitmap, exifOrientation: Int): Bitmap {
        val matrix = Matrix()
        when (exifOrientation) {
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { matrix.postRotate(180f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            else -> return bitmap
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
        if (oriented != bitmap) bitmap.recycle()
        return oriented
    }

    /** Encodes a solid bitmap to JPEG bytes; test fixture support lives beside the decoder. */
    companion object {
        fun encodeFixtureJpeg(bitmap: Bitmap, quality: Int = 95): ByteArray {
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "fixture encode failed" }
            return out.toByteArray()
        }
    }
}
