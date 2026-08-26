package dev.hryshyn.remanence.core.recognition

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class CaptureQualityMeterTest {

    private val meter = CaptureQualityMeter()
    private val w = 200
    private val h = 150

    @BeforeTest
    fun loadNative() {
        runCatching { System.loadLibrary("opencv_java4100") }
            .onFailure { assumeTrue("desktop OpenCV natives unavailable: $it", false) }
    }

    private fun solid(color: Int): IntArray = IntArray(w * h) { color }

    @Test
    fun flatImageHasNearZeroLaplacianVariance() {
        val signals = meter.measure(solid(0xFF808080.toInt()), w, h)
        assertTrue(signals.laplacianVariance < 1.0, "variance=${signals.laplacianVariance}")
        assertEquals(0.0, signals.nearBlackFraction)
        assertEquals(0.0, signals.clippedWhiteFraction)
        assertEquals(0.0, signals.largestGlareFraction)
    }

    @Test
    fun checkerboardHasHighLaplacianVariance() {
        val pixels = IntArray(w * h) { index ->
            val x = index % w
            val y = index / w
            if (((x / 4) + (y / 4)) % 2 == 0) 0xFF101010.toInt() else 0xFFEFEFEF.toInt()
        }
        val signals = meter.measure(pixels, w, h)
        assertTrue(signals.laplacianVariance > 1000.0, "variance=${signals.laplacianVariance}")
    }

    @Test
    fun darkImageIsMostlyNearBlack() {
        val signals = meter.measure(solid(0xFF0A0A0A.toInt()), w, h)
        assertTrue(signals.nearBlackFraction > 0.99)
        assertEquals(0.0, signals.clippedWhiteFraction)
    }

    @Test
    fun saturatedWhiteIsClippedAndCountedAsGlare() {
        val signals = meter.measure(solid(0xFFFFFFFF.toInt()), w, h)
        assertTrue(signals.clippedWhiteFraction > 0.99)
        assertTrue(signals.largestGlareFraction > 0.99)
    }

    @Test
    fun brightBlobDominatesGlareRegionFraction() {
        val pixels = solid(0xFF303030.toInt())
        // Bright 20x20 blob inside dark field: fraction = 400/30000.
        for (y in 60 until 80) {
            for (x in 90 until 110) {
                pixels[y * w + x] = 0xFFFFFFFF.toInt()
            }
        }
        val signals = meter.measure(pixels, w, h)
        val expected = 400.0 / (w * h)
        assertTrue(
            kotlin.math.abs(signals.largestGlareFraction - expected) < expected * 0.25,
            "glare=${signals.largestGlareFraction} expected≈$expected",
        )
        assertEquals(expected, signals.clippedWhiteFraction, expected * 0.25 + 1e-9)
    }
}
