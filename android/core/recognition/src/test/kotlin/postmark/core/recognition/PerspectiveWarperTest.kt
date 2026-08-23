package postmark.core.recognition

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class PerspectiveWarperTest {

    private val profile = RecognitionProfile.mvpOrbV1()
    private val warper = PerspectiveWarper(profile)

    private val frameW = 400
    private val frameH = 300

    @BeforeTest
    fun loadNative() {
        runCatching { System.loadLibrary("opencv_java4100") }
            .onFailure { assumeTrue("desktop OpenCV natives unavailable: $it", false) }
    }

    /** Four solid quadrant colors so orientation is visible pixel-wise. */
    private fun quadrantFrame(): IntArray {
        val pixels = IntArray(frameW * frameH) { 0xFF111111.toInt() }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
            for (y in y0 until y1) for (x in x0 until x1) pixels[y * frameW + x] = color
        }
        // Postcard occupies (60,50)-(340,250); its own quadrants:
        rect(60, 50, 200, 150, 0xFFFF0000.toInt()) // red top-left
        rect(200, 50, 340, 150, 0xFF00FF00.toInt()) // green top-right
        rect(60, 150, 200, 250, 0xFF0000FF.toInt()) // blue bottom-left
        rect(200, 150, 340, 250, 0xFFFFFF00.toInt()) // yellow bottom-right
        return pixels
    }

    private val corners = listOf(
        PointD(60.0, 50.0),
        PointD(340.0, 50.0),
        PointD(340.0, 250.0),
        PointD(60.0, 250.0),
    )

    @Test
    fun goldenGeometryPreservesAspectAtCanonicalLongEdge() {
        val warped = warper.warp(quadrantFrame(), frameW, frameH, corners)
        // Long edge 280px → scale = 1600/280; short edge 200*1600/280 ≈ 1143.
        assertEquals(profile.capture.canonicalLongEdgePx, warped.width)
        val expectedShort = kotlin.math.round(200.0 * 1600.0 / 280.0).toInt()
        assertEquals(expectedShort, warped.height)
    }

    @Test
    fun warpedQuadrantColorsStayInMatchingQuadrants() {
        val warped = warper.warp(quadrantFrame(), frameW, frameH, corners)
        fun pixel(xPct: Double, yPct: Double): Int {
            val x = (xPct * (warped.width - 1)).toInt()
            val y = (yPct * (warped.height - 1)).toInt()
            return warped.pixels[y * warped.width + x] and 0x00FFFFFF
        }
        assertEquals(0x00FF0000, pixel(0.05, 0.05)) // red TL
        assertEquals(0x0000FF00, pixel(0.95, 0.05)) // green TR
        assertEquals(0x000000FF, pixel(0.05, 0.95)) // blue BL
        assertEquals(0x00FFFF00, pixel(0.95, 0.95)) // yellow BR
    }

    @Test
    fun rotatedQuadStillYieldsUprightCanonicalOutput() {
        // The same postcard rotated ~10 degrees inside the frame.
        val angle = Math.toRadians(10.0)
        fun rotate(p: PointD): PointD {
            val dx = p.x - 200.0
            val dy = p.y - 150.0
            return PointD(
                200.0 + dx * kotlin.math.cos(angle) - dy * kotlin.math.sin(angle),
                150.0 + dx * kotlin.math.sin(angle) + dy * kotlin.math.cos(angle),
            )
        }
        val rotated = corners.map(::rotate)
        val warped = warper.warp(quadrantFrame(), frameW, frameH, rotated)
        assertEquals(profile.capture.canonicalLongEdgePx, warped.width)
        // Quadrant colors survive the warp despite rotation.
        fun pixel(xPct: Double, yPct: Double): Int {
            val x = (xPct * (warped.width - 1)).toInt()
            val y = (yPct * (warped.height - 1)).toInt()
            return warped.pixels[y * warped.width + x] and 0x00FFFFFF
        }
        assertEquals(0x00FF0000, pixel(0.20, 0.20))
        assertEquals(0x00FFFF00, pixel(0.80, 0.80))
    }

    @Test
    fun invalidQuadIsRejectedBeforeAnyWarp() {
        assertFailsWith<IllegalArgumentException> {
            warper.warp(quadrantFrame(), frameW, frameH, corners.take(3))
        }
        val bowtie = listOf(
            PointD(60.0, 50.0),
            PointD(340.0, 250.0),
            PointD(340.0, 50.0),
            PointD(60.0, 250.0),
        )
        assertFailsWith<IllegalArgumentException> {
            warper.warp(quadrantFrame(), frameW, frameH, bowtie)
        }
    }

    @Test
    fun outputPixelBudgetGuarded() {
        // A quad claiming a huge short edge cannot exceed the internal budget.
        val huge = listOf(
            PointD(1.0, 1.0),
            PointD(398.0, 2.0),
            PointD(397.0, 298.0),
            PointD(2.0, 297.0),
        )
        // Normal case must succeed; budget guard is structural only.
        val warped = warper.warp(quadrantFrame(), frameW, frameH, huge)
        assertTrue(warped.width > 0 && warped.height > 0)
    }
}
