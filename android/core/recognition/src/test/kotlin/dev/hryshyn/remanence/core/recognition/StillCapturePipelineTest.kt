package dev.hryshyn.remanence.core.recognition

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StillCapturePipelineTest {

    private fun solidBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(0xFF3366AA.toInt())
        }

    private fun fixtureCapture(width: Int, height: Int): ByteArray =
        CaptureDecoder.encodeFixtureJpeg(solidBitmap(width, height))

    @Test
    fun fakeCaptureBecomesBoundedUprightWorkingImage() {
        val pipeline = StillCapturePipeline(maxWorkingEdgePx = 1600)
        val capture = pipeline.process(fixtureCapture(640, 480))

        try {
            assertEquals(640, capture.width)
            assertEquals(480, capture.height)
            assertTrue(capture.exifOrientationApplied >= 0)
        } finally {
            capture.close()
        }
        assertTrue("close must release the working bitmap", capture.bitmap.isRecycled)
    }

    @Test
    fun oversizedOddCaptureIsExactFitDownscaledToWorkingBound() {
        val pipeline = StillCapturePipeline(maxWorkingEdgePx = 500)
        val capture = pipeline.process(fixtureCapture(999, 10))

        try {
            assertEquals(500, capture.width)
            assertEquals(5, capture.height)
        } finally {
            capture.close()
        }
    }

    @Test
    fun closeIsIdempotent() {
        val pipeline = StillCapturePipeline(maxWorkingEdgePx = 1600)
        val capture = pipeline.process(fixtureCapture(64, 64))
        capture.close()
        capture.close()
        assertThrows(IllegalStateException::class.java) { capture.checkOpen() }
    }

    @Test
    fun nonDecodableCaptureFailsClosed() {
        val pipeline = StillCapturePipeline(maxWorkingEdgePx = 1600)
        assertThrows(IllegalArgumentException::class.java) { pipeline.process("not a still".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { pipeline.process(ByteArray(0)) }
    }
}
