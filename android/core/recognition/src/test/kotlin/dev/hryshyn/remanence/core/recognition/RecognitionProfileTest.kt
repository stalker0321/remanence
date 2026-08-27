package dev.hryshyn.remanence.core.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecognitionProfileTest {

    private val seed = RecognitionProfile.mvpOrbV1()

    @Test
    fun seedMatchesEveryDocumentedValue() {
        assertEquals("mvp-orb-v1", seed.profileId)
        assertEquals(1, seed.formatVersion)
        with(seed.capture) {
            assertEquals(0.35, minCardAreaRatio)
            assertEquals(600, minShortEdgeAfterWarpPx)
            assertEquals(1600, canonicalLongEdgePx)
            assertEquals(0, maxCornerOutsideFramePx)
            assertEquals(1.15, aspectRatioMin)
            assertEquals(2.20, aspectRatioMax)
            assertEquals(80.0, minLaplacianVariance)
            assertEquals(0.25, maxNearBlackFraction)
            assertEquals(0.20, maxClippedWhiteFraction)
            assertEquals(0.12, maxGlareRegionFraction)
            assertEquals(0.80, minRectangularity)
        }
        with(seed.orb) {
            assertEquals(1500, nfeatures)
            assertEquals(1.2, scaleFactor)
            assertEquals(8, nlevels)
            assertEquals(31, edgeThreshold)
            assertEquals(0, firstLevel)
            assertEquals(2, wtaK)
            assertTrue(scoreTypeHarris)
            assertEquals(31, patchSize)
            assertEquals(20, fastThreshold)
        }
        with(seed.match) {
            assertEquals(5.0, inlierReprojectionTolerancePx)
            assertEquals(40.0, countScoreInliersDivisor)
            assertEquals(0.20, ratioScoreOffset)
            assertEquals(0.60, ratioScoreSpan)
            assertEquals(0.45, coverageScoreTarget)
            assertEquals(8.0, reprojectionScoreMaxMedianErrorPx)
            assertEquals(10, weakMinRatioMatches)
            assertEquals(6, weakMinInliers)
            assertEquals(0.25, weakMinInlierRatio)
            assertEquals(0.10, weakMinCoverage)
            assertEquals(3, weakMinGridCells)
            assertEquals(4, coverageGridSize)
            assertEquals(20, strongMinRatioMatches)
            assertEquals(15, strongMinInliers)
            assertEquals(0.35, strongMinInlierRatio)
            assertEquals(0.20, strongMinCoverage)
            assertEquals(4.0, strongMaxMedianErrorPx)
            assertEquals(0.20, homographyAreaRatioMin)
            assertEquals(5.0, homographyAreaRatioMax)
            assertEquals(4.0, homographyMaxOppositeEdgeRatio)
        }
        with(seed.ranking) {
            assertEquals(0.08, duplicateFrontMargin)
            assertEquals(0.40, compositeFrontWeight)
            assertEquals(0.60, compositeBackWeight)
            assertEquals(0.70, autoCompositeMin)
            assertEquals(0.12, autoMarginOverRunnerUp)
            assertEquals(0.65, duplicateFrontBackMinScore)
            assertEquals(0.40, chooserCompositeMin)
            assertEquals(0.70, minContourConfidence)
        }
    }

    @Test
    fun jsonRoundTripPreservesSeed() {
        val text = RecognitionProfileJson.encode(seed)
        val parsed = RecognitionProfile.fromJson(text)
        assertEquals(seed, parsed)
    }

    @Test
    fun unknownProfileIdFailsClosed() {
        val base = RecognitionProfileJson.encode(seed)
        assertFailsWith<IllegalArgumentException> {
            RecognitionProfile.fromJson(base.replace("\"mvp-orb-v1\"", "\"mvp-orb-v9\""))
        }
    }

    @Test
    fun unknownJsonFieldFailsClosed() {
        val base = RecognitionProfileJson.encode(seed)
        val withExtra = base.replace(
            "\"profileId\": \"mvp-orb-v1\"",
            "\"profileId\": \"mvp-orb-v1\", \"surprise\": true",
        )
        assertFailsWith<Exception> { RecognitionProfile.fromJson(withExtra) }
    }

    @Test
    fun missingFieldFailsClosed() {
        val base = RecognitionProfileJson.encode(seed)
        val missing = base.replace("\"canonicalLongEdgePx\": 1600,", "")
        assertTrue(missing != base)
        assertFailsWith<Exception> { RecognitionProfile.fromJson(missing) }
    }

    @Test
    fun missingContourConfidenceFailsClosed() {
        val base = RecognitionProfileJson.encode(seed)
        val missing = base.replace("\"minContourConfidence\": 0.7", "")
        assertTrue(missing != base)
        assertFailsWith<Exception> { RecognitionProfile.fromJson(missing) }
    }

    @Test
    fun outOfRangeThresholdsFailClosed() {
        val bad = RecognitionProfileJson.encode(
            seed.copy(
                ranking = seed.ranking.copy(compositeBackWeight = 0.50),
            ),
        )
        assertFailsWith<IllegalArgumentException> { RecognitionProfile.fromJson(bad) }
    }
}
