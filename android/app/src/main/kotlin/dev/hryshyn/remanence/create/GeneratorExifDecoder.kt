package dev.hryshyn.remanence.create

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * G4B production [GeneratorSourceBinding.PhotoDecoderPort]: real EXIF-upright
 * dimension reader (C2 container wiring per the HB.md production choices:
 * G4A store + `elapsedRealtime` clock + real EXIF decoder).
 *
 * Bounds-only: `inJustDecodeBounds` plus the EXIF orientation tag, so no
 * pixel buffer is ever allocated — safe against 32 MiB sources. Undecodable
 * bytes fail closed ([IllegalArgumentException], mapped to "decode failed"
 * by the binder). Unknown, undefined or out-of-range orientation markers
 * mean "no rotation recorded" (same clamping as the capture decoder); only
 * the four transpose/90/270 orientations swap width and height.
 *
 * Full-pixel EXIF application stays where it belongs: the trusted
 * normalizer decodes separately during binding. This port answers only the
 * identity-side question (upright dimensions for the G1 dim match).
 */
object GeneratorExifDecoder : GeneratorSourceBinding.PhotoDecoderPort {

    override suspend fun decodeUpright(jpeg: ByteArray): GeneratorSourceBinding.UprightPhoto {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType == null) {
            throw IllegalArgumentException("bytes are not a decodable image")
        }
        return if (swapsDimensions(readExifOrientation(jpeg))) {
            GeneratorSourceBinding.UprightPhoto(bounds.outHeight, bounds.outWidth)
        } else {
            GeneratorSourceBinding.UprightPhoto(bounds.outWidth, bounds.outHeight)
        }
    }

    private fun swapsDimensions(exifOrientation: Int): Boolean = when (exifOrientation) {
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE,
        ExifInterface.ORIENTATION_ROTATE_270,
        -> true
        else -> false
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
            if (raw >= ExifInterface.ORIENTATION_NORMAL && raw <= ExifInterface.ORIENTATION_ROTATE_270) {
                raw
            } else {
                ExifInterface.ORIENTATION_NORMAL
            }
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
}
