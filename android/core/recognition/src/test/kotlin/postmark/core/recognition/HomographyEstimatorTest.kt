package postmark.core.recognition

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Synthetic-transform proof for M1-M02: a known projective map is applied to
 * a reference grid, outliers are injected, and the estimator must recover the
 * exact inlier set and matrix (up to scale) deterministically.
 */
class HomographyEstimatorTest {

    private val estimator = HomographyEstimator()

    /** True H: modest perspective warp mapping reference card onto query view. */
    private val trueH = doubleArrayOf(
        1.1, 0.04, -0.06,
        -0.03, 0.95, 0.05,
        0.0002, 0.0001, 1.0,
    )

    private fun apply(h: DoubleArray, x: Double, y: Double): Pair<Double, Double> {
        val dx = h[0] * x + h[1] * y + h[2]
        val dy = h[3] * x + h[4] * y + h[5]
        val w = h[6] * x + h[7] * y + h[8]
        return (dx / w) to (dy / w)
    }

    private fun gridPoints(step: Int = 10): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        var i = 0
        while (i <= step) {
            var j = 0
            while (j <= step) {
                points += (j.toDouble() / step) to (i.toDouble() / step)
                j++
            }
            i++
        }
        return points
    }

    private fun correspondences(): List<MatchPoint> =
        gridPoints().map { (rx, ry) ->
            val (qx, qy) = apply(trueH, rx, ry)
            MatchPoint(qx, qy, rx, ry)
        }

    @Test
    fun recoversExactTransformWithAllInliersOnCleanSynthetic() {
        val report = estimator.estimate(correspondences())

        assertTrue(report.success)
        assertEquals(121, report.inlierCount)
        assertEquals(1.0, report.inlierRatio)
        assertTrue(report.medianInlierErrorNormalized < 1e-9)

        // Recovered matrix equals the truth up to scale: compare projections.
        val recovered = report.matrix!!
        gridPoints().take(25).forEach { (rx, ry) ->
            val (ex, ey) = apply(recovered, rx, ry)
            val (tx, ty) = apply(trueH, rx, ry)
            assertTrue(abs(ex - tx) < 1e-9 && abs(ey - ty) < 1e-9)
        }
    }

    @Test
    fun rejectsExactlyTheInjectedOutliersAndKeepsTruth() {
        val clean = correspondences().toMutableList()
        val random = Random(42)
        val outlierIndices = mutableSetOf<Int>()
        while (outlierIndices.size < 48) {
            outlierIndices += random.nextInt(clean.size)
        }
        val corrupted = clean.mapIndexed { index, point ->
            if (index in outlierIndices) {
                point.copy(queryX = (point.queryX * 733.0 % 0.9), queryY = (point.queryY * 311.0 % 0.85))
            } else {
                point
            }
        }

        val report = estimator.estimate(corrupted)

        assertTrue(report.success)
        assertEquals(121 - 48, report.inlierCount)
        // Every surviving index must be one of the untouched correspondences.
        assertTrue(report.inlierIndices.all { it !in outlierIndices })
        assertTrue(report.inlierRatio > 0.55)
        assertTrue(report.medianInlierErrorNormalized < HomographyEstimator.DEFAULT_TOLERANCE_NORMALIZED)

        val recovered = report.matrix!!
        listOf(0.1 to 0.1, 0.5 to 0.5, 0.9 to 0.7).forEach { (rx, ry) ->
            val (ex, ey) = apply(recovered, rx, ry)
            val (tx, ty) = apply(trueH, rx, ry)
            assertTrue(abs(ex - tx) < 1e-6 && abs(ey - ty) < 1e-6)
        }
    }

    @Test
    fun smallNoiseWithinToleranceStillClassifiesAsInliers() {
        val noisy = correspondences().map { point ->
            point.copy(
                queryX = point.queryX + 1e-4,
                queryY = point.queryY - 1e-4,
            )
        }

        val report = estimator.estimate(noisy)

        assertTrue(report.success)
        assertEquals(noisy.size, report.inlierCount)
        // 1e-4 normalized ≈ 0.16 px on the canonical edge; well inside 5 px.
        assertTrue(report.medianInlierErrorNormalized <= HomographyEstimator.DEFAULT_TOLERANCE_NORMALIZED)
    }

    @Test
    fun degenerateInputsFailClosed() {
        // Too few correspondences.
        val tiny = correspondences().take(3)
        assertTrue(!estimator.estimate(tiny).success)

        // Collinear reference points can never define a homography.
        val collinear = (0 until 12).map { i ->
            MatchPoint(queryX = 0.1 + 0.01 * i, queryY = 0.2, referenceX = i / 11.0, referenceY = i / 11.0)
        }
        assertTrue(!estimator.estimate(collinear).success)
    }

    @Test
    fun pureNoiseNeverProducesAConfidentModel() {
        val random = Random(7)
        val noise = List(120) {
            MatchPoint(
                queryX = random.nextDouble(),
                queryY = random.nextDouble(),
                referenceX = random.nextDouble(),
                referenceY = random.nextDouble(),
            )
        }

        val report = estimator.estimate(noise)

        assertTrue(!report.success || report.inlierCount <= 24)
    }

    @Test
    fun sameSeedProducesIdenticalReports() {
        val corrupted = correspondences().mapIndexed { index, point ->
            if (index % 5 == 0) point.copy(queryX = point.queryX + 0.31, queryY = point.queryY + 0.17) else point
        }

        val first = estimator.estimate(corrupted)
        val second = HomographyEstimator().estimate(corrupted)

        assertEquals(first.inlierIndices, second.inlierIndices)
        assertTrue(first.matrix!!.contentEquals(second.matrix!!))
    }
}
