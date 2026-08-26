package dev.hryshyn.remanence.core.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CornerGeometryTest {

    private val rectangle = listOf(
        PointD(0.0, 0.0), // TL
        PointD(100.0, 0.0), // TR
        PointD(100.0, 50.0), // BR
        PointD(0.0, 50.0), // BL
    )

    @Test
    fun canonicalOrderStartsTopLeftAndRunsClockwise() {
        val ordered = CornerGeometry.orderClockwiseFromTopLeft(rectangle)
        assertEquals(PointD(0.0, 0.0), ordered[0])
        assertEquals(PointD(100.0, 0.0), ordered[1])
        assertEquals(PointD(100.0, 50.0), ordered[2])
        assertEquals(PointD(0.0, 50.0), ordered[3])
    }

    @Test
    fun rotationOfInputDoesNotChangeCanonicalOutput() {
        val shuffled = listOf(
            rectangle[2],
            rectangle[3],
            rectangle[1],
            rectangle[0],
        )
        assertEquals(
            CornerGeometry.orderClockwiseFromTopLeft(rectangle),
            CornerGeometry.orderClockwiseFromTopLeft(shuffled),
        )
    }

    @Test
    fun rotatedQuadCanonicalizesStably() {
        // Rectangle rotated by ~30 degrees around its center.
        val angle = Math.toRadians(30.0)
        fun rotate(p: PointD): PointD {
            val dx = p.x - 50.0
            val dy = p.y - 25.0
            return PointD(
                50.0 + dx * kotlin.math.cos(angle) - dy * kotlin.math.sin(angle),
                25.0 + dx * kotlin.math.sin(angle) + dy * kotlin.math.cos(angle),
            )
        }
        val rotated = rectangle.map(::rotate)
        val ordered = CornerGeometry.orderClockwiseFromTopLeft(rotated)
        assertTrue(CornerGeometry.isConvex(ordered))
        assertFalse(CornerGeometry.selfIntersects(ordered))
        assertEquals(ordered, CornerGeometry.orderClockwiseFromTopLeft(ordered.reversed()))
    }

    @Test
    fun duplicateAndWrongCountInputsRejected() {
        assertFailsWith<IllegalArgumentException> {
            CornerGeometry.orderClockwiseFromTopLeft(rectangle.take(3))
        }
        assertFailsWith<IllegalArgumentException> {
            CornerGeometry.orderClockwiseFromTopLeft(listOf(rectangle[0], rectangle[1], rectangle[2], rectangle[0]))
        }
    }

    @Test
    fun bowtieSequenceIsSelfIntersecting() {
        val bowtie = listOf(
            PointD(0.0, 0.0),
            PointD(10.0, 10.0),
            PointD(10.0, 0.0),
            PointD(0.0, 10.0),
        )
        assertTrue(CornerGeometry.selfIntersects(bowtie))
        val result = CornerGeometry.validateQuad(bowtie)
        assertTrue(result is CornerGeometry.QuadValidation.Invalid)
        assertEquals(
            CornerGeometry.QuadValidation.Reason.SELF_INTERSECTING,
            (result as CornerGeometry.QuadValidation.Invalid).reason,
        )
    }

    @Test
    fun concaveQuadIsNotConvex() {
        val concave = listOf(
            PointD(0.0, 0.0),
            PointD(10.0, 0.0),
            PointD(5.0, 4.0), // notch pulled inward
            PointD(0.0, 10.0),
        )
        assertFalse(CornerGeometry.isConvex(concave))
        val result = CornerGeometry.validateQuad(concave)
        assertEquals(
            CornerGeometry.QuadValidation.Reason.NOT_CONVEX,
            (result as CornerGeometry.QuadValidation.Invalid).reason,
        )
    }

    @Test
    fun clockwiseRectangleValidatesWithPositiveArea() {
        val result = CornerGeometry.validateQuad(rectangle)
        assertTrue(result is CornerGeometry.QuadValidation.Valid)
        assertTrue(CornerGeometry.signedArea((result as CornerGeometry.QuadValidation.Valid).orderedCorners) > 0)
    }

    @Test
    fun counterClockwiseWindingFailsValidation() {
        val ccw = rectangle.reversed()
        val result = CornerGeometry.validateQuad(ccw)
        assertEquals(
            CornerGeometry.QuadValidation.Reason.DEGENERATE_AREA,
            (result as CornerGeometry.QuadValidation.Invalid).reason,
        )
    }

    @Test
    fun tinyAreaBelowMinimumIsDegenerate() {
        val sliver = listOf(
            PointD(0.0, 0.0),
            PointD(2.0, 0.0),
            PointD(2.0, 0.5),
            PointD(0.0, 0.5),
        )
        val result = CornerGeometry.validateQuad(sliver, minArea = 10.0)
        assertEquals(
            CornerGeometry.QuadValidation.Reason.DEGENERATE_AREA,
            (result as CornerGeometry.QuadValidation.Invalid).reason,
        )
    }
}
