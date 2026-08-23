package postmark.core.recognition

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import java.io.File
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
        val temp = File.createTempFile("postmark-exif", ".jpg")
        try {
            temp.writeBytes(jpeg)
            val exif = ExifInterface(temp.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
            return temp.readBytes()
        } finally {
            temp.delete()
        }
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
}
