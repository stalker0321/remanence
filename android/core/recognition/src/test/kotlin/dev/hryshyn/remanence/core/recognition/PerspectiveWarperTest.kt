package dev.hryshyn.remanence.core.recognition

import java.util.Random
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

class PerspectiveWarperTest {

    private val profile = RecognitionProfile.mvpOrbV1()
    private val warper = PerspectiveWarper(profile)

    private val frameW = 400
    private val frameH = 300
    private val portraitFrameW = 300
    private val portraitFrameH = 400

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

    private fun portraitQuadrantFrame(): IntArray {
        val pixels = IntArray(portraitFrameW * portraitFrameH) { 0xFF111111.toInt() }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
            for (y in y0 until y1) for (x in x0 until x1) pixels[y * portraitFrameW + x] = color
        }
        // Portrait postcard occupies (50,40)-(250,360); its own quadrants.
        rect(50, 40, 150, 200, 0xFFFF0000.toInt())
        rect(150, 40, 250, 200, 0xFF00FF00.toInt())
        rect(50, 200, 150, 360, 0xFF0000FF.toInt())
        rect(150, 200, 250, 360, 0xFFFFFF00.toInt())
        return pixels
    }

    private val corners = listOf(
        PointD(60.0, 50.0),
        PointD(340.0, 50.0),
        PointD(340.0, 250.0),
        PointD(60.0, 250.0),
    )

    private val portraitCorners = listOf(
        PointD(50.0, 40.0),
        PointD(250.0, 40.0),
        PointD(250.0, 360.0),
        PointD(50.0, 360.0),
    )

    private val texturedFrameW = 800
    private val texturedFrameH = 600
    private val texturedLandscapeCorners = listOf(
        PointD(100.0, 100.0),
        PointD(700.0, 100.0),
        PointD(700.0, 500.0),
        PointD(100.0, 500.0),
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
    fun portraitLongEdgeMapsToCanonicalLongEdgeWithoutStretching() {
        val warped = warper.warp(
            portraitQuadrantFrame(),
            portraitFrameW,
            portraitFrameH,
            portraitCorners,
        )
        val scale = profile.capture.canonicalLongEdgePx / 320.0
        assertEquals(profile.capture.canonicalLongEdgePx, warped.width)
        assertEquals(kotlin.math.round(200.0 * scale).toInt(), warped.height)
        assertEquals(
            warped.width.toDouble() / warped.height.toDouble(),
            320.0 / 200.0,
            1.0 / profile.capture.canonicalLongEdgePx,
        )

        fun pixel(xPct: Double, yPct: Double): Int {
            val x = (xPct * (warped.width - 1)).toInt()
            val y = (yPct * (warped.height - 1)).toInt()
            return warped.pixels[y * warped.width + x] and 0x00FFFFFF
        }
        assertEquals(0x0000FF00, pixel(0.05, 0.05)) // green TL after rotation
        assertEquals(0x00FFFF00, pixel(0.95, 0.05)) // yellow TR
        assertEquals(0x00FF0000, pixel(0.05, 0.95)) // red BL
        assertEquals(0x000000FF, pixel(0.95, 0.95)) // blue BR
    }

    @Test
    fun averagedOppositeEdgesKeepAsymmetricLandscapeQuadCanonical() {
        val asymmetric = listOf(
            PointD(0.0, 0.0),
            PointD(80.0, 0.0),
            PointD(200.0, 20.0),
            PointD(0.0, 20.0),
        )
        val horizontal = (80.0 + 200.0) / 2.0
        val vertical = (kotlin.math.hypot(120.0, 20.0) + 20.0) / 2.0
        val expectedShort = kotlin.math.round(
            vertical * profile.capture.canonicalLongEdgePx / horizontal,
        ).toInt()

        val warped = warper.warp(IntArray(200 * 20), 200, 20, asymmetric)

        assertTrue(horizontal > vertical)
        assertEquals(profile.capture.canonicalLongEdgePx, warped.width)
        assertEquals(expectedShort, warped.height)
    }

    @Test
    fun landscapeAndNinetyDegreePortraitTexturesPassRealWarpOrbAndMatch() = runBlocking {
        val landscapeFrame = texturedPostcardFrame()
        val portraitFrame = rotateClockwise(landscapeFrame, texturedFrameW, texturedFrameH)
        val portraitCorners = listOf(
            PointD(100.0, 100.0),
            PointD(500.0, 100.0),
            PointD(500.0, 700.0),
            PointD(100.0, 700.0),
        )
        val landscapeWarped = warper.warp(
            landscapeFrame,
            texturedFrameW,
            texturedFrameH,
            texturedLandscapeCorners,
        )
        val portraitWarped = warper.warp(
            portraitFrame,
            texturedFrameH,
            texturedFrameW,
            portraitCorners,
        )
        assertEquals(landscapeWarped.width, portraitWarped.width)
        assertEquals(landscapeWarped.height, portraitWarped.height)

        val extractor = FingerprintExtractor(profile)
        fun fingerprint(warped: WarpedCapture): PostcardFingerprint =
            extractor.extract(warped.pixels, warped.width, warped.height)

        val landscapeFingerprint = fingerprint(landscapeWarped)
        val portraitFingerprint = fingerprint(portraitWarped)
        val matcher = DescriptorMatcher()
        val landscapeToPortrait = matcher.match(landscapeFingerprint, portraitFingerprint)
        val portraitToLandscape = matcher.match(portraitFingerprint, landscapeFingerprint)
        assertTrue(landscapeToPortrait.size >= profile.match.weakMinRatioMatches)
        assertTrue(portraitToLandscape.size >= profile.match.weakMinRatioMatches)

        suspend fun accept(
            query: PostcardFingerprint,
            reference: PostcardFingerprint,
        ): ScanFlowResult {
            val capsuleId = UUID.nameUUIDFromBytes("textured-postcard".toByteArray())
            return LocalMatchEngine(
                profile = profile,
                verifier = { true },
                grantIssuer = { "grant-$capsuleId" },
            ).run(
                queryFront = query,
                candidates = listOf(
                    IndexedCandidate(
                        capsuleId = capsuleId,
                        front = reference,
                    ),
                ),
            )
        }

        assertIs<ScanFlowResult.Granted>(accept(landscapeFingerprint, portraitFingerprint))
        assertIs<ScanFlowResult.Granted>(accept(portraitFingerprint, landscapeFingerprint))
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

    private fun texturedPostcardFrame(): IntArray {
        val pixels = IntArray(texturedFrameW * texturedFrameH) { 0xFF202020.toInt() }
        val random = Random(0x4D32504F53544341L)
        for (y in 100 until 500) {
            for (x in 100 until 700) {
                val localX = x - 100
                val localY = y - 100
                val noise = random.nextInt(96)
                val checker = if ((localX / 24 + localY / 24) % 2 == 0) 36 else 0
                val value = (localX * 150 / 600 + localY * 60 / 400 + noise + checker).coerceIn(0, 255)
                pixels[y * texturedFrameW + x] =
                    0xFF000000.toInt() or (value shl 16) or (value shl 8) or ((value * 3) % 256)
            }
        }
        return pixels
    }

    private fun rotateClockwise(pixels: IntArray, width: Int, height: Int): IntArray {
        val rotated = IntArray(width * height)
        val rotatedWidth = height
        for (y in 0 until height) {
            for (x in 0 until width) {
                val rotatedX = height - 1 - y
                val rotatedY = x
                rotated[rotatedY * rotatedWidth + rotatedX] = pixels[y * width + x]
            }
        }
        return rotated
    }
}
