package dev.hryshyn.remanence.core.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecognitionProfileTest {

    private val seed = RecognitionProfile.postcardSiftRootSiftV1()

    @Test
    fun seedMatchesEveryDocumentedValue() {
        assertEquals(RecognitionProfile.SIFT_ROOTSIFT_V1_ID, seed.profileId)
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
        with(seed.sift) {
            assertEquals(0, nfeatures)
            assertEquals(3, octaveLayers)
            assertEquals(0.018, contrastThreshold)
            assertEquals(12.0, edgeThreshold)
            assertEquals(1.6, sigma)
            assertEquals(6, gridSize)
            assertEquals(45, maxPerCell)
            assertEquals(1500, maxKeypoints)
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
            assertEquals(0.70, autoFrontMin)
            assertEquals(0.12, autoMarginOverRunnerUp)
            assertEquals(0.65, duplicateFrontMinScore)
            assertEquals(0.40, chooserFrontMin)
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
            RecognitionProfile.fromJson(base.replace("\"postcard-sift-rootsift-v1\"", "\"postcard-sift-rootsift-v9\""))
        }
    }

    @Test
    fun unknownJsonFieldFailsClosed() {
        val base = RecognitionProfileJson.encode(seed)
        val withExtra = base.replace(
            "\"profileId\": \"postcard-sift-rootsift-v1\"",
            "\"profileId\": \"postcard-sift-rootsift-v1\", \"surprise\": true",
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
                ranking = seed.ranking.copy(autoFrontMin = 1.5),
            ),
        )
        assertFailsWith<IllegalArgumentException> { RecognitionProfile.fromJson(bad) }
        val badChooser = RecognitionProfileJson.encode(
            seed.copy(
                ranking = seed.ranking.copy(chooserFrontMin = 0.80),
            ),
        )
        assertFailsWith<IllegalArgumentException> { RecognitionProfile.fromJson(badChooser) }
    }
}
