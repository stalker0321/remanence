package postmark.core.recognition

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assert.assertFalse as junitFalse
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoNormalizerTest {

    @org.junit.Before
    fun loadNative() {
        runCatching { System.loadLibrary("opencv_java4100") }
            .onFailure { error ->
                // Another Robolectric sandbox may have mapped the same .so.
                val alreadyLoaded = error is UnsatisfiedLinkError &&
                    error.message?.contains("already loaded") == true
                assumeTrue("desktop OpenCV natives unavailable: $error", alreadyLoaded)
            }
    }

    private fun noisyBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        var seed = 12345L
        for (y in 0 until height) {
            for (x in 0 until width) {
                seed = seed * 6364136223846793005L + 1442695040888963407L
                val v = ((seed ushr 33) and 0xFF).toInt()
                row[x] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        return bitmap
    }

    private fun jpegWithExif(bitmap: Bitmap, orientation: Int): ByteArray {
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out))
        val temp = File.createTempFile("photo-exif", ".jpg")
        try {
            temp.writeBytes(out.toByteArray())
            val exif = ExifInterface(temp.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
            return temp.readBytes()
        } finally {
            temp.delete()
        }
    }

    @org.junit.Test
    fun oversizedPhotoResizedToProtocolLongEdgeAndStripped() {
        val normalizer = PhotoNormalizer(maxLongEdgePx = 2560)
        val raw = jpegWithExif(noisyBitmap(3200, 2000), ExifInterface.ORIENTATION_NORMAL)
        assertTrue(containsExifSegment(raw), "fixture must carry EXIF before normalization")

        val normalized = normalizer.normalize(raw)

        assertEquals(2560, maxOf(normalized.width, normalized.height))
        assertEquals(1600, minOf(normalized.width, normalized.height))
        assertFalse(containsExifSegment(normalized.jpegBytes))
    }

    @org.junit.Test
    fun exifRotationIsAppliedBeforeResize() {
        val normalizer = PhotoNormalizer(maxLongEdgePx = 2560)
        // Portrait sensor data with ROTATE_90: stored 2000x3200 becomes upright 3200x2000.
        val raw = jpegWithExif(noisyBitmap(2000, 3200), ExifInterface.ORIENTATION_ROTATE_90)

        val normalized = normalizer.normalize(raw)

        assertEquals(2560, normalized.width)
        assertEquals(1600, normalized.height)
        assertFalse(containsExifSegment(normalized.jpegBytes))
    }

    @org.junit.Test
    fun qualityStepsDownUntilPlaintextBudgetFits() {
        // Robolectric's native graphics ignore the JPEG quality knob, so the
        // stepping logic is proven against a deterministic fake encoder:
        // size(quality) = 5_000 + quality * 1_000.
        val fakeEncoder: (Bitmap, Int) -> ByteArray = { _, quality ->
            ByteArray(5_000 + quality * 1_000)
        }
        val budget = 100_000 // q100=105_000 rejected; q90=95_000 fits.

        val normalizer = PhotoNormalizer(
            maxPlaintextBytes = budget,
            qualityLadder = intArrayOf(100, 90),
            encoderOverride = fakeEncoder,
        )
        val normalized = normalizer.normalize(jpegWithExif(noisyBitmap(320, 200), 1))

        assertTrue(normalized.jpegBytes.size <= budget)
        assertEquals(90, normalized.finalQuality)
    }

    @org.junit.Test
    fun impossibleBudgetFailsClosed() {
        val normalizer = PhotoNormalizer(
            maxPlaintextBytes = 16,
            qualityLadder = intArrayOf(50),
            encoderOverride = { _, _ -> ByteArray(999_999) },
        )
        assertFailsWith<IllegalArgumentException> {
            normalizer.normalize(jpegWithExif(noisyBitmap(64, 48), 1))
        }
    }

    @org.junit.Test
    fun smallCleanPhotoIsNeverUpscaled() {
        val normalizer = PhotoNormalizer(encoderOverride = { _, q -> ByteArray(q) })
        val normalized = normalizer.normalize(jpegWithExif(noisyBitmap(800, 600), 1))
        assertEquals(90, normalized.finalQuality)
        assertEquals(800, normalized.width)
        assertEquals(600, normalized.height)
    }

    @org.junit.Test
    fun alreadySmallCleanPhotoPassesThroughAtTopQuality() {
        val normalizer = PhotoNormalizer()
        val normalized = normalizer.normalize(jpegWithExif(noisyBitmap(800, 600), 1))
        assertEquals(90, normalized.finalQuality)
        assertEquals(800, normalized.width)
        assertEquals(600, normalized.height)
    }

    @org.junit.Test
    fun nonImageInputRejected() {
        assertFailsWith<IllegalArgumentException> {
            PhotoNormalizer().normalize("definitely not a photo".toByteArray())
        }
    }
}

private fun assertFalse(actual: Boolean) = org.junit.Assert.assertFalse(actual)
