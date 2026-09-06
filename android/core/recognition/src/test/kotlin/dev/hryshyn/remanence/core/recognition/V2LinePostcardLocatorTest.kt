package dev.hryshyn.remanence.core.recognition

import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class V2LinePostcardLocatorTest {
    private val profile = RecognitionProfile.mvpOrbV1()
    private val frameWidth = 400
    private val frameHeight = 300

    @BeforeTest
    fun loadNative() {
        runCatching { System.loadLibrary("opencv_java4100") }
            .onFailure { assumeTrue("desktop OpenCV natives unavailable: $it", false) }
    }

    @Test
    fun realFrameLinesProduceMeasuredRectangularProposal() {
        val pixels = IntArray(frameWidth * frameHeight) { 0xFF202020.toInt() }
        for (y in 50 until 250) {
            for (x in 60 until 340) {
                pixels[y * frameWidth + x] = 0xFFF2F2F2.toInt()
            }
        }

        val candidates = V2LinePostcardLocator(profile).detect(pixels, frameWidth, frameHeight)

        assertTrue(candidates.isNotEmpty(), "expected a line-locator proposal")
        val best = candidates.first()
        assertTrue(best.edgeSupport != null && best.edgeSupport >= 0.70)
        assertTrue(best.rectangularity > 0.85, "rectangularity=${best.rectangularity}")
        assertTrue(best.areaRatio > 0.30, "areaRatio=${best.areaRatio}")
        assertTrue(abs(best.corners.first().x - 60.0) < 18.0)
        assertTrue(abs(best.corners.first().y - 50.0) < 18.0)
    }
}
