package dev.hryshyn.remanence.ui.collage.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * Non-production full-pixel EXIF-upright decoder for the A4b pixel field
 * stage. It turns source JPEG bytes into the [FieldStageImage] the stage
 * owns: the full-resolution EXIF-upright original, symmetric with the oracle
 * `ImageOps.exif_transpose(Image.open(src)).convert("RGB")`.
 *
 * Boundaries, mirroring [dev.hryshyn.remanence.create.GeneratorExifDecoder]:
 * sources larger than [MAX_SOURCE_BYTES] (32 MiB, slice B5) fail closed;
 * undecodable bytes fail closed ([IllegalArgumentException]); unknown,
 * undefined, or out-of-range orientation markers mean "no rotation recorded".
 * Only the four transpose/90/270 orientations swap width and height. The 2560
 * re-encoded normalized/preview bytes are a different, unproven analysis
 * contract version and must never be passed here.
 *
 * Never referenced by production code, `CollageCropSource`, or Create: only
 * the A4b diagnostic probe drives it.
 */
object FieldUprightDecoder {

    const val MAX_SOURCE_BYTES = 32L * 1024 * 1024

    fun decodeUpright(jpeg: ByteArray): FieldStageImage {
        require(jpeg.size <= MAX_SOURCE_BYTES) { "source exceeds the 32 MiB bound" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType == null) {
            throw IllegalArgumentException("bytes are not a decodable image")
        }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: throw IllegalArgumentException("bytes are not a decodable image")
        try {
            val upright = transpose(decoded, readExifOrientation(jpeg))
            try {
                val width = upright.width
                val height = upright.height
                val argb = IntArray(width * height)
                upright.getPixels(argb, 0, width, 0, 0, width, height)
                return FieldStageImage(width, height, argb)
            } finally {
                if (upright !== decoded) runCatching { upright.recycle() }
            }
        } finally {
            runCatching { decoded.recycle() }
        }
    }

    private fun transpose(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun readExifOrientation(jpeg: ByteArray): Int =
        try {
            val raw = ByteArrayInputStream(jpeg).use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
            if (raw >= ExifInterface.ORIENTATION_NORMAL && raw <= ExifInterface.ORIENTATION_ROTATE_270) {
                raw
            } else {
                ExifInterface.ORIENTATION_NORMAL
            }
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
}
