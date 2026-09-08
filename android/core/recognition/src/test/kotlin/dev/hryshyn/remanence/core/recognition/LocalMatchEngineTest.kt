package dev.hryshyn.remanence.core.recognition

import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprint
import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec
import dev.hryshyn.remanence.core.model.SiftRootSiftKeypoint
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * FRONT-only integration proof (ADR-012, M2-F0-01): the hierarchy over synthetic
 * FRONT fingerprints — unique strong acceptance issues exactly one grant after
 * crypto verification, verification refusal never issues, and identical FRONT
 * designs fall to the chooser (design->0..N). Old two-sided payloads fail closed
 * at the codec before reaching the engine.
 */
class LocalMatchEngineTest {

    private var verifierResult = true
    private var verifierCalls = 0
    private val issuedGrants = mutableListOf<UUID>()

    @BeforeTest
    fun setUp() {
        verifierResult = true
        verifierCalls = 0
        issuedGrants.clear()
    }

    private fun keypoint(x: Double, y: Double, response: Int) = SiftRootSiftKeypoint(
        xMicro = Math.rint(x * SiftRootSiftFingerprintCodec.MICRO_UNITS).toInt(),
        yMicro = Math.rint(y * SiftRootSiftFingerprintCodec.MICRO_UNITS).toInt(),
        scaleMicro = SiftRootSiftFingerprintCodec.MICRO_UNITS,
        angleCentiDegrees = 0,
        responseQuantized = response,
        octave = 0,
    )

    private fun fingerprint(
        seed: Int,
        count: Int,
        points: List<Pair<Double, Double>> = List(count) { index ->
            (index % 8) / 8.0 to (index / 8) / 8.0
        },
        canonicalWidthPx: Int = 1600,
        canonicalHeightPx: Int = 1000,
    ): SiftRootSiftFingerprint {
        require(points.size == count)
        return SiftRootSiftFingerprint(
        profileId = SiftRootSiftFingerprintCodec.PROFILE_ID,
        canonicalWidthPx = canonicalWidthPx,
        canonicalHeightPx = canonicalHeightPx,
        coarseHash64 = seed.toLong(),
        keypoints = points.mapIndexed { index, (x, y) -> keypoint(x, y, index) },
        quantizedSiftDescriptors = List(count) { i ->
            ByteArray(SiftRootSiftFingerprintCodec.DESCRIPTOR_BYTES) {
                ((it * 7 + i * 13 + seed * 29).coerceIn(1, 255)).toByte()
            }
        },
        )
    }

    private fun candidate(id: String, preferred: Boolean, seedFront: Int) = IndexedCandidate(
        capsuleId = UUID.nameUUIDFromBytes(id.toByteArray()),
        front = fingerprint(seedFront, 64),
        recipientPreferred = preferred,
    )

    private fun engine(
        matcher: SiftRootSiftMatcherPort = fakeMatcher(),
    ): Pair<LocalMatchEngine, ScanGrantIssuer> {
        val issuer = ScanGrantIssuer { capsuleId ->
            issuedGrants += capsuleId
            "grant-${capsuleId}"
        }
        return LocalMatchEngine(
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            verifier = { verifierCalls++; verifierResult },
            grantIssuer = issuer,
            matcher = matcher,
        ) to issuer
    }

    private data class GeometryObservation(
        val pair: SiftRootSiftMatchPair,
        val referencePixel: Pair<Double, Double>,
        val queryPixel: Pair<Double, Double>,
        val projectedQueryPixel: Pair<Double, Double>?,
        val errorPx: Double,
    )

    private data class SupportMetrics(
        val areaPx2: Double,
        val edgeRatio: Double,
        val orientationPreserved: Boolean,
    )

    /**
     * Deterministic matcher fixture whose geometry observations are derived
     * from the supplied row coordinates and reference-pixel -> query-pixel H.
     * It is intentionally not a second matcher implementation: it only gives
     * the engine a physically consistent boundary result for policy tests.
     */
    private fun fakeMatcher(homography: DoubleArray? = null) = SiftRootSiftMatcherPort { query, reference ->
        val count = if (query.coarseHash64 == reference.coarseHash64) {
            minOf(query.keypoints.size, reference.keypoints.size)
        } else {
            0
        }
        val pixelHomography = homography ?: IDENTITY_H
        val pairs = (0 until count).map { index ->
            SiftRootSiftMatchPair(index, index, 0.1)
        }
        val observations = pairs.map { pair ->
            val referencePixel = pixelPoint(reference, pair.referenceIndex)
            val queryPixel = pixelPoint(query, pair.queryIndex)
            val projected = project(pixelHomography, referencePixel)
            val error = projected?.let { hypot(it.first - queryPixel.first, it.second - queryPixel.second) }
                ?: Double.POSITIVE_INFINITY
            GeometryObservation(pair, referencePixel, queryPixel, projected, error)
        }
        val inliers = observations.filter { it.errorPx <= INLIER_TOLERANCE_PX }
        val support = supportMetrics(inliers, pixelHomography)
        val hFinite = pixelHomography.size == HOMOGRAPHY_VALUES && pixelHomography.all(Double::isFinite)
        val geometryFound = count >= 6 && hFinite
        val derivedGeometryAccepted = geometryFound && inliers.size >= 4 && support != null &&
            support.orientationPreserved && support.areaPx2 >= MIN_SUPPORT_AREA_PX2 &&
            support.edgeRatio <= MAX_SUPPORT_EDGE_RATIO
        val failure = when {
            count < 6 -> SiftRootSiftMatchFailure.INSUFFICIENT_UNIQUE_PAIRS
            !geometryFound -> SiftRootSiftMatchFailure.INVALID_HOMOGRAPHY
            inliers.size < 4 -> SiftRootSiftMatchFailure.INSUFFICIENT_INLIERS
            support == null -> SiftRootSiftMatchFailure.INVALID_PROJECTED_SUPPORT
            !support.orientationPreserved -> SiftRootSiftMatchFailure.INVALID_PROJECTED_SUPPORT
            support.areaPx2 < MIN_SUPPORT_AREA_PX2 -> SiftRootSiftMatchFailure.SUPPORT_TOO_SMALL
            support.edgeRatio > MAX_SUPPORT_EDGE_RATIO -> SiftRootSiftMatchFailure.SUPPORT_EDGE_RATIO
            else -> SiftRootSiftMatchFailure.NONE
        }
        val errors = inliers.map { it.errorPx }
        val referenceCoverage = if (inliers.isEmpty()) {
            -1.0
        } else {
            ConvexHull.area(inliers.map { normalizedPoint(reference, it.pair.referenceIndex) })
        }
        SiftRootSiftMatchResult(
            matches = pairs,
            inlierMatchIndices = inliers.map { observations.indexOf(it) },
            homographyRowMajor = if (count >= 6) pixelHomography else null,
            diagnostics = SiftRootSiftMatchDiagnostics(
                rawQueryRows = query.keypoints.size,
                rawReferenceRows = reference.keypoints.size,
                usableQueryRows = query.keypoints.size,
                usableReferenceRows = reference.keypoints.size,
                forwardRatioMatches = count,
                reverseRatioMatches = count,
                reciprocalMatches = count,
                uniqueMatches = count,
                geometryAttempted = count >= 6,
                geometryFound = geometryFound,
                geometryAccepted = derivedGeometryAccepted,
                inliers = inliers.size,
                inlierRatio = if (count == 0) 0.0 else inliers.size.toDouble() / count,
                medianInlierReprojectionErrorPx = median(errors),
                referenceConvexHullCoverage = referenceCoverage,
                supportAreaPx2 = support?.areaPx2 ?: 0.0,
                supportEdgeRatio = support?.edgeRatio ?: 0.0,
                failure = failure,
            ),
        )
    }

    /** Models a P2 result that rejected geometry after deriving all observations. */
    private fun p2GeometryRejectedMatcher() = SiftRootSiftMatcherPort { query, reference ->
        val derived = fakeMatcher().match(query, reference)
        derived.copy(
            diagnostics = derived.diagnostics.copy(
                geometryAccepted = false,
                failure = SiftRootSiftMatchFailure.INVALID_PROJECTED_SUPPORT,
            ),
        )
    }

    private fun pixelPoint(
        fingerprint: SiftRootSiftFingerprint,
        index: Int,
    ): Pair<Double, Double> {
        val keypoint = fingerprint.keypoints[index]
        return (
            keypoint.xMicro.toDouble() / SiftRootSiftFingerprintCodec.MICRO_UNITS * fingerprint.canonicalWidthPx
            ) to (
            keypoint.yMicro.toDouble() / SiftRootSiftFingerprintCodec.MICRO_UNITS * fingerprint.canonicalHeightPx
            )
    }

    private fun normalizedPoint(
        fingerprint: SiftRootSiftFingerprint,
        index: Int,
    ): Pair<Double, Double> {
        val keypoint = fingerprint.keypoints[index]
        return (
            keypoint.xMicro.toDouble() / SiftRootSiftFingerprintCodec.MICRO_UNITS
            ) to (
            keypoint.yMicro.toDouble() / SiftRootSiftFingerprintCodec.MICRO_UNITS
            )
    }

    private fun project(
        homography: DoubleArray,
        point: Pair<Double, Double>,
    ): Pair<Double, Double>? {
        if (homography.size != HOMOGRAPHY_VALUES || homography.any { !it.isFinite() }) return null
        val x = point.first
        val y = point.second
        val w = homography[6] * x + homography[7] * y + homography[8]
        if (!w.isFinite() || abs(w) <= PROJECTION_EPSILON) return null
        val projectedX = (homography[0] * x + homography[1] * y + homography[2]) / w
        val projectedY = (homography[3] * x + homography[4] * y + homography[5]) / w
        return if (projectedX.isFinite() && projectedY.isFinite()) projectedX to projectedY else null
    }

    private fun supportMetrics(
        inliers: List<GeometryObservation>,
        homography: DoubleArray,
    ): SupportMetrics? {
        if (inliers.size < 4) return null
        val minX = inliers.minOf { it.referencePixel.first }
        val maxX = inliers.maxOf { it.referencePixel.first }
        val minY = inliers.minOf { it.referencePixel.second }
        val maxY = inliers.maxOf { it.referencePixel.second }
        val corners = listOf(minX to minY, maxX to minY, maxX to maxY, minX to maxY)
        val projected = corners.map { project(homography, it) }
        if (projected.any { it == null }) return null
        val points = projected.map { requireNotNull(it) }
        val signedArea = polygonArea(points)
        val area = abs(signedArea)
        val edgeLengths = points.indices.map { index ->
            val next = points[(index + 1) % points.size]
            hypot(next.first - points[index].first, next.second - points[index].second)
        }
        val shortest = edgeLengths.minOrNull() ?: return null
        val longest = edgeLengths.maxOrNull() ?: return null
        val edgeRatio = if (shortest > 0.0) longest / shortest else Double.POSITIVE_INFINITY
        return SupportMetrics(area, edgeRatio, signedArea > 0.0)
    }

    private fun polygonArea(points: List<Pair<Double, Double>>): Double = points.indices.sumOf { index ->
        val current = points[index]
        val next = points[(index + 1) % points.size]
        current.first * next.second - next.first * current.second
    } / 2.0

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return -1.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    private fun projectedFingerprint(
        reference: SiftRootSiftFingerprint,
        homography: DoubleArray,
        width: Int = reference.canonicalWidthPx,
        height: Int = reference.canonicalHeightPx,
    ): SiftRootSiftFingerprint {
        val points = reference.keypoints.indices.map { index ->
            val projected = requireNotNull(project(homography, pixelPoint(reference, index)))
            require(projected.first in 0.0..width.toDouble() && projected.second in 0.0..height.toDouble())
            projected.first / width to projected.second / height
        }
        return fingerprint(
            seed = reference.coarseHash64.toInt(),
            count = reference.keypoints.size,
            points = points,
            canonicalWidthPx = width,
            canonicalHeightPx = height,
        )
    }

    private fun pixelHomographyFromNormalized(
        normalized: DoubleArray,
        queryWidth: Int,
        queryHeight: Int,
        referenceWidth: Int,
        referenceHeight: Int,
    ): DoubleArray = doubleArrayOf(
        queryWidth.toDouble() / referenceWidth * normalized[0],
        queryWidth.toDouble() / referenceHeight * normalized[1],
        queryWidth.toDouble() * normalized[2],
        queryHeight.toDouble() / referenceWidth * normalized[3],
        queryHeight.toDouble() / referenceHeight * normalized[4],
        queryHeight.toDouble() * normalized[5],
        normalized[6] / referenceWidth,
        normalized[7] / referenceHeight,
        normalized[8],
    )

    private companion object {
        val IDENTITY_H = doubleArrayOf(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0,
        )
        const val HOMOGRAPHY_VALUES = 9
        const val INLIER_TOLERANCE_PX = 4.0
        const val MIN_SUPPORT_AREA_PX2 = 300.0
        const val MAX_SUPPORT_EDGE_RATIO = 8.0
        const val PROJECTION_EPSILON = 1e-9
    }

    @Test
    fun returnedPixelHomographyIsNormalizedBeforeFullCardGate() = kotlinx.coroutines.runBlocking {
        val reference = fingerprint(11, 64)
        val normalizedHomography = doubleArrayOf(
            0.62, 0.03, 0.08,
            -0.02, 0.81, 0.07,
            0.00006, -0.00004, 1.0,
        )
        val pixelHomography = pixelHomographyFromNormalized(
            normalized = normalizedHomography,
            queryWidth = 1200,
            queryHeight = 900,
            referenceWidth = reference.canonicalWidthPx,
            referenceHeight = reference.canonicalHeightPx,
        )
        val (engine, _) = engine(
            matcher = fakeMatcher(homography = pixelHomography),
        )
        // The unequal axes, translation, and non-zero projective terms are
        // deliberately retained through the pixel conversion. With the
        // actual projected query points, normalization must recover the
        // plausible normalized matrix above.
        val query = projectedFingerprint(reference, pixelHomography, width = 1200, height = 900)

        val result = engine.run(
            query,
            listOf(IndexedCandidate(UUID.nameUUIDFromBytes("normalized-h".toByteArray()), reference, true)),
        )

        assertTrue(result is ScanFlowResult.Granted, "dimension-normalized H should pass the existing gate")
    }

    @Test
    fun fullCardAreaLowerBoundaryRemainsInclusive() = kotlinx.coroutines.runBlocking {
        val pixelHomography = doubleArrayOf(
            0.2, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0,
        )
        val front = fingerprint(11, 64)
        val query = projectedFingerprint(front, pixelHomography)
        val (engine, _) = engine(matcher = fakeMatcher(homography = pixelHomography))

        val result = engine.run(
            query,
            listOf(IndexedCandidate(UUID.nameUUIDFromBytes("area-boundary".toByteArray()), front, true)),
        )

        // The homography gate's exact 0.20 area boundary is inclusive (covered
        // directly by HomographyPlausibilityGateTest), but this projection has
        // query hull coverage 0.153125 and therefore cannot product-grant.
        assertEquals(0.153125, ConvexHull.area(query.keypoints.indices.map { normalizedPoint(query, it) }), 1e-12)
        assertTrue(result !is ScanFlowResult.Granted, "0.20 area with 0.153125 query coverage must not grant")
        assertEquals(0, verifierCalls)
        assertTrue(issuedGrants.isEmpty())
    }

    @Test
    fun embeddedPatchFailsFullCardGateEvenWhenP2SupportSaysAccepted() = kotlinx.coroutines.runBlocking {
        val front = fingerprint(11, 64)
        val pixelHomography = doubleArrayOf(
            0.25, 0.0, 0.35,
            0.0, 0.25, 0.35,
            0.0, 0.0, 1.0,
        )
        val (engine, _) = engine(
            matcher = fakeMatcher(homography = pixelHomography),
        )
        // The projected support is well-shaped and sufficiently large for P2,
        // while the full-card normalized area is only .0625.
        val query = projectedFingerprint(front, pixelHomography)

        val result = engine.run(
            query,
            listOf(IndexedCandidate(UUID.nameUUIDFromBytes("embedded-patch".toByteArray()), front, true)),
        )

        assertEquals(ScanFlowResult.RecaptureRequired, result)
        assertEquals(0, verifierCalls)
        assertTrue(issuedGrants.isEmpty(), "a local patch must never reach a grant")
    }

    @Test
    fun existingCoverageUsesTheSmallerQueryOrReferenceHull() = kotlinx.coroutines.runBlocking {
        val reference = fingerprint(11, 64)
        val pixelHomography = doubleArrayOf(
            0.1, 0.0, 0.2,
            0.0, 0.1, 0.2,
            0.0, 0.0, 1.0,
        )
        val query = projectedFingerprint(reference, pixelHomography)
        val (engine, _) = engine(matcher = fakeMatcher(homography = pixelHomography))

        val result = engine.run(
            query,
            listOf(IndexedCandidate(UUID.nameUUIDFromBytes("coverage-min".toByteArray()), reference, true)),
        )

        assertEquals(ScanFlowResult.RecaptureRequired, result)
        assertEquals(0, verifierCalls)
        assertTrue(issuedGrants.isEmpty(), "clustered query coverage must fail the unchanged weak gate")
    }

    @Test
    fun p2GeometryRejectionBlocksFullCardPlausibleResultBeforeVerifierOrGrant() = kotlinx.coroutines.runBlocking {
        val front = fingerprint(11, 64)
        val (engine, _) = engine(matcher = p2GeometryRejectedMatcher())

        val result = engine.run(
            front,
            listOf(IndexedCandidate(UUID.nameUUIDFromBytes("p2-geometry-rejected".toByteArray()), front, true)),
        )

        assertEquals(ScanFlowResult.RecaptureRequired, result)
        assertEquals(0, verifierCalls, "geometryAccepted=false must block crypto verification")
        assertTrue(issuedGrants.isEmpty(), "geometryAccepted=false must block grants")
    }

    @Test
    fun visualEvaluationDoesNotVerifyOrIssueUntilExplicitCompletion() = kotlinx.coroutines.runBlocking {
        var verificationCalls = 0
        var grantCalls = 0
        val front = fingerprint(11, 64)
        val engine = LocalMatchEngine(
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            verifier = CapsuleVerifier { verificationCalls++; true },
            grantIssuer = ScanGrantIssuer { grantCalls++; "grant" },
            matcher = fakeMatcher(),
        )

        val visual = engine.evaluateVisual(
            queryFront = front,
            candidates = listOf(
                IndexedCandidate(UUID.nameUUIDFromBytes("visual-boundary".toByteArray()), front, true),
            ),
        )

        assertTrue(visual is VisualScanResult.Accepted)
        assertEquals(0, verificationCalls)
        assertEquals(0, grantCalls)
        assertTrue(engine.completeVisual(visual) is ScanFlowResult.Granted)
        assertEquals(1, verificationCalls)
        assertEquals(1, grantCalls)
    }

    @Test
    fun visualEvaluationChecksCancellationBetweenCandidates() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val baseMatcher = fakeMatcher()
        val engine = LocalMatchEngine(
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            verifier = CapsuleVerifier { true },
            grantIssuer = ScanGrantIssuer { "grant" },
            matcher = SiftRootSiftMatcherPort { query, reference ->
                calls++
                val result = baseMatcher.match(query, reference)
                result
            },
        )
        val front = fingerprint(11, 64)
        val second = fingerprint(11, 64, canonicalWidthPx = 1599)

        var checks = 0
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            kotlinx.coroutines.runBlocking {
                engine.evaluateVisual(
                    queryFront = front,
                    candidates = listOf(
                        IndexedCandidate(UUID.nameUUIDFromBytes("cancel-one".toByteArray()), front, true),
                        IndexedCandidate(UUID.nameUUIDFromBytes("cancel-two".toByteArray()), second, true),
                    ),
                    cancellationCheck = {
                        checks++
                        if (calls > 0) throw kotlinx.coroutines.CancellationException("test cancellation")
                    },
                )
            }
        }

        assertTrue(checks > 0)
        assertEquals(1, calls)
    }

    @Test
    fun uniqueStrongRecipientMatchIssuesExactlyOneGrantAfterVerification() = kotlinx.coroutines.runBlocking {
        val (engine, _) = engine()
        val queryFront = fingerprint(11, 64)
        val recipient = candidate("A", preferred = true, seedFront = 11)
        val universe = listOf(recipient, recipient.copy(recipientPreferred = false))

        val result = engine.run(queryFront, universe)

        val granted = result as ScanFlowResult.Granted
        assertEquals(recipient.capsuleId, granted.capsuleId)
        assertEquals(CandidateOrigin.RECIPIENT_PREFERRED, granted.origin)
        assertTrue(granted.grantId.startsWith("grant-"))
        assertEquals(1, issuedGrants.size)
    }

    @Test
    fun refusedCryptoVerificationNeverIssuesAGrant() = kotlinx.coroutines.runBlocking {
        verifierResult = false
        val (engine, _) = engine()
        val queryFront = fingerprint(11, 64)
        val universe = listOf(candidate("A", preferred = true, 11))

        val result = engine.run(queryFront, universe)

        assertEquals(ScanFlowResult.RecaptureRequired, result)
        assertEquals(0, issuedGrants.size)
    }

    @Test
    fun identicalMassProducedDesignsFallToTheChooserInsteadOfAutoOpen() = kotlinx.coroutines.runBlocking {
        val (engine, _) = engine()
        val queryFront = fingerprint(11, 64)
        val universe = listOf(
            candidate("dup-1", preferred = true, 11),
            candidate("dup-2", preferred = true, 11),
        )

        val result = engine.run(queryFront, universe)

        val ambiguous = result as ScanFlowResult.Ambiguous
        assertEquals(CandidateOrigin.RECIPIENT_PREFERRED, ambiguous.origin)
        assertEquals(2, ambiguous.rows.size)
        assertFalse(ambiguous.singleRecaptureFirst)
        kotlin.test.assertTrue(issuedGrants.isEmpty(), "no grant may exist for an ambiguous scan")
    }

    @Test
    fun senderFallbackRunsWhenRecipientRowsLackWeakEvidence() = kotlinx.coroutines.runBlocking {
        val (engine, _) = engine()
        val queryFront = fingerprint(11, 64)
        // Recipient baselines EXIST but each holds so few features that no
        // FRONT can ever clear the weak-evidence gate.
        val starvedFront = fingerprint(11, 3)
        val universe = listOf(
            IndexedCandidate(
                capsuleId = UUID.nameUUIDFromBytes("rec-starved-1".toByteArray()),
                front = starvedFront,
                recipientPreferred = true,
            ),
            IndexedCandidate(
                capsuleId = UUID.nameUUIDFromBytes("rec-starved-2".toByteArray()),
                front = fingerprint(31, 5),
                recipientPreferred = true,
            ),
            candidate("sender-original", preferred = false, seedFront = 11),
        )

        val result = engine.run(queryFront, universe)

        val granted = result as ScanFlowResult.Granted
        assertEquals(UUID.nameUUIDFromBytes("sender-original".toByteArray()), granted.capsuleId)
        assertEquals(CandidateOrigin.SENDER_FALLBACK, granted.origin)
        assertEquals(1, issuedGrants.size)
    }

    @Test
    fun senderFallbackKeepsRecipientAndSenderPairsForTheSameCapsule() = kotlinx.coroutines.runBlocking {
        val (engine, _) = engine()
        val queryFront = fingerprint(11, 64)
        val capsuleId = UUID.nameUUIDFromBytes("repeat-fallback".toByteArray())

        val result = engine.run(
            queryFront,
            listOf(
                IndexedCandidate(
                    capsuleId = capsuleId,
                    front = fingerprint(11, 3),
                    recipientPreferred = true,
                ),
                IndexedCandidate(
                    capsuleId = capsuleId,
                    front = fingerprint(11, 64),
                    recipientPreferred = false,
                ),
            ),
        )

        val granted = result as ScanFlowResult.Granted
        assertEquals(capsuleId, granted.capsuleId)
        assertEquals(CandidateOrigin.SENDER_FALLBACK, granted.origin)
        assertEquals(1, issuedGrants.size)
    }

    @Test
    fun unknownPostcardIsRecaptureRequiredWithoutAnyGrant() = kotlinx.coroutines.runBlocking {
        val (engine, _) = engine()
        // Unknown postcard: candidate has too few features to ever pass weak gate.
        val starved = fingerprint(91, 3)
        val universe = listOf(
            IndexedCandidate(
                capsuleId = UUID.nameUUIDFromBytes("other-card".toByteArray()),
                front = starved,
                recipientPreferred = false,
            ),
        )

        val result = engine.run(fingerprint(55, 64), universe)

        assertEquals(ScanFlowResult.RecaptureRequired, result)
        assertEquals(0, issuedGrants.size)
    }

    @Test
    fun emptyCandidateIndexIsNoMatchNotAnError() = kotlinx.coroutines.runBlocking {
        val (engine, _) = engine()

        val result = engine.run(fingerprint(1, 64), emptyList())

        assertEquals(ScanFlowResult.RecaptureRequired, result)
        assertEquals(0, issuedGrants.size)
    }

    @Test
    fun designToManyRequiresExplicitChoiceNeverAutoOpens() = kotlinx.coroutines.runBlocking {
        val (engine, _) = engine()
        // Same printed FRONT design mapped to two capsules (design->N).
        val queryFront = fingerprint(11, 64)
        // other-design is starved so it cannot pass weak gate and is excluded.
        val universe = listOf(
            candidate("design-capsule-1", preferred = true, 11),
            candidate("design-capsule-2", preferred = true, 11),
            IndexedCandidate(
                capsuleId = UUID.nameUUIDFromBytes("other-design".toByteArray()),
                front = fingerprint(99, 3),
                recipientPreferred = true,
            ),
        )
        // Both design capsules are plausible; must not auto-open.
        val result = engine.run(queryFront, universe)
        val ambiguous = result as ScanFlowResult.Ambiguous
        assertEquals(2, ambiguous.rows.size)
        assertTrue(issuedGrants.isEmpty(), "design->N must never auto-open")
    }

    @Test
    fun identicalScoreDistinctPlausibleCandidatesReturnAmbiguousWithoutVerifier() = kotlinx.coroutines.runBlocking {
        var verifierInvoked = false
        val countingVerifier = CapsuleVerifier { id -> verifierInvoked = true; true }
        val issuer = ScanGrantIssuer { capsuleId -> issuedGrants += capsuleId; "grant-$capsuleId" }
        val engine = LocalMatchEngine(
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            verifier = countingVerifier,
            grantIssuer = issuer,
            matcher = fakeMatcher(),
        )
        val queryFront = fingerprint(11, 64)
        // Both candidates share the same seed (11) so their synthetic fingerprints are
        // identical; the engine sees identical scores and must not auto-open.  Real
        // score-separated cases (e.g. 0.85 vs 0.45) are exercised in
        // MatchCoordinatorTest; this test specifically proves the identical-score path.
        val universe = listOf(
            candidate("dup-a", preferred = true, 11),
            candidate("dup-b", preferred = true, 11),
        )
        val result = engine.run(queryFront, universe)
        assertTrue(result is ScanFlowResult.Ambiguous, "identical-score plausible candidates must be ambiguous")
        assertTrue(!verifierInvoked, "verifier must never be invoked for ambiguous")
        assertTrue(issuedGrants.isEmpty(), "no grant for ambiguous even with identical scores")
    }

    @Test
    fun multiplePlausibleInSenderFallbackAlsoReturnsAmbiguousWithoutVerifier() = kotlinx.coroutines.runBlocking {
        var verifierInvoked = false
        val countingVerifier = CapsuleVerifier { id -> verifierInvoked = true; true }
        val issuer = ScanGrantIssuer { capsuleId -> issuedGrants += capsuleId; "grant-$capsuleId" }
        val engine = LocalMatchEngine(
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            verifier = countingVerifier,
            grantIssuer = issuer,
            matcher = fakeMatcher(),
        )
        val queryFront = fingerprint(11, 64)
        // No recipient candidates, two sender candidates both plausible.
        val universe = listOf(
            candidate("sender-1", preferred = false, 11),
            candidate("sender-2", preferred = false, 11),
        )
        val result = engine.run(queryFront, universe)
        assertTrue(result is ScanFlowResult.Ambiguous, "sender-fallback multiple plausible must be ambiguous")
        assertEquals(CandidateOrigin.SENDER_FALLBACK, (result as ScanFlowResult.Ambiguous).origin)
        assertTrue(!verifierInvoked, "verifier must never be invoked for sender-fallback ambiguous")
        assertTrue(issuedGrants.isEmpty())
    }

    @Test
    fun singlePlausibleCandidateStillAutoOpens() = kotlinx.coroutines.runBlocking {
        val (engine, _) = engine()
        val queryFront = fingerprint(11, 64)
        val universe = listOf(candidate("single", preferred = true, 11))
        val result = engine.run(queryFront, universe)
        assertTrue(result is ScanFlowResult.Granted, "single plausible must grant")
        assertEquals(1, issuedGrants.size)
    }

    @Test
    fun crossOriginRecipientAndSenderBothPlausibleReturnsAmbiguousWithoutVerifier() = kotlinx.coroutines.runBlocking {
        var verifierInvoked = false
        val countingVerifier = CapsuleVerifier { id -> verifierInvoked = true; true }
        val issuer = ScanGrantIssuer { capsuleId -> issuedGrants += capsuleId; "grant-$capsuleId" }
        val engine = LocalMatchEngine(
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            verifier = countingVerifier,
            grantIssuer = issuer,
            matcher = fakeMatcher(),
        )
        val queryFront = fingerprint(11, 64)
        // Recipient capsule A (plausible) + Sender capsule B (plausible) distinct => cross-origin ambiguous
        val universe = listOf(
            candidate("recipient-A", preferred = true, 11),
            candidate("sender-B", preferred = false, 11),
        )
        val result = engine.run(queryFront, universe)
        assertTrue(result is ScanFlowResult.Ambiguous, "recipient A + sender B both plausible must be ambiguous")
        assertTrue(!verifierInvoked, "verifier must not be invoked for cross-origin ambiguous")
        assertTrue(issuedGrants.isEmpty(), "no grant for cross-origin ambiguous")
        val ambiguous = result as ScanFlowResult.Ambiguous
        assertEquals(2, ambiguous.rows.size)
    }

    @Test
    fun sameCapsuleInBothOriginsDedupsAndPrefersRecipient() = kotlinx.coroutines.runBlocking {
        var verifierInvoked = false
        var verifiedId: UUID? = null
        val countingVerifier = CapsuleVerifier { id -> verifierInvoked = true; verifiedId = id; true }
        val issuer = ScanGrantIssuer { capsuleId -> issuedGrants += capsuleId; "grant-$capsuleId" }
        val engine = LocalMatchEngine(
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            verifier = countingVerifier,
            grantIssuer = issuer,
            matcher = fakeMatcher(),
        )
        val queryFront = fingerprint(11, 64)
        val capsuleId = UUID.nameUUIDFromBytes("dedup-capsule".toByteArray())
        val universe = listOf(
            IndexedCandidate(capsuleId = capsuleId, front = fingerprint(11, 64), recipientPreferred = true),
            IndexedCandidate(capsuleId = capsuleId, front = fingerprint(11, 64), recipientPreferred = false),
        )
        val result = engine.run(queryFront, universe)
        assertTrue(result is ScanFlowResult.Granted, "same capsule in both origins must dedup to single and grant")
        assertEquals(capsuleId, (result as ScanFlowResult.Granted).capsuleId)
        assertEquals(CandidateOrigin.RECIPIENT_PREFERRED, result.origin)
        assertTrue(verifierInvoked, "verifier must be invoked for single deduped candidate")
        assertEquals(capsuleId, verifiedId)
        assertEquals(1, issuedGrants.size)
    }

    @Test
    fun recipientNonPlausibleSenderPlausibleFallsBackToSender() = kotlinx.coroutines.runBlocking {
        var verifierInvoked = false
        val countingVerifier = CapsuleVerifier { id -> verifierInvoked = true; true }
        val issuer = ScanGrantIssuer { capsuleId -> issuedGrants += capsuleId; "grant-$capsuleId" }
        val engine = LocalMatchEngine(
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            verifier = countingVerifier,
            grantIssuer = issuer,
            matcher = fakeMatcher(),
        )
        val queryFront = fingerprint(11, 64)
        // Recipient has no plausible (starved), sender has one plausible
        val universe = listOf(
            IndexedCandidate(
                capsuleId = UUID.nameUUIDFromBytes("rec-nonplausible".toByteArray()),
                front = fingerprint(11, 3),
                recipientPreferred = true,
            ),
            candidate("sender-plausible", preferred = false, 11),
        )
        val result = engine.run(queryFront, universe)
        assertTrue(result is ScanFlowResult.Granted, "recipient non-plausible + sender plausible must fallback")
        assertEquals(CandidateOrigin.SENDER_FALLBACK, (result as ScanFlowResult.Granted).origin)
        assertTrue(verifierInvoked, "verifier must be invoked for fallback")
        assertEquals(1, issuedGrants.size)
    }

    private fun assertFalse(value: Boolean) = kotlin.test.assertFalse(value)
}
