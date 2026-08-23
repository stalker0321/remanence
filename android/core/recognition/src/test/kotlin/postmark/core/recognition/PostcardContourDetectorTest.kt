package postmark.core.recognition

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class PostcardContourDetectorTest {

    private val profile = RecognitionProfile.mvpOrbV1()

    private val frameW = 400
    private val frameH = 300

    @BeforeTest
    fun loadNative() {
        // Desktop OpenCV natives (same 4.10 API as the production Android AAR).
        runCatching { System.loadLibrary("opencv_java4100") }
            .onFailure { assumeTrue("desktop OpenCV natives unavailable: $it", false) }
    }

    private fun blankFrame(): IntArray = IntArray(frameW * frameH) { 0xFF202020.toInt() }

    private fun drawFilledRect(
        pixels: IntArray,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        argb: Int = 0xFFF2F2F2.toInt(),
    ) {
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                pixels[y * frameW + x] = argb
            }
        }
    }

    @AfterTest
    fun sanity() = Unit

    @Test
    fun singleCenteredPostcardYieldsCandidateWithExpectedCorners() {
        val pixels = blankFrame()
        val rectX0 = 60
        val rectY0 = 50
        val rectX1 = 340
        val rectY1 = 250
        drawFilledRect(pixels, rectX0, rectY0, rectX1, rectY1)

        val candidates = PostcardContourDetector(profile).detect(pixels, frameW, frameH)
        assertTrue(candidates.isNotEmpty(), "expected at least one candidate")

        val best = candidates.first()
        assertTrue(best.areaRatio > 0.3, "areaRatio=${best.areaRatio}")
        assertTrue(best.rectangularity > 0.85, "rectangularity=${best.rectangularity}")

        val expected = listOf(
            PointD(rectX0.toDouble(), rectY0.toDouble()),
            PointD(rectX1.toDouble(), rectY0.toDouble()),
            PointD(rectX1.toDouble(), rectY1.toDouble()),
            PointD(rectX0.toDouble(), rectY1.toDouble()),
        )
        best.corners.zip(expected).forEach { (actual, want) ->
            assertTrue(kotlin.math.abs(actual.x - want.x) <= 6.0, "corner x ${actual.x} vs ${want.x}")
            assertTrue(kotlin.math.abs(actual.y - want.y) <= 6.0, "corner y ${actual.y} vs ${want.y}")
        }
    }

    @Test
    fun blankFrameHasNoCandidates() {
        val candidates = PostcardContourDetector(profile).detect(blankFrame(), frameW, frameH)
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun twoRectsProduceAtLeastTwoCandidatesSortedByArea() {
        val pixels = blankFrame()
        drawFilledRect(pixels, 30, 30, 200, 200)
        drawFilledRect(pixels, 220, 80, 380, 260)
        val candidates = PostcardContourDetector(profile).detect(pixels, frameW, frameH)
        assertTrue(candidates.size >= 2, "candidates=${candidates.size}")
        for (i in 1 until candidates.size) {
            assertTrue(candidates[i - 1].areaRatio >= candidates[i].areaRatio)
        }
        // Canonical corner order: top-left first.
        val first = candidates.first().corners
        assertEquals(4, first.size)
        assertTrue(first[0].x < first[1].x && first[0].y < first[3].y)
    }

    @Test
    fun thinSliverBelowLooseAreaFloorIsIgnored() {
        val pixels = blankFrame()
        drawFilledRect(pixels, 10, 10, 390, 12) // 2px tall strip
        val candidates = PostcardContourDetector(profile).detect(pixels, frameW, frameH)
        assertTrue(candidates.all { it.areaRatio >= PostcardContourDetector.MIN_CANDIDATE_AREA_RATIO })
    }
}
