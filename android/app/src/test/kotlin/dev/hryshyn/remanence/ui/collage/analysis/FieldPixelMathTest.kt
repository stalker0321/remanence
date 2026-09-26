package dev.hryshyn.remanence.ui.collage.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM pins for [FieldPixelMath], the scalar contract of the frozen pixel
 * stage (`runtime/tools/legacy_features.py:14-19, 66-69`): numpy-default
 * linear `.98` quantile, the `normal()` scale/clip/floor, tip box clamping,
 * the exclusive `0.025` dedup, and the analytic 48x48 tip gaussian. The
 * [OpenCvFieldStage] executes these same functions on device; no OpenCV
 * natives are loaded here.
 *
 * Declared tolerances: quantile/normalized values 1e-12, tip boxes 1e-9,
 * gaussian symmetry exact (same arithmetic both sides).
 */
class FieldPixelMathTest {

    @Test
    fun quantileMatchesLinearInterpolation() {
        assertEquals(5.0, FieldPixelMath.quantileLinearSorted(doubleArrayOf(5.0), 0.98), 1e-12)
        assertEquals(0.0, FieldPixelMath.quantileLinearSorted(doubleArrayOf(0.0, 10.0), 0.0), 1e-12)
        assertEquals(10.0, FieldPixelMath.quantileLinearSorted(doubleArrayOf(0.0, 10.0), 1.0), 1e-12)
        assertEquals(5.0, FieldPixelMath.quantileLinearSorted(doubleArrayOf(0.0, 10.0), 0.5), 1e-12)
        assertEquals(25.0, FieldPixelMath.quantileLinearSorted(doubleArrayOf(0.0, 100.0), 0.25), 1e-12)
        // pos = 0.98 * 3 = 2.94 -> 2 * 0.06 + 3 * 0.94.
        assertEquals(2.94, FieldPixelMath.quantileLinearSorted(doubleArrayOf(0.0, 1.0, 2.0, 3.0), 0.98), 1e-12)
    }

    @Test
    fun quantileRejectsEmptyNonFiniteAndOutOfRangeQ() {
        assertThrows { FieldPixelMath.quantileLinearSorted(doubleArrayOf(), 0.98) }
        assertThrows { FieldPixelMath.quantileLinearSorted(doubleArrayOf(0.0, Double.NaN), 0.98) }
        assertThrows { FieldPixelMath.quantileLinearSorted(doubleArrayOf(0.0, 1.0), -0.1) }
        assertThrows { FieldPixelMath.quantileLinearSorted(doubleArrayOf(0.0, 1.0), 1.1) }
    }

    @Test
    fun normalizeScalesByQ98AndClipsToUnitRange() {
        assertEquals(
            listOf(1.0, 1.0, 1.0, 1.0),
            FieldPixelMath.normalizeField(doubleArrayOf(2.0, 2.0, 2.0, 2.0)).toList(),
        )
        // q.98 = 9.4: 10 / 9.4 clips to 1.
        val scaled = FieldPixelMath.normalizeField(doubleArrayOf(0.0, 0.0, 0.0, 10.0))
        assertEquals(0.0, scaled[0], 1e-12)
        assertEquals(1.0, scaled[3], 1e-12)
        // Negatives clip to 0, overshoot clips to 1.
        val clipped = FieldPixelMath.normalizeField(doubleArrayOf(-5.0, 5.0))
        assertEquals(0.0, clipped[0], 1e-12)
        assertEquals(1.0, clipped[1], 1e-12)
        // All zeros: max(0, 1e-6) floor keeps the output at zero, not NaN.
        assertEquals(
            listOf(0.0, 0.0, 0.0),
            FieldPixelMath.normalizeField(doubleArrayOf(0.0, 0.0, 0.0)).toList(),
        )
    }

    @Test
    fun normalizeRejectsEmptyAndNonFinite() {
        assertThrows { FieldPixelMath.normalizeField(doubleArrayOf()) }
        assertThrows { FieldPixelMath.normalizeField(doubleArrayOf(0.0, Double.POSITIVE_INFINITY)) }
    }

    @Test
    fun tipBoxClampsToFrozenCorners() {
        val origin = FieldPixelMath.tipBoxForPixel(0, 0)
        assertEquals(0.0, origin.x, 1e-9)
        assertEquals(0.0, origin.y, 1e-9)
        assertEquals(0.03, origin.width, 1e-9)
        assertEquals(0.04, origin.height, 1e-9)

        val mid = FieldPixelMath.tipBoxForPixel(96, 48)
        assertEquals(0.485, mid.x, 1e-9)
        assertEquals(0.235, mid.y, 1e-9)
        assertEquals(0.03, mid.width, 1e-9)
        assertEquals(0.04, mid.height, 1e-9)

        // Far corner: width/height shrink against the unit edge.
        val far = FieldPixelMath.tipBoxForPixel(191, 191)
        assertEquals(191.0 / 192 - 0.015, far.x, 1e-9)
        assertEquals(191.0 / 192 - 0.015, far.y, 1e-9)
        assertEquals(1.0 - far.x, far.width, 1e-9)
        assertEquals(1.0 - far.y, far.height, 1e-9)
        assertThrows { FieldPixelMath.tipBoxForPixel(-1, 0) }
    }

    @Test
    fun tipDedupIsExclusiveOnBothAxes() {
        val kept = listOf(FieldPixelMath.tipBoxForPixel(96, 48))
        val bx = kept[0].x
        val by = kept[0].y
        assertFalse(FieldPixelMath.isNovelTip(kept, bx, by))
        assertFalse(FieldPixelMath.isNovelTip(kept, bx + 0.024, by))
        assertFalse(FieldPixelMath.isNovelTip(kept, bx, by - 0.024))
        // Exclusive boundary: exactly 0.025 on either axis is novel, and both
        // axes must agree for a duplicate.
        assertTrue(FieldPixelMath.isNovelTip(kept, bx + 0.025, by))
        assertTrue(FieldPixelMath.isNovelTip(kept, bx, by + 0.025))
        assertTrue(FieldPixelMath.isNovelTip(kept, bx + 0.024, by + 0.025))
        assertTrue(FieldPixelMath.isNovelTip(emptyList(), bx, by))
        assertThrows { FieldPixelMath.isNovelTip(kept, Double.NaN, by) }
    }

    @Test
    fun tipGaussianIsSymmetricRowMajorAndBounded() {
        val box = dev.hryshyn.remanence.ui.collage.NormBox(0.4, 0.4, 0.2, 0.2)
        val g = FieldPixelMath.tipGaussian48(box)
        assertEquals(48 * 48, g.size)
        assertTrue(g.all { it.isFinite() })
        // Center cells (rows/cols 23-24 straddle 0.5) peak near 1.
        assertTrue(g[24 * 48 + 24] > 0.99)
        // Exact mirror symmetry pins the row-major layout without OpenCV.
        assertEquals(g[23 * 48 + 24], g[24 * 48 + 23], 0.0)
        assertEquals(g[23 * 48 + 23], g[24 * 48 + 24], 0.0)
        assertEquals(g[47], g[47 * 48], 0.0)
        // Far corner of a centered box is effectively zero.
        assertTrue(g[0] < 1e-3)
        // Degenerate boxes cannot produce NaN/Infinity (0.025 sigma floor).
        val degenerate = FieldPixelMath.tipGaussian48(
            dev.hryshyn.remanence.ui.collage.NormBox(0.5, 0.5, 0.0, 0.0),
        )
        assertTrue(degenerate.all { it.isFinite() })
        assertTrue((degenerate.maxOrNull() ?: 0.0) <= 1.0)
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
