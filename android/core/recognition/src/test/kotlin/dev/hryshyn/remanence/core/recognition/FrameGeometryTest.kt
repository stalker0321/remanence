package dev.hryshyn.remanence.core.recognition

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * S1 frame-geometry contract tests, mirroring the frozen
 * `evidence/frame_first_v2/tests/test_frame_first_v2.py::UniversalFrameGeometryTest`
 * vectors ((height, width) viewports) plus the freeze spec examples. Expected
 * integers were produced by the frozen Python equations (identical IEEE-754
 * double order) and cross-checked against the frozen assertions
 * `(460,440,2540,3560)` and `(293,79,667,641)`.
 */
class FrameGeometryTest {

    private data class Viewport(val name: String, val height: Int, val width: Int)

    private val viewports = listOf(
        Viewport("portrait_3_4", 4000, 3000),
        Viewport("portrait_phone", 2400, 1080),
        Viewport("square", 1000, 1000),
        Viewport("tall_1_2", 2000, 1000),
        Viewport("tall_9_16", 1920, 1080),
        Viewport("landscape_4_3", 720, 960),
        Viewport("landscape_16_9", 1080, 1920),
        Viewport("landscape_small", 480, 640),
    )

    private val expectedBounds = mapOf(
        "portrait_3_4" to FrameGeometry.Bounds(460, 440, 2540, 3560),
        "portrait_phone" to FrameGeometry.Bounds(166, 638, 915, 1761),
        "square" to FrameGeometry.Bounds(240, 110, 760, 890),
        "tall_1_2" to FrameGeometry.Bounds(153, 480, 846, 1520),
        "tall_9_16" to FrameGeometry.Bounds(166, 398, 915, 1521),
        "landscape_4_3" to FrameGeometry.Bounds(293, 79, 667, 641),
        "landscape_16_9" to FrameGeometry.Bounds(679, 119, 1241, 961),
        "landscape_small" to FrameGeometry.Bounds(195, 53, 445, 427),
    )

    @Test
    fun contractConstantsMatchFreeze() {
        assertEquals(0.6934, FrameGeometry.MAX_WIDTH_FRACTION)
        assertEquals(0.78, FrameGeometry.MAX_HEIGHT_FRACTION)
        assertEquals(2.0, FrameGeometry.ASPECT_WIDTH)
        assertEquals(3.0, FrameGeometry.ASPECT_HEIGHT)
    }

    @Test
    fun documentedPortraitExampleIsExact() {
        assertEquals(
            FrameGeometry.Bounds(460, 440, 2540, 3560),
            FrameGeometry.bounds(imageWidth = 3000, imageHeight = 4000),
        )
    }

    @Test
    fun documentedLandscapeExampleStaysPortraitTwoToThree() {
        // 960x720 keeps a portrait 2:3 frame, NOT 666x562.
        val bounds = FrameGeometry.bounds(imageWidth = 960, imageHeight = 720)
        assertEquals(374, bounds.width)
        assertEquals(562, bounds.height)
        assertTrue(bounds.width != 666 || bounds.height != 562)
        assertEquals(FrameGeometry.Bounds(293, 79, 667, 641), bounds)
    }

    @Test
    fun everyViewportMatchesFrozenBounds() {
        for (v in viewports) {
            assertEquals(
                expectedBounds.getValue(v.name),
                FrameGeometry.bounds(v.width, v.height),
                "viewport ${v.name}",
            )
        }
    }

    @Test
    fun frameIsAlwaysPortraitTwoToThree() {
        for (v in viewports) {
            val g = FrameGeometry.geometry(v.width, v.height)
            assertTrue(abs(g.frameWidth / g.frameHeight - 2.0 / 3.0) < 1e-9, v.name)
            assertTrue(g.frameHeight > g.frameWidth, v.name)
            val b = FrameGeometry.bounds(v.width, v.height)
            assertTrue(b.height > b.width, v.name)
        }
    }

    @Test
    fun frameRespectsMaxFractions() {
        for (v in viewports) {
            val g = FrameGeometry.geometry(v.width, v.height)
            assertTrue(g.frameWidth <= v.width * 0.6934 + 1e-9, v.name)
            assertTrue(g.frameHeight <= v.height * 0.78 + 1e-9, v.name)
        }
    }

    @Test
    fun frameUsesTheBindingConstraint() {
        // 3000x4000 and 960x720 bind on height*0.78*2/3.
        for ((w, h) in listOf(3000 to 4000, 960 to 720)) {
            val g = FrameGeometry.geometry(w, h)
            assertTrue(abs(g.frameHeight - g.maxHeight) < 1e-6, "$w x $h")
            assertTrue(g.frameWidth < g.maxWidth, "$w x $h")
        }
        // Extremely tall 1000x2000 binds on width instead.
        val tall = FrameGeometry.geometry(1000, 2000)
        assertEquals(tall.maxWidth, tall.frameWidth)
        assertTrue(tall.frameHeight < tall.maxHeight)
    }

    @Test
    fun frameIsCentred() {
        for (v in viewports) {
            val g = FrameGeometry.geometry(v.width, v.height)
            val left = g.x0
            val right = v.width - (g.x0 + g.frameWidth)
            val top = g.y0
            val bottom = v.height - (g.y0 + g.frameHeight)
            assertTrue(abs(left - right) < 1e-9, v.name)
            assertTrue(abs(top - bottom) < 1e-9, v.name)
        }
    }

    @Test
    fun boundsStayInsideImageAndNonEmpty() {
        val sweep = viewports.map { it.width to it.height } + listOf(
            1 to 1, 2 to 2, 3 to 5, 320 to 240, 640 to 480, 1080 to 1080,
            4032 to 3024, 3024 to 4032, 100 to 2000, 2000 to 100,
        )
        for ((w, h) in sweep) {
            val b = FrameGeometry.bounds(w, h)
            assertTrue(b.x0 >= 0 && b.y0 >= 0, "$w x $h: $b")
            assertTrue(b.x1 <= w && b.y1 <= h, "$w x $h: $b")
            assertTrue(b.width > 0 && b.height > 0, "$w x $h: $b")
        }
    }

    @Test
    fun boundsCornerIsNeverReRounded() {
        // x1 == x0 + rounded-width, exactly as run.py assigns.
        for (v in viewports) {
            val g = FrameGeometry.geometry(v.width, v.height)
            val b = FrameGeometry.bounds(v.width, v.height)
            assertEquals(b.x0 + b.width, b.x1, v.name)
            assertEquals(b.y0 + b.height, b.y1, v.name)
            assertTrue(g.x0 >= 0.0, v.name)
        }
    }

    @Test
    fun bankersRoundingMatchesPythonRound() {
        assertEquals(0, FrameGeometry.bankersRound(0.5))
        assertEquals(2, FrameGeometry.bankersRound(1.5))
        assertEquals(2, FrameGeometry.bankersRound(2.5))
        assertEquals(4, FrameGeometry.bankersRound(3.5))
        assertEquals(0, FrameGeometry.bankersRound(0.4))
        assertEquals(1, FrameGeometry.bankersRound(0.6))
        assertEquals(293, FrameGeometry.bankersRound(292.8))
        assertEquals(79, FrameGeometry.bankersRound(79.2))
        assertEquals(374, FrameGeometry.bankersRound(374.4))
        assertEquals(562, FrameGeometry.bankersRound(561.6))
    }

    @Test
    fun nonPositiveDimensionsAreRejected() {
        assertFailsWith<IllegalArgumentException> { FrameGeometry.geometry(0, 100) }
        assertFailsWith<IllegalArgumentException> { FrameGeometry.geometry(100, 0) }
        assertFailsWith<IllegalArgumentException> { FrameGeometry.geometry(-3, 100) }
        assertFailsWith<IllegalArgumentException> { FrameGeometry.bounds(100, -1) }
        assertFailsWith<IllegalArgumentException> { FrameGeometry.bankersRound(-0.5) }
    }
}
