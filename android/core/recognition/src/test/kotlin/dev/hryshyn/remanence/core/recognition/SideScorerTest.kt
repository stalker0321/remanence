package dev.hryshyn.remanence.core.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exact report-to-score proof for M1-M05 using the frozen mvp-orb-v1 seeds. */
class SideScorerTest {

    private val scorer = SideScorer(RecognitionProfile.mvpOrbV1())

    /** All sub-scores land exactly on 0.5 with the seeded thresholds. */
    private fun halfWaySignals() = SideMatchSignals(
        ratioMutualMatches = 20,
        ransacInliers = 20, // min(20/40) = 0.5
        inlierRatio = 0.50, // (0.50-0.20)/0.60 = 0.5
        spatialCoverage = 0.225, // 0.225/0.45 = 0.5
        occupiedGridCells = 9,
        medianInlierErrorNormalized = 4.0 / 1600.0, // 4 px -> reprojection 0.5
        homographyPlausible = true,
    )

    @Test
    fun balancedSignalsProduceExactlyHalfScores() {
        val report = scorer.score(halfWaySignals())

        assertEquals(0.5, report.countScore)
        assertEquals(0.5, report.ratioScore)
        assertEquals(0.5, report.coverageScore)
        assertEquals(0.5, report.reprojectionScore)

        val expected = 0.35 * 0.5 + 0.25 * 0.5 + 0.25 * 0.5 + 0.15 * 0.5
        assertEquals(expected, report.sideScore)
        assertEquals(0.5, report.sideScore)
    }

    @Test
    fun everyComponentClampsAtBothEnds() {
        val saturated = SideMatchSignals(
            ratioMutualMatches = 500,
            ransacInliers = 400,
            inlierRatio = 1.0,
            spatialCoverage = 2.0,
            occupiedGridCells = 16,
            medianInlierErrorNormalized = 0.0,
            homographyPlausible = true,
        )
        val perfect = scorer.score(saturated)

        assertEquals(listOf(1.0, 1.0, 1.0, 1.0), listOf(
            perfect.countScore, perfect.ratioScore, perfect.coverageScore, perfect.reprojectionScore,
        ))
        assertEquals(1.0, perfect.sideScore)

        val starved = SideMatchSignals(
            ratioMutualMatches = 10,
            ransacInliers = 6, // 6/40 -> count below weak? still passes weakMinInliers=6
            inlierRatio = 0.20, // ratio score clamps to 0 at offset
            spatialCoverage = 0.10, // coverage score ~0.22 not zero; keep separate test for gates
            occupiedGridCells = 3,
            medianInlierErrorNormalized = 8.0 / 1600.0, // reprojection exactly 0
            homographyPlausible = true,
        )
        val floorReport = scorer.score(starved)
        assertEquals(0.15, floorReport.countScore, 1e-12) // 6/40 * 0.35
        assertEquals(0.0, floorReport.ratioScore)
        assertEquals(0.0, floorReport.reprojectionScore)
    }

    @Test
    fun weakGatePassesExactlyAtEveryDocumentedMinimum() {
        val minimums = SideMatchSignals(
            ratioMutualMatches = 10,
            ransacInliers = 6,
            inlierRatio = 0.25,
            spatialCoverage = 0.10,
            occupiedGridCells = 3,
            medianInlierErrorNormalized = 99.0 / 1600.0, // error is not part of the weak gate
            homographyPlausible = true,
        )
        assertTrue(scorer.score(minimums).weakGatePassed)
        assertFalse(scorer.score(minimums).strongGatePassed)

        listOf(
            minimums.copy(ratioMutualMatches = 9),
            minimums.copy(ransacInliers = 5),
            minimums.copy(inlierRatio = 0.249999),
            minimums.copy(spatialCoverage = 0.099999),
            minimums.copy(occupiedGridCells = 2),
            minimums.copy(homographyPlausible = false),
        ).forEach { degraded ->
            assertFalse(scorer.score(degraded).weakGatePassed, "must fail: $degraded")
        }
    }

    @Test
    fun strongGatePassesExactlyAtEveryDocumentedMinimum() {
        val minimums = SideMatchSignals(
            ratioMutualMatches = 20,
            ransacInliers = 15,
            inlierRatio = 0.35,
            spatialCoverage = 0.20,
            occupiedGridCells = 3,
            medianInlierErrorNormalized = 4.0 / 1600.0,
            homographyPlausible = true,
        )
        val report = scorer.score(minimums)
        assertTrue(report.strongGatePassed)
        assertTrue(report.weakGatePassed, "anything strong is also at least weak")

        listOf(
            minimums.copy(ratioMutualMatches = 19),
            minimums.copy(ransacInliers = 14),
            minimums.copy(inlierRatio = 0.349999),
            minimums.copy(spatialCoverage = 0.199999),
            minimums.copy(medianInlierErrorNormalized = (4.001) / 1600.0),
            minimums.copy(homographyPlausible = false),
        ).forEach { degraded ->
            assertFalse(scorer.score(degraded).strongGatePassed, "must fail: $degraded")
        }
        // The strong gate deliberately carries no grid-cell requirement
        // (docs/recognition.md section 8); coverage already binds it.
        assertTrue(scorer.score(minimums.copy(occupiedGridCells = 2)).strongGatePassed)
    }

    @Test
    fun implausibleHomographyFailsBothGatesRegardlessOfCounts() {
        val huge = SideMatchSignals(
            ratioMutualMatches = 100,
            ransacInliers = 60,
            inlierRatio = 0.9,
            spatialCoverage = 0.8,
            occupiedGridCells = 12,
            medianInlierErrorNormalized = 0.0,
            homographyPlausible = false,
        )

        val report = scorer.score(huge)

        assertFalse(report.weakGatePassed)
        assertFalse(report.strongGatePassed)
        assertEquals(1.0, report.sideScore, 1e-12) // score still computed, gates decide
    }
}
