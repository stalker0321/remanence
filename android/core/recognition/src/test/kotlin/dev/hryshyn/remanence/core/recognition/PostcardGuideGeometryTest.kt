package dev.hryshyn.remanence.core.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostcardGuideGeometryTest {

    private val profile = RecognitionProfile.mvpOrbV1()

    @Test
    fun guideCropUsesAdaptiveGeometryAcrossPreviewAndCaptureAspects() {
        val frames = listOf(
            9 to 20,
            3 to 4,
            4 to 3,
            16 to 9,
        )

        for ((width, height) in frames) {
            val guide = PostcardGuideGeometry.normalizedFor(width, height)
            val corners = guide.toPixels(width, height)
            val cropWidth = corners[1].x - corners[0].x
            val cropHeight = corners[3].y - corners[0].y
            val expectedAspect = if (width >= height) {
                PostcardGuideGeometry.LANDSCAPE_ASPECT_RATIO
            } else {
                PostcardGuideGeometry.PORTRAIT_ASPECT_RATIO
            }

            assertEquals(expectedAspect, cropWidth / cropHeight, 1e-9)
            assertTrue(
                guide.width * guide.height >= profile.capture.minCardAreaRatio,
                "guide area must meet the capture minimum for ${width}:${height}",
            )
            assertEquals(guide, PostcardGuideGeometry.normalizedFor(width.toDouble(), height.toDouble()))
            assertEquals(guide.toOverlay(width, height), PostcardGuideGeometry.overlayFor(width, height))
            assertTrue(corners.all { it.x in 0.0..width.toDouble() && it.y in 0.0..height.toDouble() })
        }
    }

    @Test
    fun productionPortraitPostcardFallbackKeepsPortraitShapeAndMinimumArea() {
        val selected = PostcardCropSelector(profile).select(emptyList(), 1080, 2400)

        assertTrue(selected.usedGuideFallback)
        assertEquals(PostcardGuideGeometry.PORTRAIT_ASPECT_RATIO, edgeRatio(selected.candidate.corners), 1e-9)
        assertTrue(selected.candidate.areaRatio >= profile.capture.minCardAreaRatio)
        assertEquals(
            PostcardGuideGeometry.normalizedFor(1080, 2400).toPixels(1080, 2400),
            selected.candidate.corners,
        )
    }

    @Test
    fun absentContourGetsBoundedGuideAlignedCentralFallback() {
        val selected = PostcardCropSelector(profile).select(emptyList(), 1600, 1200)

        assertTrue(selected.usedGuideFallback)
        assertTrue(selected.candidate.areaRatio >= profile.capture.minCardAreaRatio)
        assertEquals(PostcardGuideGeometry.LANDSCAPE_ASPECT_RATIO, edgeRatio(selected.candidate.corners), 1e-9)
        assertEquals(
            PostcardGuideGeometry.normalizedFor(1600, 1200).toPixels(1600, 1200),
            selected.candidate.corners,
        )
    }

    @Test
    fun nonEmptyLowAreaContourUsesGuideFallback() {
        val selected = PostcardCropSelector(profile).select(
            listOf(axisAligned(400.0, 300.0, 1200.0, 900.0, areaRatio = 0.25)),
            1600,
            1200,
        )

        assertTrue(selected.usedGuideFallback)
    }

    @Test
    fun nonEmptyPoorRectangularityContourUsesGuideFallback() {
        val selected = PostcardCropSelector(profile).select(
            listOf(axisAligned(160.0, 160.0, 1440.0, 1040.0, areaRatio = 0.60, rectangularity = 0.65)),
            1600,
            1200,
        )

        assertTrue(selected.usedGuideFallback)
    }

    @Test
    fun nonEmptyImplausibleAspectContourUsesGuideFallback() {
        val selected = PostcardCropSelector(profile).select(
            listOf(axisAligned(100.0, 350.0, 1500.0, 850.0, areaRatio = 0.49)),
            1600,
            1200,
        )

        assertTrue(selected.usedGuideFallback)
    }

    @Test
    fun credibleContourIsPreferredOverGuideFallback() {
        val contour = QuadCandidate(
            corners = listOf(
                PointD(180.0, 160.0),
                PointD(1420.0, 130.0),
                PointD(1450.0, 1030.0),
                PointD(150.0, 1060.0),
            ),
            areaRatio = 0.58,
            rectangularity = 0.96,
        )

        val selected = PostcardCropSelector(profile).select(listOf(contour), 1600, 1200)

        assertFalse(selected.usedGuideFallback)
        assertEquals(contour, selected.candidate)
    }

    @Test
    fun bestCredibleContourWinsOverHigherRankedUnusableContour() {
        val unusable = axisAligned(
            left = 100.0,
            top = 350.0,
            right = 1500.0,
            bottom = 850.0,
            areaRatio = 0.70,
            rectangularity = 0.99,
        )
        val credible = axisAligned(
            left = 260.0,
            top = 220.0,
            right = 1340.0,
            bottom = 940.0,
            areaRatio = 0.45,
            rectangularity = 0.90,
        )

        val selected = PostcardCropSelector(profile).select(listOf(unusable, credible), 1600, 1200)

        assertFalse(selected.usedGuideFallback)
        assertEquals(credible, selected.candidate)
    }

    private fun axisAligned(
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        areaRatio: Double,
        rectangularity: Double = 1.0,
    ) = QuadCandidate(
        corners = listOf(
            PointD(left, top),
            PointD(right, top),
            PointD(right, bottom),
            PointD(left, bottom),
        ),
        areaRatio = areaRatio,
        rectangularity = rectangularity,
    )

    private fun edgeRatio(corners: List<PointD>): Double {
        val width = kotlin.math.hypot(corners[1].x - corners[0].x, corners[1].y - corners[0].y)
        val height = kotlin.math.hypot(corners[3].x - corners[0].x, corners[3].y - corners[0].y)
        return width / height
    }
}
