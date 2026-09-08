package dev.hryshyn.remanence.core.recognition

import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class V2LinePostcardLocatorTest {
    private val profile = RecognitionProfile.postcardSiftRootSiftV1()
    private val frameWidth = 400
    private val frameHeight = 300

    @BeforeTest
    fun loadNative() {
        runCatching { System.loadLibrary("opencv_java4100") }
            .onFailure { assumeTrue("desktop OpenCV natives unavailable: $it", false) }
    }

    @Test
    fun realFrameLinesProduceMeasuredRectangularProposal() {
        val pixels = IntArray(frameWidth * frameHeight) { 0xFF202020.toInt() }
        for (y in 50 until 250) {
            for (x in 60 until 340) {
                pixels[y * frameWidth + x] = 0xFFF2F2F2.toInt()
            }
        }

        val candidates = V2LinePostcardLocator(profile).detect(pixels, frameWidth, frameHeight)

        assertTrue(candidates.isNotEmpty(), "expected a line-locator proposal")
        val best = candidates.first()
        assertTrue(best.edgeSupport != null && best.edgeSupport >= 0.70)
        assertTrue(best.rectangularity > 0.85, "rectangularity=${best.rectangularity}")
        assertTrue(best.areaRatio > 0.30, "areaRatio=${best.areaRatio}")
        assertTrue(abs(best.corners.first().x - 60.0) < 18.0)
        assertTrue(abs(best.corners.first().y - 50.0) < 18.0)
    }

    @Test
    fun secondChromaAllocationFailureReleasesFirstChannelAndBaseMats() {
        val pixels = IntArray(frameWidth * frameHeight) { 0xFF202020.toInt() }
        for (y in 50 until 250) {
            for (x in 60 until 340) {
                pixels[y * frameWidth + x] = 0xFFF2F2F2.toInt()
            }
        }
        val allocator = FailingNativeMatAllocator(failAt = 8)

        assertFailsWith<AssertionError> {
            V2LinePostcardLocator(profile, allocator).detect(pixels, frameWidth, frameHeight)
        }

        assertEquals(7, allocator.allocated.size)
        assertTrue(allocator.allocated.all { it.empty() })
    }

    @Test
    fun detectorCreationFailureClearsDetectorAndInitialMats() {
        val allocator = FailingNativeMatAllocator(failAt = Int.MAX_VALUE)
        val detector = RecordingLineSegmentDetector()
        val locator = V2LinePostcardLocator(
            profile,
            allocator,
            NativeOperationFault { stage ->
                if (stage == "after-line-detector-created") {
                    throw IllegalStateException("deterministic detector failure")
                }
            },
            LineSegmentDetectorFactory { detector },
        )

        assertFailsWith<IllegalStateException> {
            locator.detect(rectanglePixels(), frameWidth, frameHeight)
        }

        assertTrue(detector.cleared)
        assertEquals(6, allocator.allocated.size)
        assertTrue(allocator.allocated.all { it.empty() })
    }

    @Test
    fun perSourceAllocationFailureReleasesSourceAndInitialMats() {
        val allocator = FailingNativeMatAllocator(failAt = 10)

        assertFailsWith<AssertionError> {
            V2LinePostcardLocator(profile, allocator).detect(rectanglePixels(), frameWidth, frameHeight)
        }

        assertEquals(9, allocator.allocated.size)
        assertTrue(allocator.allocated.all { it.empty() })
    }

    @Test
    fun sourceOperationFailureReleasesAllPerSourceMats() {
        val allocator = FailingNativeMatAllocator(failAt = Int.MAX_VALUE)
        val locator = V2LinePostcardLocator(
            profile,
            allocator,
            NativeOperationFault { stage ->
                if (stage == "after-source-mats") {
                    throw IllegalStateException("deterministic source operation failure")
                }
            },
        )

        assertFailsWith<IllegalStateException> {
            locator.detect(rectanglePixels(), frameWidth, frameHeight)
        }

        assertEquals(13, allocator.allocated.size)
        assertTrue(allocator.allocated.all { it.empty() })
    }

    @Test
    fun derivedDistanceFailureReleasesInverseAndDistanceMats() {
        val allocator = FailingNativeMatAllocator(failAt = Int.MAX_VALUE)
        val locator = V2LinePostcardLocator(
            profile,
            allocator,
            NativeOperationFault { stage ->
                if (stage == "after-derived-distance") {
                    throw IllegalStateException("deterministic derived operation failure")
                }
            },
        )

        assertFailsWith<IllegalStateException> {
            locator.detect(rectanglePixels(), frameWidth, frameHeight)
        }

        assertTrue(allocator.allocated.size >= 35)
        assertTrue(allocator.allocated.all { it.empty() })
    }

    private fun rectanglePixels(): IntArray = IntArray(frameWidth * frameHeight) { index ->
        val x = index % frameWidth
        val y = index / frameWidth
        if (x in 60 until 340 && y in 50 until 250) 0xFFF2F2F2.toInt() else 0xFF202020.toInt()
    }

    private class RecordingLineSegmentDetector : LineSegmentDetectorPort {
        var cleared = false

        override fun detect(image: org.opencv.core.Mat, lines: org.opencv.core.Mat) = Unit

        override fun clear() {
            cleared = true
        }
    }
}
