package dev.hryshyn.remanence.core.recognition

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reflection/skew/degenerate proof for M1-M04 homography plausibility. */
class HomographyPlausibilityGateTest {

    private val gate = HomographyPlausibilityGate(
        areaRatioMin = 0.20,
        areaRatioMax = 5.0,
        oppositeEdgeRatioMax = 4.0,
    )
    private val zeroMedian = HomographyEstimator.DEFAULT_TOLERANCE_NORMALIZED / 2

    @Test
    fun identityAndMildPerspectiveArePlausible() {
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        assertTrue(gate.check(identity, 0.001, zeroMedian).plausible)

        // Gentle perspective foreshortening: w varies from ~0.9 to ~1.1.
        val mild = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0002, 0.0002, 0.9)
        val report = gate.check(mild, 0.001, zeroMedian)
        assertTrue(report.plausible, "mild perspective must pass, got $report")
        assertTrue(report.orientationPreserved)
        assertTrue(report.convexQuad)
    }

    @Test
    fun reflectionFailsOrientationButNothingElse() {
        // Vertical mirror: determinant negative, geometry otherwise perfect.
        val mirrored = doubleArrayOf(-1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        val report = gate.check(mirrored, 0.001, zeroMedian)

        assertFalse(report.orientationPreserved)
        assertTrue(report.finiteQuad)
        assertTrue(report.convexQuad)
        assertFalse(report.plausible)
    }

    @Test
    fun extremeStretchFailsAreaRatioInBothDirections() {
        // Horizontal stretch by 6x: mapped area ratio 6 > 5.
        val stretched = doubleArrayOf(6.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        assertEquals(6.0, gate.check(stretched, 0.001, zeroMedian).mappedAreaRatio, 1e-12)
        assertFalse(gate.check(stretched, 0.001, zeroMedian).plausible)

        // Squeeze to a sliver: ratio 0.15 < 0.20.
        val squeezed = doubleArrayOf(1.5, 0.0, 0.0, 0.0, 0.1, 0.0, 0.0, 0.0, 1.0)
        assertEquals(0.15, gate.check(squeezed, 0.001, zeroMedian).mappedAreaRatio, 1e-12)
        assertFalse(gate.check(squeezed, 0.001, zeroMedian).plausible)
    }

    @Test
    fun trapezoidForeshorteningBeyondFourToOneFailsOppositeEdges() {
        // Projective map squeezing the top edge far below the bottom edge.
        val steep = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, -0.9, 1.0)

        val report = gate.check(steep, 0.001, zeroMedian)

        assertTrue(report.maxOppositeEdgeRatio > 4.0, "expected heavy foreshortening, got $report")
        assertFalse(report.plausible)

        // A moderate trapezoid stays inside the bound.
        val moderate = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, -0.3, 1.0)
        assertTrue(gate.check(moderate, 0.001, zeroMedian).plausible)
    }

    @Test
    fun wSignChangeProducesSelfIntersectionAndFails() {
        // w(x,y) = x + y - 1 changes sign inside the unit square, so the
        // projected corners form a crossed (bowtie) quadrilateral.
        val bowtie = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 1.0, -1.0)

        val report = gate.check(bowtie, 0.001, zeroMedian)

        assertFalse(report.finiteQuad || report.convexQuad || report.plausible)
    }

    @Test
    fun medianErrorAboveLimitRejectsEvenPerfectGeometry() {
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        val report = gate.check(identity, medianInlierErrorNormalized = 0.01, medianErrorLimitNormalized = 0.004)

        assertTrue(report.medianErrorWithinLimit.not())
        assertFalse(report.plausible)
    }

    @Test
    fun nonFiniteProjectionFailsClosed() {
        // dw == 0 at the first corner: the projection is undefined there.
        val singularW = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0)

        val report = gate.check(singularW, 0.001, zeroMedian)

        assertFalse(report.finiteQuad)
        assertFalse(report.plausible)
    }

    @Test
    fun boundaryAreaRatioValuesAreInclusive() {
        val fiveX = doubleArrayOf(5.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        assertTrue(gate.check(fiveX, 0.001, zeroMedian).plausible)

        val oneFifthX = doubleArrayOf(0.2, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        assertTrue(gate.check(oneFifthX, 0.001, zeroMedian).plausible)

        val justOver = doubleArrayOf(5.0001, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        assertFalse(gate.check(justOver, 0.001, zeroMedian).plausible)
    }

    @Test
    fun profileConstructorCarriesTheFrozenThresholds() {
        val profileGate = HomographyPlausibilityGate(RecognitionProfile.mvpOrbV1().match)
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        val report = profileGate.check(identity, 0.0, HomographyEstimator.DEFAULT_TOLERANCE_NORMALIZED)

        assertTrue(report.plausible)
    }
}
