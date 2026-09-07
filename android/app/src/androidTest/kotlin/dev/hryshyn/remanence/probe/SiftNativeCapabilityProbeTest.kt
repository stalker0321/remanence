package dev.hryshyn.remanence.probe

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc

/**
 * Native OpenCV feasibility probe for a future SIFT-based matcher.
 *
 * Scope: proves the *shipped* `org.opencv:opencv:4.10.0` Android artifact can
 * compile and execute SIFT extraction, KNN matching primitives, a probe-local
 * RootSIFT normalization, and `findHomography` with `USAC_MAGSAC`.
 *
 * Explicitly NOT: product wiring (this source set never enters the release
 * APK or runtime graph), threshold/ORB changes, or accuracy evidence. All
 * fixtures are synthetic and deterministic; a passing probe says the native
 * primitives exist and behave, nothing about recognition quality on real
 * postcards. Runtime requires a connected arm64-v8a device or x86_64
 * emulator; see `tools/probes/postcard-matcher/NATIVE_ANDROID_FEASIBILITY.md`.
 *
 * Native-memory discipline: every locally owned [Mat] is released in a
 * `finally` block, so assertion and exception paths cannot leak native
 * memory across the suite. [RootSiftProbe.normalize] returns a caller-owned
 * [Mat] under the same contract. Failure coverage uses only deterministic
 * seams (empty/wrong-type Mats, blank images, degenerate point sets);
 * mid-loop native-throw guards are defense-in-depth with no injectable
 * throw seam, so no test invents one.
 */
@RunWith(AndroidJUnit4::class)
class SiftNativeCapabilityProbeTest {

    companion object {
        private const val SIZE = 320
        private const val TRANSLATE_X = 17.0
        private const val TRANSLATE_Y = 11.0

        @JvmStatic
        @BeforeClass
        fun loadNative() {
            assertTrue(
                "libopencv_java4 failed to load from the test process",
                OpenCVLoader.initDebug(),
            )
        }

        /**
         * Deterministic keypoint-rich fixture: dot grid plus border.
         * Caller-owned on success; fully released if drawing throws before
         * ownership transfers.
         */
        fun syntheticFixture(): Mat {
            val image = Mat(SIZE, SIZE, CvType.CV_8UC1, Scalar(0.0))
            try {
                for (row in 0 until 4) {
                    for (col in 0 until 4) {
                        Imgproc.circle(
                            image,
                            Point(48.0 + col * 74.0, 48.0 + row * 74.0),
                            18,
                            Scalar(255.0),
                            -1,
                        )
                    }
                }
                Imgproc.rectangle(
                    image,
                    Point(8.0, 8.0),
                    Point((SIZE - 8).toDouble(), (SIZE - 8).toDouble()),
                    Scalar(255.0),
                    3,
                )
            } catch (thrown: Throwable) {
                image.release()
                throw thrown
            }
            return image
        }

        /**
         * Caller-owned translated copy. Each allocation boundary releases what
         * it owns: a failed warp leaves nothing, a failed source releases the
         * warp, a failed warpAffine releases source and destination.
         */
        fun translatedFixture(): Mat {
            val warp = Mat.eye(2, 3, CvType.CV_64F)
            try {
                val src = syntheticFixture()
                try {
                    val out = Mat()
                    try {
                        warp.put(0, 2, TRANSLATE_X)
                        warp.put(1, 2, TRANSLATE_Y)
                        Imgproc.warpAffine(src, out, warp, src.size())
                    } catch (thrown: Throwable) {
                        out.release()
                        throw thrown
                    }
                    return out
                } finally {
                    src.release()
                }
            } finally {
                warp.release()
            }
        }

        /**
         * Both returned Mats are caller-owned. A throwing detectAndCompute
         * releases keys and descriptors before propagating; the mask is
         * always released since ownership never transfers.
         */
        fun extract(image: Mat): Pair<MatOfKeyPoint, Mat> {
            val keys = MatOfKeyPoint()
            val descriptors = Mat()
            val noMask = Mat()
            try {
                SIFT.create().detectAndCompute(image, noMask, keys, descriptors)
            } catch (thrown: Throwable) {
                keys.release()
                descriptors.release()
                throw thrown
            } finally {
                noMask.release()
            }
            return keys to descriptors
        }

        fun assertFiniteDescriptors(descriptors: Mat) {
            assertEquals(CvType.CV_32F, descriptors.type())
            assertEquals(128, descriptors.cols())
            assertTrue(descriptors.rows() > 0)
            val row = FloatArray(descriptors.cols())
            for (r in 0 until descriptors.rows()) {
                descriptors.get(r, 0, row)
                for (value in row) {
                    assertTrue("non-finite descriptor value", value.isFinite())
                }
            }
        }
    }

    @Test
    fun siftExtractsFinite128DimDescriptors() {
        val image = syntheticFixture()
        val (keys, descriptors) = extract(image)
        try {
            assertFiniteDescriptors(descriptors)
        } finally {
            keys.release()
            descriptors.release()
            image.release()
        }
    }

    @Test
    fun blankImageFailsClosedWithEmptyDescriptors() {
        val blank = Mat(SIZE, SIZE, CvType.CV_8UC1, Scalar(127.0))
        val (keys, descriptors) = extract(blank)
        val normalized = RootSiftProbe.normalize(descriptors)
        try {
            assertEquals(0, keys.toArray().size)
            assertEquals(0, descriptors.rows())
            assertEquals(0, normalized.rows())
        } finally {
            normalized.release()
            keys.release()
            descriptors.release()
            blank.release()
        }
    }

    @Test
    fun probeLocalRootSiftYieldsUnitEnergyNonNegativeRows() {
        // Oracle semantics: L1-normalize then sqrt, so each non-degenerate
        // row has sum-of-squares (L2 norm squared) approx 1 — never an L1
        // sum of 1, which the square root destroys by construction.
        val image = syntheticFixture()
        val (keys, descriptors) = extract(image)
        val root = RootSiftProbe.normalize(descriptors)
        try {
            assertEquals(descriptors.rows(), root.rows())
            assertEquals(descriptors.cols(), root.cols())
            val row = FloatArray(root.cols())
            for (r in 0 until root.rows()) {
                root.get(r, 0, row)
                var squares = 0f
                for (value in row) {
                    assertTrue(value.isFinite())
                    assertTrue(value >= 0f)
                    squares += value * value
                }
                assertEquals(1f, squares, 1e-4f)
            }
        } finally {
            root.release()
            keys.release()
            descriptors.release()
            image.release()
        }
    }

    @Test
    fun rootSiftMatchesKnownVector() {
        // L1 sum is 10, so output must be sqrt(v / 10) element-wise.
        val input = Mat(1, 4, CvType.CV_32F)
        input.put(0, 0, floatArrayOf(1f, 2f, 3f, 4f))
        val out = RootSiftProbe.normalize(input)
        try {
            val actual = FloatArray(4)
            out.get(0, 0, actual)
            val expected = floatArrayOf(
                kotlin.math.sqrt(0.1f),
                kotlin.math.sqrt(0.2f),
                kotlin.math.sqrt(0.3f),
                kotlin.math.sqrt(0.4f),
            )
            for (c in 0 until 4) assertEquals(expected[c], actual[c], 1e-6f)
        } finally {
            out.release()
            input.release()
        }
    }

    @Test
    fun emptyMatReturnsEmptyRegardlessOfDefaultType() {
        val empty = Mat()
        val out = RootSiftProbe.normalize(empty)
        try {
            assertEquals(0, out.rows())
        } finally {
            out.release()
            empty.release()
        }
    }

    @Test
    fun nonEmptyWrongTypeIsRejected() {
        val wrong = Mat(2, 2, CvType.CV_8UC1, Scalar(1.0))
        try {
            try {
                RootSiftProbe.normalize(wrong).release()
                fail("expected IllegalArgumentException for non-CV_32F input")
            } catch (_: IllegalArgumentException) {
                // Expected: type contract enforced for non-empty input.
            }
        } finally {
            wrong.release()
        }
    }

    @Test
    fun knnMatchFindsExactSelfMatches() {
        val image = syntheticFixture()
        val (keys, descriptors) = extract(image)
        val knn = mutableListOf<MatOfDMatch>()
        try {
            BFMatcher.create(Core.NORM_L2).knnMatch(descriptors, descriptors, knn, 2)
            assertEquals(descriptors.rows(), knn.size)
            for ((query, perQuery) in knn.withIndex()) {
                val neighbors = perQuery.toArray()
                try {
                    assertEquals(2, neighbors.size)
                    val best = neighbors.minByOrNull { it.distance }!!
                    assertEquals(query, best.trainIdx)
                    assertEquals(0f, best.distance, 1e-6f)
                } finally {
                    perQuery.release()
                }
            }
        } finally {
            // Idempotent: per-query entries above are already released.
            for (entry in knn) entry.release()
            keys.release()
            descriptors.release()
            image.release()
        }
    }

    @Test
    fun usacMagsacBindingAndHomographyRecoverKnownTranslation() {
        // Pin the exact Java binding: USAC_MAGSAC must be exposed as 38.
        assertEquals(38, Calib3d.USAC_MAGSAC)
        val src = MatOfPoint2f()
        val dst = MatOfPoint2f()
        val mask = Mat()
        var homography: Mat? = null
        try {
            val srcPoints = mutableListOf<Point>()
            val dstPoints = mutableListOf<Point>()
            for (row in 0 until 4) {
                for (col in 0 until 4) {
                    val x = 48.0 + col * 74.0
                    val y = 48.0 + row * 74.0
                    srcPoints += Point(x, y)
                    dstPoints += Point(x + TRANSLATE_X, y + TRANSLATE_Y)
                }
            }
            src.fromList(srcPoints)
            dst.fromList(dstPoints)
            homography = Calib3d.findHomography(
                src, dst, Calib3d.USAC_MAGSAC, 4.0, mask, 2000, 0.99,
            )
            val result: Mat = homography
            assertFalse(result.empty())
            assertEquals(3, result.rows())
            assertEquals(3, result.cols())
            val flat = DoubleArray(9)
            result.get(0, 0, flat)
            for (value in flat) assertTrue(value.isFinite())
            assertEquals(TRANSLATE_X, flat[2], 1e-3)
            assertEquals(TRANSLATE_Y, flat[5], 1e-3)
            assertEquals(16, Core.countNonZero(mask))
        } finally {
            homography?.release()
            src.release()
            dst.release()
            mask.release()
        }
    }

    @Test
    fun degenerateCorrespondencesFailClosedWithEmptyHomography() {
        val src = MatOfPoint2f(Point(0.0, 0.0), Point(1.0, 0.0), Point(0.0, 1.0))
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(1.0, 0.0), Point(0.0, 1.0))
        val mask = Mat()
        var homography: Mat? = null
        try {
            homography = Calib3d.findHomography(
                src, dst, Calib3d.USAC_MAGSAC, 4.0, mask, 2000, 0.99,
            )
            val result: Mat = homography
            assertTrue(result.empty())
        } finally {
            homography?.release()
            src.release()
            dst.release()
            mask.release()
        }
    }

    @Test
    fun translatedFixtureKeepsMatchableKeypoints() {
        val imageA = syntheticFixture()
        val imageB = translatedFixture()
        val (keysA, descA) = extract(imageA)
        val (keysB, descB) = extract(imageB)
        try {
            assertTrue(keysA.toArray().isNotEmpty())
            assertTrue(keysB.toArray().isNotEmpty())
            assertFiniteDescriptors(descA)
            assertFiniteDescriptors(descB)
        } finally {
            keysA.release()
            descA.release()
            keysB.release()
            descB.release()
            imageA.release()
            imageB.release()
        }
    }
}
