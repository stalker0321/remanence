package dev.hryshyn.remanence.core.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManualCropQuadTest {

    private val frameW = 800
    private val frameH = 600

    private fun quad(vararg points: PointD) = ManualCropQuad(points.toList(), frameW, frameH)

    private val validRect = quad(
        PointD(100.0, 100.0),
        PointD(700.0, 90.0),
        PointD(710.0, 500.0),
        PointD(90.0, 510.0),
    )

    @Test
    fun validUserDraggedCornersCanonicalize() {
        val result = validRect.validate()
        assertTrue(result is ManualCropQuad.Validation.Valid)
        val ordered = (result as ManualCropQuad.Validation.Valid).orderedCorners
        // Starts at top-left-most corner, clockwise.
        assertTrue(ordered[0].x + ordered[0].y <= ordered[2].x + ordered[2].y)
        assertTrue(CornerGeometry.signedArea(ordered) > 0)
    }

    @Test
    fun cornerOutsideFrameRejected() {
        val outside = quad(
            PointD(100.0, 100.0),
            PointD(850.0, 100.0), // beyond frameW=800
            PointD(700.0, 500.0),
            PointD(100.0, 500.0),
        )
        val result = outside.validate()
        assertEquals(
            ManualCropQuad.Validation.Reason.OUT_OF_FRAME,
            (result as ManualCropQuad.Validation.Invalid).reason,
        )
    }

    @Test
    fun bowtieRejectedAsSelfIntersecting() {
        val bowtie = quad(
            PointD(100.0, 100.0),
            PointD(700.0, 500.0),
            PointD(700.0, 100.0),
            PointD(100.0, 500.0),
        )
        val result = bowtie.validate()
        assertEquals(
            ManualCropQuad.Validation.Reason.SELF_INTERSECTING,
            (result as ManualCropQuad.Validation.Invalid).reason,
        )
    }

    @Test
    fun duplicateCornerRejected() {
        val dup = quad(
            PointD(100.0, 100.0),
            PointD(700.0, 100.0),
            PointD(700.0, 100.0),
            PointD(100.0, 500.0),
        )
        val result = dup.validate()
        assertEquals(
            ManualCropQuad.Validation.Reason.DUPLICATE_CORNERS,
            (result as ManualCropQuad.Validation.Invalid).reason,
        )
    }

    @Test
    fun tinySliverRejectedByMinArea() {
        val sliver = quad(
            PointD(100.0, 100.0),
            PointD(130.0, 100.0),
            PointD(130.0, 102.0),
            PointD(100.0, 102.0),
        )
        val result = sliver.validate()
        assertEquals(
            ManualCropQuad.Validation.Reason.DEGENERATE_AREA,
            (result as ManualCropQuad.Validation.Invalid).reason,
        )
    }

    @Test
    fun wrongPointCountRejected() {
        val three = ManualCropQuad(validRect.corners.take(3), frameW, frameH)
        val result = three.validate()
        assertEquals(
            ManualCropQuad.Validation.Reason.WRONG_POINT_COUNT,
            (result as ManualCropQuad.Validation.Invalid).reason,
        )
    }
}
