package dev.hryshyn.remanence.ui.collage.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * EXIF-upright decoder for the collage field stage. It turns source JPEG
 * bytes into the [FieldStageImage] the stage owns: the EXIF-upright original,
 * symmetric with the oracle `ImageOps.exif_transpose(Image.open(src))
 * .convert("RGB")`, optionally subsampled for installed-app analysis.
 *
 * Boundaries, mirroring [dev.hryshyn.remanence.create.GeneratorExifDecoder]:
 * sources larger than [MAX_SOURCE_BYTES] (32 MiB, slice B5) fail closed;
 * undecodable bytes fail closed ([IllegalArgumentException]); unknown,
 * undefined, or out-of-range orientation markers mean "no rotation recorded".
 * Only the four transpose/90/270 orientations swap width and height. The 2560
 * re-encoded normalized/preview bytes are a different, unproven analysis
 * contract version and must never be passed here.
 */
object FieldUprightDecoder {

    const val MAX_SOURCE_BYTES = 32L * 1024 * 1024

    /**
     * Long-edge bound for installed-app analysis. A full-resolution decode of a
     * 50 MP photo needs ~200 MB per ARGB copy and the decode holds three
     * copies, which crashed Create; the detectors downscale far below this.
     */
    const val ANALYSIS_MAX_LONG_EDGE_PX = 3072

    fun decodeForAnalysis(jpeg: ByteArray): FieldStageImage = decodeUpright(jpeg, ANALYSIS_MAX_LONG_EDGE_PX)

    /**
     * Decodes at full resolution by default (the oracle probes rely on it).
     * With [maxLongEdgePx] the pixels are power-of-two subsampled until the
     * long edge fits, while the image still reports the original upright size.
     */
    fun decodeUpright(jpeg: ByteArray, maxLongEdgePx: Int = Int.MAX_VALUE): FieldStageImage {
        require(jpeg.size <= MAX_SOURCE_BYTES) { "source exceeds the 32 MiB bound" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType == null) {
            throw IllegalArgumentException("bytes are not a decodable image")
        }
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > maxLongEdgePx) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            ?: throw IllegalArgumentException("bytes are not a decodable image")
        try {
            val orientation = readExifOrientation(jpeg)
            val swapped = orientation in SWAPPING_ORIENTATIONS
            val upright = transpose(decoded, orientation)
            try {
                val width = upright.width
                val height = upright.height
                val argb = IntArray(width * height)
                upright.getPixels(argb, 0, width, 0, 0, width, height)
                return FieldStageImage(
                    width,
                    height,
                    argb,
                    sourceWidth = maxOf(width, if (swapped) bounds.outHeight else bounds.outWidth),
                    sourceHeight = maxOf(height, if (swapped) bounds.outWidth else bounds.outHeight),
                )
            } finally {
                if (upright !== decoded) runCatching { upright.recycle() }
            }
        } finally {
            runCatching { decoded.recycle() }
        }
    }

    private val SWAPPING_ORIENTATIONS = setOf(
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_TRANSVERSE,
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_ROTATE_270,
    )

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
