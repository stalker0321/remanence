package dev.hryshyn.remanence.core.recognition

import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprint
import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec
import dev.hryshyn.remanence.core.model.SiftRootSiftKeypoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class SiftRootSiftMatcherTest {

    private val matcher = SiftRootSiftMatcher()

    @Test
    fun rootSiftUsesUnsignedL1ThenSqrtAndDoesNotMutateRawRows() {
        val raw = ByteArray(128).also {
            it[0] = 1
            it[1] = 2
            it[2] = 3
            it[3] = 4
        }
        val before = raw.copyOf()

        val rooted = matcher.rootSiftRowsForTesting(fingerprint(listOf(raw))).single().values

        val sum = 10.0
        listOf(1.0, 2.0, 3.0, 4.0).forEachIndexed { index, value ->
            assertTrue(abs(rooted[index] - kotlin.math.sqrt(value / sum).toFloat()) < 0.000001f)
        }
        assertTrue(rooted.drop(4).all { it == 0.0f })
        assertContentEquals(before, raw)
    }

    @Test
    fun rootSiftTreatsDescriptorBytesAsUnsigned() {
        val raw = ByteArray(128).also {
            it[0] = 0xFF.toByte()
            it[1] = 1
        }

        val rooted = matcher.rootSiftRowsForTesting(fingerprint(listOf(raw))).single().values

        assertTrue(abs(rooted[0] - kotlin.math.sqrt(255.0 / 256.0).toFloat()) < 0.000001f)
        assertTrue(abs(rooted[1] - 0.0625f) < 0.000001f)
    }

    @Test
    fun rootSiftMatchesFloat32GoldenVectorExactly() {
        val raw = ByteArray(128).also {
            it[0] = 1
            it[1] = 2
            it[2] = 127
            it[3] = 0xFF.toByte()
        }

        val rooted = matcher.rootSiftRowsForTesting(fingerprint(listOf(raw))).single().values
        val expectedRawBits = intArrayOf(0x3D50C061, 0x3D939C0E, 0x3F130828, 0x3F5057E7)

        expectedRawBits.forEachIndexed { index, expected ->
            assertEquals(expected, rooted[index].toRawBits())
        }
    }

    @Test
    fun zeroRowsAreOmittedButOriginalIndicesRemainStable() {
        val rows = listOf(
            ByteArray(128),
            descriptor(1),
            ByteArray(128),
            descriptor(2),
        )

        val rooted = matcher.rootSiftRowsForTesting(fingerprint(rows))

        assertEquals(listOf(1, 3), rooted.map { it.originalIndex })
        assertEquals(2, rooted.size)
    }

    @Test
    fun allZeroRowsAreAValidNoUsableInputWithoutNativeOpenCv() {
        val result = matcher.match(
            fingerprint(listOf(ByteArray(128))),
            fingerprint(listOf(descriptor(1))),
        )

        assertEquals(SiftRootSiftMatchFailure.NO_USABLE_ROWS, result.diagnostics.failure)
        assertEquals(1, result.diagnostics.rawQueryRows)
        assertEquals(0, result.diagnostics.usableQueryRows)
        assertFalse(result.diagnostics.geometryAttempted)
        assertEquals(-1.0, result.diagnostics.medianInlierReprojectionErrorPx)
        assertEquals(-1.0, result.diagnostics.referenceConvexHullCoverage)
    }

    @Test
    fun invalidProfileFailsBeforeNativeAvailabilityIsRelevant() {
        val invalid = fingerprint(listOf(descriptor(1))).copyWithProfile("not-the-p0-profile")

        assertFailsWith<IllegalArgumentException> {
            matcher.match(invalid, fingerprint(listOf(descriptor(1))))
        }
    }

    @Test
    fun strictRatioRejectsBoundaryZeroAndInvalidDistances() {
        assertTrue(matcher.passesStrictRatioForTesting(0.749999, 1.0))
        assertFalse(matcher.passesStrictRatioForTesting(0.75, 1.0))
        assertFalse(matcher.passesStrictRatioForTesting(0.0, 0.0))
        assertFalse(matcher.passesStrictRatioForTesting(0.1, 0.0))
        assertFalse(matcher.passesStrictRatioForTesting(-0.1, 1.0))
        assertFalse(matcher.passesStrictRatioForTesting(Double.NaN, 1.0))
        assertFalse(matcher.passesStrictRatioForTesting(0.1, Double.POSITIVE_INFINITY))
    }

    @Test
    fun reciprocalFilteringMapsUsableRowsBackToOriginalIndices() {
        val query = fingerprint(listOf(ByteArray(128), descriptor(1), descriptor(2)))
        val reference = fingerprint(listOf(ByteArray(128), descriptor(3), descriptor(4)))
        val forward = listOf(
            SiftRootSiftMatcher.LocalMatch(0, 0, 0.10),
            SiftRootSiftMatcher.LocalMatch(1, 1, 0.20),
        )
        val reverse = listOf(
            SiftRootSiftMatcher.LocalMatch(0, 0, 0.30),
        )

        val result = matcher.reduceMatchesForTesting(query, reference, forward, reverse)

        assertEquals(
            listOf(SiftRootSiftMatchPair(queryIndex = 1, referenceIndex = 1, distance = 0.10)),
            result,
        )
    }

    @Test
    fun reciprocalResultsDeduplicateOnlyQueryPixelsAndKeepBestPair() {
        val query = fingerprint(
            rows = listOf(descriptor(1), descriptor(2), descriptor(3), descriptor(4)),
            points = listOf(10.40 to 10.40, 10.49 to 10.49, 20.0 to 20.0, 30.0 to 30.0),
        )
        val reference = fingerprint(
            rows = listOf(descriptor(5), descriptor(6), descriptor(7), descriptor(8)),
            points = listOf(30.40 to 30.40, 30.49 to 30.49, 50.40 to 50.40, 50.49 to 50.49),
        )
        val forward = listOf(
            SiftRootSiftMatcher.LocalMatch(0, 0, 0.20),
            SiftRootSiftMatcher.LocalMatch(1, 1, 0.10),
            SiftRootSiftMatcher.LocalMatch(2, 2, 0.10),
            SiftRootSiftMatcher.LocalMatch(3, 3, 0.10),
        )
        val reverse = listOf(
            SiftRootSiftMatcher.LocalMatch(0, 0, 0.30),
            SiftRootSiftMatcher.LocalMatch(1, 1, 0.30),
            SiftRootSiftMatcher.LocalMatch(2, 2, 0.30),
            SiftRootSiftMatcher.LocalMatch(3, 3, 0.30),
        )

        val first = matcher.reduceMatchesForTesting(query, reference, forward, reverse)
        val second = matcher.reduceMatchesForTesting(query, reference, forward.reversed(), reverse.reversed())

        assertEquals(
            listOf(
                SiftRootSiftMatchPair(queryIndex = 1, referenceIndex = 1, distance = 0.10),
                SiftRootSiftMatchPair(queryIndex = 2, referenceIndex = 2, distance = 0.10),
                SiftRootSiftMatchPair(queryIndex = 3, referenceIndex = 3, distance = 0.10),
            ),
            first,
        )
        assertEquals(first, second)
    }

    @Test
    fun oneCandidatePerSideFailsClosedWhenKnnCannotSupplyTwo() {
        loadNativeOrSkip()

        val result = matcher.match(
            fingerprint(listOf(descriptor(1))),
            fingerprint(listOf(descriptor(1))),
        )

        assertEquals(SiftRootSiftMatchFailure.NO_RATIO_MATCHES, result.diagnostics.failure)
        assertEquals(0, result.diagnostics.forwardRatioMatches)
        assertEquals(0, result.diagnostics.reverseRatioMatches)
        assertFalse(result.diagnostics.geometryAttempted)
    }

    @Test
    fun fewerThanSixUniqueReciprocalPairsNeverAttemptGeometry() {
        loadNativeOrSkip()
        val points = listOf(
            30.0 to 20.0,
            160.0 to 20.0,
            290.0 to 20.0,
            30.0 to 180.0,
            160.0 to 180.0,
        )
        val descriptors = (0 until 5).map { descriptor(it) }

        val result = matcher.match(
            fingerprint(descriptors, points),
            fingerprint(descriptors, points),
        )

        assertEquals(5, result.diagnostics.reciprocalMatches)
        assertEquals(5, result.diagnostics.uniqueMatches)
        assertFalse(result.diagnostics.geometryAttempted)
        assertEquals(SiftRootSiftMatchFailure.INSUFFICIENT_UNIQUE_PAIRS, result.diagnostics.failure)
    }

    @Test
    fun translatedGridProducesDeterministicNativeGeometryDiagnostics() {
        loadNativeOrSkip()
        val referencePoints = listOf(
            30.0 to 20.0,
            160.0 to 20.0,
            290.0 to 20.0,
            30.0 to 180.0,
            160.0 to 180.0,
            290.0 to 180.0,
        )
        val queryPoints = referencePoints.map { (x, y) -> x + 17.0 to y + 11.0 }
        val descriptors = (0 until 6).map { descriptor(it) }

        val result = matcher.match(
            fingerprint(descriptors, queryPoints),
            fingerprint(descriptors, referencePoints),
        )

        assertEquals(6, result.diagnostics.forwardRatioMatches)
        assertEquals(6, result.diagnostics.reverseRatioMatches)
        assertEquals(6, result.diagnostics.reciprocalMatches)
        assertEquals(6, result.diagnostics.uniqueMatches)
        assertTrue(result.diagnostics.geometryAttempted)
        assertTrue(result.diagnostics.geometryFound, result.diagnostics.toString())
        assertTrue(result.diagnostics.geometryAccepted)
        assertTrue(result.diagnostics.inliers >= 4)
        assertEquals(SiftRootSiftMatchFailure.NONE, result.diagnostics.failure)
        val homography = assertNotNull(result.homographyRowMajor)
        assertTrue(abs(homography[2] - 17.0) < 1.0)
        assertTrue(abs(homography[5] - 11.0) < 1.0)

        val repeated = matcher.match(
            fingerprint(descriptors, queryPoints),
            fingerprint(descriptors, referencePoints),
        )
        assertEquals(result.matches, repeated.matches)
        assertEquals(result.diagnostics, repeated.diagnostics)
    }

    @Test
    fun reflectedSupportFailsCanonicalOrientationWhilePositiveWindingRemainsValid() {
        val canonical = listOf(
            0.0 to 0.0,
            100.0 to 0.0,
            100.0 to 100.0,
            0.0 to 100.0,
        )
        val reflected = canonical.map { (x, y) -> 100.0 - x to y }

        assertTrue(matcher.supportOrientationForTesting(canonical))
        assertFalse(matcher.supportOrientationForTesting(reflected))
    }

    @Test
    fun reflectedSupportMatchesCanonicalOrientedAreaNoEvidenceBehavior() {
        loadNativeOrSkip()
        val referencePoints = listOf(
            30.0 to 20.0,
            150.0 to 25.0,
            280.0 to 20.0,
            55.0 to 80.0,
            230.0 to 75.0,
            35.0 to 175.0,
            160.0 to 155.0,
            290.0 to 180.0,
        )
        val queryPoints = referencePoints.map { (x, y) -> 320.0 - x to y + 11.0 }
        val descriptors = (0 until referencePoints.size).map { descriptor(it) }

        val result = matcher.match(
            fingerprint(descriptors, queryPoints),
            fingerprint(descriptors, referencePoints),
        )

        assertFalse(result.diagnostics.geometryAccepted)
        if (result.diagnostics.geometryFound) {
            // If USAC returns the reflected H, the canonical oriented-area
            // check rejects it after preserving the observed evidence.
            assertEquals(SiftRootSiftMatchFailure.INVALID_PROJECTED_SUPPORT, result.diagnostics.failure)
            assertTrue(result.diagnostics.inliers >= 4)
            assertTrue(result.diagnostics.medianInlierReprojectionErrorPx >= 0.0)
            assertTrue(result.diagnostics.referenceConvexHullCoverage > 0.0)
            assertTrue(result.diagnostics.supportAreaPx2 > 300.0)
            assertTrue(result.diagnostics.supportEdgeRatio < 8.0)
        } else {
            // OpenCV may reject this reflected model before support
            // evaluation; that is also ordinary no-evidence behavior.
            assertEquals(SiftRootSiftMatchFailure.HOMOGRAPHY_EMPTY, result.diagnostics.failure)
            assertEquals(-1.0, result.diagnostics.medianInlierReprojectionErrorPx)
            assertEquals(-1.0, result.diagnostics.referenceConvexHullCoverage)
        }
    }

    @Test
    fun supportValidityDiagnosticsRetainObservedEvidence() {
        loadNativeOrSkip()
        val descriptors = (0 until 6).map { descriptor(it) }
        val smallReference = listOf(
            10.0 to 10.0,
            15.0 to 10.0,
            20.0 to 10.0,
            10.0 to 20.0,
            15.0 to 20.0,
            20.0 to 20.0,
        )
        val smallQuery = smallReference.map { (x, y) -> x + 2.0 to y + 3.0 }

        val small = matcher.match(
            fingerprint(descriptors, smallQuery),
            fingerprint(descriptors, smallReference),
        )

        assertEquals(SiftRootSiftMatchFailure.SUPPORT_TOO_SMALL, small.diagnostics.failure)
        assertTrue(small.diagnostics.geometryFound)
        assertTrue(small.diagnostics.inliers >= 4)
        assertTrue(small.diagnostics.inlierRatio > 0.0)
        assertTrue(small.diagnostics.medianInlierReprojectionErrorPx.isFinite())
        assertTrue(small.diagnostics.referenceConvexHullCoverage > 0.0)
        assertTrue(small.diagnostics.supportAreaPx2 in 0.0..300.0)
        assertTrue(small.diagnostics.supportEdgeRatio > 0.0)

        val wideReference = listOf(
            20.0 to 50.0,
            100.0 to 50.0,
            220.0 to 50.0,
            20.0 to 60.0,
            100.0 to 60.0,
            220.0 to 60.0,
        )
        val wideQuery = wideReference.map { (x, y) -> x + 2.0 to y + 3.0 }
        val wide = matcher.match(
            fingerprint(descriptors, wideQuery),
            fingerprint(descriptors, wideReference),
        )

        assertEquals(SiftRootSiftMatchFailure.SUPPORT_EDGE_RATIO, wide.diagnostics.failure)
        assertTrue(wide.diagnostics.geometryFound)
        assertTrue(wide.diagnostics.inliers >= 4)
        assertTrue(wide.diagnostics.inlierRatio > 0.0)
        assertTrue(wide.diagnostics.medianInlierReprojectionErrorPx.isFinite())
        assertTrue(wide.diagnostics.referenceConvexHullCoverage > 0.0)
        assertTrue(wide.diagnostics.supportAreaPx2 > 300.0)
        assertTrue(wide.diagnostics.supportEdgeRatio > 8.0)
    }

    @Test
    fun collinearNativeGeometryIsOrdinaryNoEvidence() {
        loadNativeOrSkip()
        val referencePoints = List(6) { index -> 20.0 + index * 45.0 to 80.0 }
        val queryPoints = referencePoints.map { (x, y) -> x + 12.0 to y + 7.0 }
        val descriptors = (0 until 6).map { descriptor(it) }

        val result = matcher.match(
            fingerprint(descriptors, queryPoints),
            fingerprint(descriptors, referencePoints),
        )

        assertTrue(result.diagnostics.geometryAttempted)
        assertFalse(result.diagnostics.geometryAccepted)
        if (result.diagnostics.inliers == 0) {
            assertEquals(-1.0, result.diagnostics.medianInlierReprojectionErrorPx)
            assertEquals(-1.0, result.diagnostics.referenceConvexHullCoverage)
        } else {
            assertTrue(result.diagnostics.medianInlierReprojectionErrorPx >= 0.0)
            assertTrue(result.diagnostics.referenceConvexHullCoverage >= 0.0)
        }
        assertTrue(result.diagnostics.inliers < 6 || result.diagnostics.geometryFound)
    }

    @Test
    fun cleanupContinuesAfterIndividualNativeReleaseFailureAndPreservesPrimary() {
        val released = ArrayList<String>()
        val primary = IllegalStateException("native primary")

        matcher.cleanupForTesting(
            primaryFailure = primary,
            actions = listOf(
                { released += "matcher"; error("clear failure") },
                { released += "knn-forward" },
                { released += "knn-reverse" },
                { released += "homography" },
                { released += "mask" },
                { released += "points" },
                { released += "descriptors" },
            ),
        )

        assertEquals(
            listOf("matcher", "knn-forward", "knn-reverse", "homography", "mask", "points", "descriptors"),
            released,
        )
        assertEquals(1, primary.suppressed.size)
        assertEquals("clear failure", primary.suppressed.single().message)
    }

    private fun loadNativeOrSkip() {
        runCatching { System.loadLibrary("opencv_java4100") }
            .onFailure { assumeTrue("desktop OpenCV natives unavailable: $it", false) }
    }

    private fun fingerprint(
        rows: List<ByteArray>,
        points: List<Pair<Double, Double>> = rows.indices.map { index ->
            20.0 + (index % 8) * 35.0 to 20.0 + (index / 8) * 35.0
        },
    ): SiftRootSiftFingerprint {
        require(rows.size == points.size)
        return SiftRootSiftFingerprint(
            profileId = SiftRootSiftFingerprintCodec.PROFILE_ID,
            canonicalWidthPx = 320,
            canonicalHeightPx = 200,
            coarseHash64 = 0x1020304050607080L,
            keypoints = points.map { (x, y) ->
                SiftRootSiftKeypoint(
                    xMicro = Math.rint(x / 320.0 * SiftRootSiftFingerprintCodec.MICRO_UNITS).toInt(),
                    yMicro = Math.rint(y / 200.0 * SiftRootSiftFingerprintCodec.MICRO_UNITS).toInt(),
                    scaleMicro = 1,
                    angleCentiDegrees = 0,
                    responseQuantized = 1,
                    octave = 0,
                )
            },
            quantizedSiftDescriptors = rows,
        )
    }

    private fun descriptor(index: Int): ByteArray = ByteArray(128).also {
        it[index] = 0xFF.toByte()
    }

    private fun SiftRootSiftFingerprint.copyWithProfile(profile: String) = SiftRootSiftFingerprint(
        profileId = profile,
        canonicalWidthPx = canonicalWidthPx,
        canonicalHeightPx = canonicalHeightPx,
        coarseHash64 = coarseHash64,
        keypoints = keypoints,
        quantizedSiftDescriptors = quantizedSiftDescriptors,
    )
}
