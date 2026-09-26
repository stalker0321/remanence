package dev.hryshyn.remanence.ui.collage.analysis

import dev.hryshyn.remanence.ui.collage.CollageAnalysis
import dev.hryshyn.remanence.ui.collage.NormBox
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Pure, host-neutral arithmetic for the frozen `import-field-3.3` pixel stage
 * (`runtime/tools/legacy_features.py:50-73`). No OpenCV, no `android.*`: every
 * function here is exhaustively unit-testable on the JVM. The OpenCV
 * implementation ([OpenCvFieldStage]) calls into these for the exact parts of
 * the freeze that are scalar math, so the JVM tests pin the contract the
 * device executes.
 *
 * Pinned freeze facts:
 * - `normal(a)` (line 14-16): float values, `quantile(a, 0.98)` with numpy's
 *   default linear interpolation, divisor `max(q, 1e-6)`, clipped to `[0, 1]`.
 * - contour tip boxes (lines 66-67) on the 192-extent structural image:
 *   `bx = max(0, px / 192 - 0.015)`, `by = max(0, py / 192 - 0.015)`,
 *   `b = [bx, by, min(0.03, 1 - bx), min(0.04, 1 - by)]`, kept with
 *   confidence `0.4` unless an already-kept tip is within `0.025` in both
 *   `x` and `y` (line 69).
 * - tip merge (line 68): element-wise maximum with the analytic
 *   `gaussian_field(b, expand = 0.4)` on the 48x48 grid (same formula as
 *   `legacy_features.py:17-19`; duplicated here so the A4a aggregator stays
 *   untouched).
 */
object FieldPixelMath {

    /** `np.quantile` probability used by `normal()`. */
    const val NORMALIZE_QUANTILE = 0.98

    /** Denominator floor used by `normal()`. */
    const val NORMALIZE_FLOOR = 1e-6

    /** Structural working extent (pixels) for contour tip points. */
    const val TIP_EXTENT = 192

    /** Tip halo subtracted from the topmost contour point. */
    const val TIP_HALO = 0.015

    /** Clamped tip box width/height. */
    const val TIP_WIDTH = 0.03
    const val TIP_HEIGHT = 0.04

    /** Proximity dedup delta for tip boxes (both axes, exclusive). */
    const val TIP_DEDUP_DELTA = 0.025

    /** Gaussian expand used by the tip merge. */
    const val TIP_EXPAND = 0.4

    /** Confidence of a kept structure-tip region. */
    const val TIP_CONFIDENCE = 0.4

    /**
     * Numpy-default linear-interpolation quantile over already-sorted values.
     * `pos = q * (n - 1)`; interpolates between the bracketing ranks.
     */
    fun quantileLinearSorted(sorted: DoubleArray, q: Double): Double {
        require(sorted.isNotEmpty()) { "quantile requires at least one value" }
        require(q in 0.0..1.0) { "quantile q must be in [0, 1]" }
        require(sorted.all { it.isFinite() }) { "quantile values must be finite" }
        if (sorted.size == 1) return sorted[0]
        val pos = q * (sorted.size - 1)
        val lower = pos.toInt()
        val fraction = pos - lower
        if (fraction == 0.0) return sorted[lower]
        return sorted[lower] * (1.0 - fraction) + sorted[lower + 1] * fraction
    }

    /**
     * The frozen `normal()`: `clip(values / max(quantile(values, 0.98),
     * 1e-6), 0, 1)`. The input is not mutated; a new array is returned.
     */
    fun normalizeField(values: DoubleArray): DoubleArray {
        require(values.isNotEmpty()) { "normal() requires at least one value" }
        require(values.all { it.isFinite() }) { "normal() values must be finite" }
        val sorted = values.clone()
        sorted.sort()
        val divisor = max(quantileLinearSorted(sorted, NORMALIZE_QUANTILE), NORMALIZE_FLOOR)
        return DoubleArray(values.size) { i -> (values[i] / divisor).coerceIn(0.0, 1.0) }
    }

    /**
     * Clamped tip box for one topmost contour point (`px`, `py` in
     * structural pixels, extent [TIP_EXTENT]).
     */
    fun tipBoxForPixel(px: Int, py: Int): NormBox {
        require(px >= 0 && py >= 0) { "contour points must be non-negative" }
        val bx = max(0.0, px.toDouble() / TIP_EXTENT - TIP_HALO)
        val by = max(0.0, py.toDouble() / TIP_EXTENT - TIP_HALO)
        return NormBox(bx, by, min(TIP_WIDTH, 1.0 - bx), min(TIP_HEIGHT, 1.0 - by))
    }

    /**
     * The line-69 dedup rule: a candidate tip point is novel unless an
     * already-kept box is within [TIP_DEDUP_DELTA] in both axes (exclusive on
     * both, exactly as the frozen `abs(...) < .025` comparisons).
     */
    fun isNovelTip(kept: List<NormBox>, bx: Double, by: Double): Boolean {
        require(bx.isFinite() && by.isFinite()) { "tip point must be finite" }
        return kept.none { abs(it.x - bx) < TIP_DEDUP_DELTA && abs(it.y - by) < TIP_DEDUP_DELTA }
    }

    /**
     * Analytic `gaussian_field(box, n = 48, expand)` on the 48x48 row-major
     * grid (`y * 48 + x`), cell centers `(i + 0.5) / 48`, denominator floor
     * `0.025`. Used only for the tip merge inside the pixel loop.
     */
    fun tipGaussian48(box: NormBox, expand: Double = TIP_EXPAND): DoubleArray {
        val grid = CollageAnalysis.GRID_SIZE
        val out = DoubleArray(grid * grid)
        val sigmaX = max(box.width * (0.5 + expand), 0.025)
        val sigmaY = max(box.height * (0.5 + expand), 0.025)
        val cx = box.x + box.width / 2.0
        val cy = box.y + box.height / 2.0
        for (j in 0 until grid) {
            val yy = j.toDouble() / grid + 0.5 / grid
            for (i in 0 until grid) {
                val xx = i.toDouble() / grid + 0.5 / grid
                val ex = (xx - cx) / sigmaX
                val ey = (yy - cy) / sigmaY
                out[j * grid + i] = exp(-0.5 * ex * ex - 0.5 * ey * ey)
            }
        }
        return out
    }
}
