package dev.hryshyn.remanence.core.recognition

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class CaptureDecoderSampleSizeTest {

    @Test
    fun smallImagesStayAtOne() {
        val decoder = CaptureDecoder(maxWorkingEdgePx = 1600)
        assertEquals(1, decoder.computeSampleSize(1200, 800))
    }

    @Test
    fun oversizedImageHalvesUntilWithinBoundToTwiceBound() {
        val decoder = CaptureDecoder(maxWorkingEdgePx = 500)
        // Resulting long edge stays within [bound, 2*bound): 2000/4 = 500.
        assertEquals(4, decoder.computeSampleSize(2000, 1000))
        assertEquals(8, decoder.computeSampleSize(4000, 100))
        assertEquals(1, decoder.computeSampleSize(999, 10))
    }

    @Test
    fun invalidDimensionsRejected() {
        val decoder = CaptureDecoder(maxWorkingEdgePx = 500)
        assertFailsWith<IllegalArgumentException> { decoder.computeSampleSize(0, 10) }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class CaptureDecoderTest {
    private fun assertEq(expected: Any?, actual: Any?) = org.junit.Assert.assertEquals(expected, actual)


    private val decoder = CaptureDecoder(maxWorkingEdgePx = 1600)

    private fun solidBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(0xFF3366AA.toInt())
        }

    private fun jpegWithOrientation(bitmap: Bitmap, orientation: Int?): ByteArray {
        val jpeg = CaptureDecoder.encodeFixtureJpeg(bitmap)
        if (orientation == null) return jpeg
        require(orientation in setOf(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
        ))
        // Minimal little-endian EXIF APP1 with one SHORT orientation tag.
        // Keeping this in memory makes all eight orientation cases deterministic
        // across Robolectric and device ExifInterface implementations.
        val exif = byteArrayOf(
            0x45, 0x78, 0x69, 0x66, 0x00, 0x00, // Exif\0\0
            0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, // TIFF header
            0x01, 0x00, // one IFD entry
            0x12, 0x01, 0x03, 0x00, // orientation, SHORT
            0x01, 0x00, 0x00, 0x00, // one value
            orientation.toByte(), 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, // no next IFD
        )
        val app1 = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(),
            0x00, (exif.size + 2).toByte(),
        ) + exif
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    @org.junit.Test
    fun uprightFixtureDecodesWithoutSwap() {
        val decoded = decoder.decode(jpegWithOrientation(solidBitmap(64, 32), null))
        org.junit.Assert.assertEquals("width", 64, decoded.bitmap.width)
        org.junit.Assert.assertEquals("height", 32, decoded.bitmap.height)
        org.junit.Assert.assertEquals("orientation", ExifInterface.ORIENTATION_NORMAL, decoded.exifOrientation)
        org.junit.Assert.assertEquals("sample", 1, decoded.appliedSampleSize)
    }

    @org.junit.Test
    fun exifRotate90SwapsOutputDimensions() {
        val decoded = decoder.decode(
            jpegWithOrientation(solidBitmap(64, 32), ExifInterface.ORIENTATION_ROTATE_90),
        )
        assertEquals(32, decoded.bitmap.width)
        assertEquals(64, decoded.bitmap.height)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, decoded.exifOrientation)
    }

    @org.junit.Test
    fun exifRotate180KeepsDimensionsUpright() {
        val decoded = decoder.decode(
            jpegWithOrientation(solidBitmap(64, 32), ExifInterface.ORIENTATION_ROTATE_180),
        )
        assertEq(64, decoded.bitmap.width)
        assertEq(32, decoded.bitmap.height)
    }

    @org.junit.Test
    fun nonSymmetricExifOrientationsAreAppliedExactlyOnce() {
        val orientations = listOf(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
        )
        orientations.forEach { orientation ->
            val source = asymmetricBitmap()
            try {
                val decoded = decoder.decode(jpegWithOrientation(source, orientation))
                org.junit.Assert.assertEquals(
                    "metadata for orientation=$orientation",
                    orientation,
                    decoded.exifOrientation,
                )
                val expectedWidth = if (orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                    orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                    orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
                    orientation == ExifInterface.ORIENTATION_ROTATE_270
                ) {
                    source.height
                } else {
                    source.width
                }
                val expectedHeight = if (expectedWidth == source.width) source.height else source.width
                org.junit.Assert.assertEquals("width for orientation=$orientation", expectedWidth, decoded.bitmap.width)
                org.junit.Assert.assertEquals("height for orientation=$orientation", expectedHeight, decoded.bitmap.height)

                val samples = listOf(
                    decoded.bitmap.getPixel(decoded.bitmap.width / 4, decoded.bitmap.height / 4),
                    decoded.bitmap.getPixel(decoded.bitmap.width * 3 / 4, decoded.bitmap.height / 4),
                    decoded.bitmap.getPixel(decoded.bitmap.width / 4, decoded.bitmap.height * 3 / 4),
                    decoded.bitmap.getPixel(decoded.bitmap.width * 3 / 4, decoded.bitmap.height * 3 / 4),
                )
                expectedQuarterColors(orientation).zip(samples).forEach { (expected, actual) ->
                    assertColorNear("orientation=$orientation", expected, actual)
                }
            } finally {
                source.recycle()
            }
        }
    }

    @org.junit.Test
    fun oversizedCaptureIsDownsampledTowardWorkingBound() {
        val bounded = CaptureDecoder(maxWorkingEdgePx = 500)
        val decoded = bounded.decode(jpegWithOrientation(solidBitmap(2000, 1000), null))
        assertEq(4, decoded.appliedSampleSize)
        assertEq(500, decoded.bitmap.width)
        assertEq(250, decoded.bitmap.height)
    }

    @org.junit.Test
    fun nonImageBytesFailClosed() {
        assertFailsWith<IllegalArgumentException> { decoder.decode("not an image".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { decoder.decode(ByteArray(0)) }
    }

    @org.junit.Test
    fun fixtureJpegEncodingProducesDecodableBytes() {
        val bytes = CaptureDecoder.encodeFixtureJpeg(solidBitmap(48, 48))
        assertTrue(bytes.size > 100)
        val decoded = decoder.decode(bytes)
        assertEquals(48, decoded.bitmap.width)
    }

    private fun asymmetricBitmap(): Bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).also { bitmap ->
        val colors = listOf(
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt(),
            0xFF0000FF.toInt(),
            0xFFFFFF00.toInt(),
        )
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val quadrant = (if (x >= bitmap.width / 2) 1 else 0) +
                    (if (y >= bitmap.height / 2) 2 else 0)
                bitmap.setPixel(x, y, colors[quadrant])
            }
        }
    }

    private fun expectedQuarterColors(orientation: Int): List<Int> = when (orientation) {
        ExifInterface.ORIENTATION_NORMAL -> listOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt(),
        )
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> listOf(
            0xFF00FF00.toInt(), 0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF0000FF.toInt(),
        )
        ExifInterface.ORIENTATION_ROTATE_180 -> listOf(
            0xFFFFFF00.toInt(), 0xFF0000FF.toInt(), 0xFF00FF00.toInt(), 0xFFFF0000.toInt(),
        )
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> listOf(
            0xFF0000FF.toInt(), 0xFFFFFF00.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(),
        )
        ExifInterface.ORIENTATION_TRANSPOSE -> listOf(
            0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
        )
        ExifInterface.ORIENTATION_ROTATE_90 -> listOf(
            0xFF0000FF.toInt(), 0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
        )
        ExifInterface.ORIENTATION_TRANSVERSE -> listOf(
            0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFF0000.toInt(),
        )
        ExifInterface.ORIENTATION_ROTATE_270 -> listOf(
            0xFF00FF00.toInt(), 0xFFFFFF00.toInt(), 0xFFFF0000.toInt(), 0xFF0000FF.toInt(),
        )
        else -> error("unsupported test orientation")
    }

    private fun assertColorNear(label: String, expected: Int, actual: Int) {
        val distance = kotlin.math.abs(((expected shr 16) and 0xFF) - ((actual shr 16) and 0xFF)) +
            kotlin.math.abs(((expected shr 8) and 0xFF) - ((actual shr 8) and 0xFF)) +
            kotlin.math.abs((expected and 0xFF) - (actual and 0xFF))
        assertTrue(distance < 120, "$label expected=$expected actual=$actual")
    }
}
