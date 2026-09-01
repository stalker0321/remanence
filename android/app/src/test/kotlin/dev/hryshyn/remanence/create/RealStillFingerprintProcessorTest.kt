package dev.hryshyn.remanence.create

import android.graphics.Bitmap
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.core.recognition.CaptureDecoder
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.QualityReason
import dev.hryshyn.remanence.core.recognition.QuadCandidate
import dev.hryshyn.remanence.core.recognition.RecognitionProfile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RealStillFingerprintProcessorTest {

    @Before
    fun loadNative() {
        runCatching { System.loadLibrary("opencv_java4100") }
            .onFailure { error ->
                val alreadyLoaded = error is UnsatisfiedLinkError &&
                    error.message?.contains("already loaded") == true
                assumeTrue("desktop OpenCV natives unavailable: $error", alreadyLoaded)
            }
    }

    private val profile = RecognitionProfile.mvpOrbV1()

    @Test
    fun guideAlignedCaptureWithoutQuadUsesFallbackAndProducesUsableOrb() {
        val result = processor(::emptyContours).process(patternJpeg())

        assertTrue("expected accepted fallback capture, got $result", result is ProcessedStill.Accepted)
    }

    @Test
    fun portraitGuideFallbackProducesUsableOrbThroughProductionPipeline() {
        val result = processor(::emptyContours).process(patternJpeg(width = 600, height = 900))

        assertTrue("expected accepted portrait fallback capture, got $result", result is ProcessedStill.Accepted)
    }

    @Test
    fun fallbackStillRejectsBlurDarkAndGlare() {
        val blurry = processor(::emptyContours).process(patternJpeg(blur = true))
        val dark = processor(::emptyContours).process(patternJpeg(dark = true))
        val glare = processor(::emptyContours).process(patternJpeg(glare = true))

        assertRejectedWith(blurry, QualityReason.TOO_BLURRY)
        assertRejectedWith(dark, QualityReason.TOO_DARK)
        assertRejectedWith(glare, QualityReason.GLARE_EXCESSIVE)
    }

    private fun processor(detector: (IntArray, Int, Int) -> List<QuadCandidate>) =
        RealStillFingerprintProcessor(
            profile = profile,
            side = FingerprintSide.FRONT,
            contourDetector = detector,
        )

    private fun emptyContours(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): List<QuadCandidate> = emptyList()

    private fun assertRejectedWith(result: ProcessedStill, reason: QualityReason) {
        assertTrue("expected $reason rejection, got $result", result is ProcessedStill.Rejected)
        assertTrue(reason in (result as ProcessedStill.Rejected).reasons)
    }

    private fun patternJpeg(
        blur: Boolean = false,
        dark: Boolean = false,
        glare: Boolean = false,
        width: Int = 900,
        height: Int = 600,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val texture = ((x * 37 + y * 61 + (x * y % 97)) % 150)
                val value = if (dark) 18 + texture / 20 else 65 + texture
                pixels[y * width + x] = 0xFF000000.toInt() or
                    (value shl 16) or ((value + 13).coerceAtMost(255) shl 8) or
                    (value + 27).coerceAtMost(255)
            }
        }
        if (glare) {
            for (y in 100 until 500) {
                for (x in 200 until 700) pixels[y * width + x] = 0xFFFFFFFF.toInt()
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val source = if (blur) {
            val reduced = Bitmap.createScaledBitmap(bitmap, 45, 30, true)
            Bitmap.createScaledBitmap(reduced, width, height, true).also { reduced.recycle() }
        } else {
            bitmap
        }
        return try {
            CaptureDecoder.encodeFixtureJpeg(source)
        } finally {
            source.recycle()
            if (source !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        }
    }
}
