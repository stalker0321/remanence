package postmark.core.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Clustered/distributed proof for M1-M03 spatial coverage. */
class SpatialCoverageMeterTest {

    private val meter = SpatialCoverageMeter(gridSize = 4)

    @Test
    fun cardSpanningCornersYieldLargeHullAndManyCells() {
        val corners = listOf(
            0.05 to 0.05,
            0.95 to 0.08,
            0.92 to 0.94,
            0.07 to 0.90,
            0.50 to 0.50,
        )

        val coverage = meter.measure(corners, corners)

        // Hull of the four outer corners is ~0.81 of the normalized card.
        assertTrue(coverage.hullAreaNormalized > 0.70)
        assertEquals(coverage.queryHullAreaNormalized, coverage.hullAreaNormalized)
        assertEquals(5, coverage.occupiedGridCells)
    }

    @Test
    fun stampCornerClusterIsTinyEvenWithHighInlierCount() {
        val cluster = (0 until 60).map { i ->
            val jitterX = ((i * 37) % 11) / 500.0
            val jitterY = ((i * 61) % 13) / 600.0
            (0.85 + jitterX) to (0.10 + jitterY)
        }

        val coverage = meter.measure(cluster, cluster)

        assertTrue(coverage.hullAreaNormalized < 0.01, "cluster hull must be tiny")
        assertTrue(coverage.occupiedGridCells <= 2, "cluster must occupy at most a couple of grid cells")
    }

    @Test
    fun weakerSideBindsBothAreaAndCells() {
        val distributed = listOf(
            0.02 to 0.02,
            0.98 to 0.03,
            0.97 to 0.97,
            0.03 to 0.96,
        )
        val cornerTriangle = listOf(0.80 to 0.80, 0.86 to 0.82, 0.82 to 0.87)

        val coverage = meter.measure(distributed, cornerTriangle)

        // Triangle area is hand-computed: |cross((0.06,0.02),(0.02,0.07))| / 2.
        assertEquals(0.0019, coverage.hullAreaNormalized, 1e-12)
        assertEquals(distributedHull(), coverage.queryHullAreaNormalized)
        assertEquals(1, coverage.occupiedGridCells)
    }

    private fun distributedHull(): Double {
        val distributed = listOf(
            0.02 to 0.02,
            0.98 to 0.03,
            0.97 to 0.97,
            0.03 to 0.96,
        )
        return SpatialCoverageMeter(gridSize = 4)
            .measure(distributed, listOf(0.5 to 0.5, 0.51 to 0.5, 0.5 to 0.51))
            .queryHullAreaNormalized
    }

    @Test
    fun knownPolygonHullAreaIsExact() {
        // Interior point + collinear edge points must not change the hull.
        val squareWithNoise = listOf(
            0.0 to 0.0,
            0.5 to 0.0,
            1.0 to 0.0,
            1.0 to 1.0,
            0.0 to 1.0,
            0.25 to 0.25,
        )
        val other = listOf(squareWithNoise.first(), squareWithNoise[2])

        val coverage = meter.measure(squareWithNoise, other)

        assertEquals(1.0, coverage.queryHullAreaNormalized, 1e-12)
        assertEquals(0.0, coverage.referenceHullAreaNormalized, 1e-12)
    }

    @Test
    fun duplicatePointsCollapseInsteadOfBreakingTheHull() {
        val same = List(9) { 0.4 to 0.4 }

        val coverage = meter.measure(same, same)

        assertEquals(0.0, coverage.hullAreaNormalized, 1e-12)
        assertEquals(1, coverage.occupiedGridCells)
    }

    @Test
    fun emptySidesAndBadGridsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            meter.measure(emptyList(), listOf(0.5 to 0.5))
        }
        assertFailsWith<IllegalArgumentException> {
            SpatialCoverageMeter(gridSize = 1)
        }
    }
}
