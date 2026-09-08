package dev.hryshyn.remanence.core.recognition

import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprint
import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec
import java.util.UUID

/**
 * One locally indexed candidate capsule with its stored FRONT fingerprint.
 * ADR-012 FRONT-only contract: exactly one required FRONT per capsule.
 */
data class IndexedCandidate(
    val capsuleId: UUID,
    val front: SiftRootSiftFingerprint,
    /** True when this FRONT comes from the preferred recipient baseline. */
    val recipientPreferred: Boolean = false,
)

/** Crypto verification hook: true only after envelope/statement/AEAD checks. */
fun interface CapsuleVerifier {
    suspend fun verify(capsuleId: UUID): Boolean
}

/** Grant issuer seam so the engine stays testable without Android state. */
fun interface ScanGrantIssuer {
    /** Returns the random grant ID for this capsule, or null when refused. */
    suspend fun issue(capsuleId: UUID): String?
}

/** Typed seam for the sole current SIFT/RootSIFT matcher. */
fun interface SiftRootSiftMatcherPort {
    fun match(
        query: SiftRootSiftFingerprint,
        reference: SiftRootSiftFingerprint,
    ): SiftRootSiftMatchResult
}

/** Final result of running the whole local hierarchy for one scan session. */
sealed interface ScanFlowResult {
    data class Granted(
        val capsuleId: UUID,
        val origin: CandidateOrigin,
        val grantId: String,
        val compositeScore: Double,
    ) : ScanFlowResult

    data class Ambiguous(
        val origin: CandidateOrigin,
        val rows: List<Pair<UUID, Double>>,
        val singleRecaptureFirst: Boolean,
    ) : ScanFlowResult

    /** Nothing opened: quality guidance or verification refusal. */
    data object RecaptureRequired : ScanFlowResult
}

/** Visual-only result; it carries no verification result or grant capability. */
sealed interface VisualScanResult {
    data class Accepted(
        val capsuleId: UUID,
        val origin: CandidateOrigin,
        val compositeScore: Double,
    ) : VisualScanResult

    data class Ambiguous(
        val origin: CandidateOrigin,
        val rows: List<Pair<UUID, Double>>,
        val singleRecaptureFirst: Boolean,
    ) : VisualScanResult

    data class RecaptureRequired(
        val outcome: MatchDiagnosticOutcome,
    ) : VisualScanResult
}

/**
 * M2-F0-01 FRONT-only: runs the complete documented hierarchy — descriptor
 * matching, RANSAC geometry, coverage, plausibility gates, FRONT scoring,
 * front ranking with duplicate grouping, FRONT-only acceptance, outcome
 * classification, and recipient-first coordination — then issues a ONE-TIME
 * memory-only scan grant ONLY after the injected crypto verifier accepts the
 * winning capsule. A refused verification never produces a grant.
 *
 * The engine operates on exactly one required FRONT per candidate. Recipient
 * and sender indexes are scored, deduplicated by capsule id (recipient
 * baseline wins), then classified once: N distinct plausibles never invoke
 * verifier/grant and return Ambiguous.
 */
class LocalMatchEngine(
    private val profile: RecognitionProfile,
    private val verifier: CapsuleVerifier,
    private val grantIssuer: ScanGrantIssuer,
    private val matcher: SiftRootSiftMatcherPort = SiftRootSiftMatcherPort {
        query, reference -> SiftRootSiftMatcher().match(query, reference)
    },
    private val diagnosticObserver: ((MatchDiagnosticEvent) -> Unit)? = null,
) {

    private val sideScorer = SideScorer(profile)
    private val frontRanker = FrontCandidateRanker(profile)
    private val acceptanceEvaluator = CompositeAcceptanceEvaluator(profile)
    private val coordinator = MatchCoordinator(profile)
    private val homographyPlausibilityGate = HomographyPlausibilityGate(profile.match)

    /** FRONT-only entry point. */
    suspend fun run(
        queryFront: SiftRootSiftFingerprint,
        candidates: List<IndexedCandidate>,
    ): ScanFlowResult = completeVisual(evaluateVisual(queryFront, candidates))

    /**
     * Runs only visual matching and policy classification. This boundary is
     * safe to execute on a CPU dispatcher: it never verifies crypto, issues a
     * grant, or mutates UI-owned state.
     */
    suspend fun evaluateVisual(
        queryFront: SiftRootSiftFingerprint,
        candidates: List<IndexedCandidate>,
        cancellationCheck: suspend () -> Unit = {},
    ): VisualScanResult {
        if (candidates.isEmpty()) {
            return VisualScanResult.RecaptureRequired(MatchDiagnosticOutcome.RECAPTURE)
        }

        val recipientList = candidates.filter { it.recipientPreferred }
        val senderList = candidates.filterNot { it.recipientPreferred }

        val recipientUniverse = evaluateUniverse(recipientList, queryFront, cancellationCheck)
        val senderUniverse = if (senderList.isNotEmpty()) {
            evaluateUniverse(senderList, queryFront, cancellationCheck)
        } else {
            null
        }

        return when (val decision = coordinator.coordinate(recipientUniverse, senderUniverse)) {
            is CoordinatorDecision.AutoAccepted -> {
                val capsuleId = UUID.fromString(decision.candidateId)
                val score = (if (decision.origin == CandidateOrigin.RECIPIENT_PREFERRED) recipientUniverse else senderUniverse)
                    ?.acceptance?.autoAccepted?.compositeScore ?: Double.NaN
                VisualScanResult.Accepted(capsuleId, decision.origin, score)
            }
            is CoordinatorDecision.SenderFallbackAccepted -> {
                val capsuleId = UUID.fromString(decision.candidateId)
                VisualScanResult.Accepted(
                    capsuleId,
                    CandidateOrigin.SENDER_FALLBACK,
                    senderUniverse?.acceptance?.autoAccepted?.compositeScore ?: Double.NaN,
                )
            }
            is CoordinatorDecision.Ambiguous -> {
                VisualScanResult.Ambiguous(
                    origin = decision.origin,
                    rows = decision.classification.chooserRows.map { UUID.fromString(it.candidateId) to it.compositeScore },
                    singleRecaptureFirst =
                        decision.classification.outcome == ScanOutcome.SINGLE_CANDIDATE_RECAPTURE,
                )
            }
            CoordinatorDecision.NoMatchEverywhere -> {
                VisualScanResult.RecaptureRequired(MatchDiagnosticOutcome.NO_MATCH)
            }
        }
    }

    /** Completes a visual result on the caller's owner/UI context. */
    suspend fun completeVisual(result: VisualScanResult): ScanFlowResult = when (result) {
        is VisualScanResult.Accepted -> {
            if (!verifier.verify(result.capsuleId)) {
                reportResult(MatchDiagnosticOutcome.RECAPTURE, result.origin)
                ScanFlowResult.RecaptureRequired
            } else {
                val grantId = grantIssuer.issue(result.capsuleId)
                if (grantId == null) {
                    reportResult(MatchDiagnosticOutcome.RECAPTURE, result.origin)
                    ScanFlowResult.RecaptureRequired
                } else {
                    reportResult(MatchDiagnosticOutcome.GRANT, result.origin)
                    ScanFlowResult.Granted(
                        result.capsuleId,
                        result.origin,
                        grantId,
                        result.compositeScore,
                    )
                }
            }
        }
        is VisualScanResult.Ambiguous -> {
            reportResult(
                if (result.singleRecaptureFirst) {
                    MatchDiagnosticOutcome.RECAPTURE
                } else {
                    MatchDiagnosticOutcome.AMBIGUOUS
                },
                result.origin,
            )
            ScanFlowResult.Ambiguous(
                origin = result.origin,
                rows = result.rows,
                singleRecaptureFirst = result.singleRecaptureFirst,
            )
        }
        is VisualScanResult.RecaptureRequired -> {
            reportResult(result.outcome)
            ScanFlowResult.RecaptureRequired
        }
    }

    private suspend fun evaluateUniverse(
        universe: List<IndexedCandidate>,
        queryFront: SiftRootSiftFingerprint,
        cancellationCheck: suspend () -> Unit,
    ): UniverseScanResult {
        val origin = if (universe.firstOrNull()?.recipientPreferred == true || universe.isEmpty()) {
            CandidateOrigin.RECIPIENT_PREFERRED
        } else {
            CandidateOrigin.SENDER_FALLBACK
        }

        val frontOutcomes = HashMap<String, FrontCandidate>(universe.size)
        val sideOutcomes = HashMap<String, SideOutcome>(universe.size)
        val frontStrengths = HashMap<String, Boolean>(universe.size)
        cancellationCheck()
        universe.forEach { candidate ->
            cancellationCheck()
            val outcome = evaluateSide(queryFront, candidate.front)
            cancellationCheck()
            val front = FrontCandidate(candidate.capsuleId.toString(), outcome.report.sideScore, outcome.report.weakGatePassed)
            frontOutcomes[candidate.capsuleId.toString()] = front
            sideOutcomes[candidate.capsuleId.toString()] = outcome
            frontStrengths[candidate.capsuleId.toString()] = outcome.report.strongGatePassed
        }
        cancellationCheck()
        val frontRanking = frontRanker.rank(frontOutcomes.values.toList())
        val top = frontRanking.retained.firstOrNull()
        val topOutcome = top?.let { sideOutcomes[it.candidateId] }
        reportDiagnostic(
            MatchDiagnosticEvent(
                phase = MatchDiagnosticPhase.CANDIDATE_EVALUATED,
                origin = origin,
                candidateCount = universe.size,
                score = top?.sideScore,
                margin = if (frontRanking.retained.size >= 2) {
                    frontRanking.retained[0].sideScore - frontRanking.retained[1].sideScore
                } else {
                    null
                },
                ratioMutualMatches = topOutcome?.signals?.ratioMutualMatches,
                ransacInliers = topOutcome?.signals?.ransacInliers,
                coverage = topOutcome?.signals?.spatialCoverage,
            ),
        )

        val composites = frontRanking.retained.map { retained ->
            CompositeCandidate(
                candidateId = retained.candidateId,
                frontScore = retained.sideScore,
                frontWeakPassed = retained.weakGatePassed,
                frontStrongPassed = frontStrengths.getValue(retained.candidateId),
            )
        }
        val acceptance = if (composites.isEmpty()) null else acceptanceEvaluator.evaluate(composites, frontRanking.duplicateFrontGroup)
        return UniverseScanResult(origin, frontRanking, acceptance)
    }

    private fun reportResult(
        outcome: MatchDiagnosticOutcome,
        origin: CandidateOrigin? = null,
    ) = reportDiagnostic(MatchDiagnosticEvent.result(outcome, origin))

    private fun reportDiagnostic(event: MatchDiagnosticEvent) {
        runCatching { diagnosticObserver?.invoke(event) }
    }

    private data class SideOutcome(
        val signals: SideMatchSignals,
        val report: SideScoreReport,
    )

    /** P2 matcher output adapted to the unchanged FRONT policy/scorer. */
    private fun evaluateSide(
        query: SiftRootSiftFingerprint,
        reference: SiftRootSiftFingerprint,
    ): SideOutcome {
        val result = matcher.match(query, reference)
        val diagnostics = result.diagnostics
        val inlierPoints = result.inlierMatchIndices.mapNotNull { index ->
            result.matches.getOrNull(index)?.let { pair ->
                MatchPoint(
                    query.keypoints[pair.queryIndex].xMicro.toDouble() / SiftRootSiftFingerprintCodec.MICRO_UNITS,
                    query.keypoints[pair.queryIndex].yMicro.toDouble() / SiftRootSiftFingerprintCodec.MICRO_UNITS,
                    reference.keypoints[pair.referenceIndex].xMicro.toDouble() / SiftRootSiftFingerprintCodec.MICRO_UNITS,
                    reference.keypoints[pair.referenceIndex].yMicro.toDouble() / SiftRootSiftFingerprintCodec.MICRO_UNITS,
                )
            }
        }
        val coverage = if (inlierPoints.isEmpty()) {
            null
        } else {
            SpatialCoverageMeter(profile.match.coverageGridSize).measure(
                inlierPoints.map { it.queryX to it.queryY },
                inlierPoints.map { it.referenceX to it.referenceY },
            )
        }
        val occupiedGridCells = coverage?.occupiedGridCells ?: 0
        val normalizedMedianError = diagnostics.medianInlierReprojectionErrorPx
            .takeIf { it >= 0.0 && it.isFinite() }
            ?.div(profile.capture.canonicalLongEdgePx.toDouble())
            ?: UNOBSERVABLE_MEDIAN_ERROR_NORMALIZED
        val fullCardPlausible = if (diagnostics.geometryAccepted) {
            fullCardPlausible(
                result = result,
                query = query,
                reference = reference,
                medianInlierErrorNormalized = normalizedMedianError,
            )
        } else {
            false
        }
        val signals = SideMatchSignals(
            // P2's unique count is the policy's one-sided ratio-match count:
            // it is after reciprocal matching, deterministic ordering, and
            // deliberate query-pixel deduplication.
            ratioMutualMatches = diagnostics.uniqueMatches,
            ransacInliers = diagnostics.inliers,
            inlierRatio = diagnostics.inlierRatio,
            // Preserve the existing policy's binding min(query, reference)
            // inlier-hull coverage; P2's reference-only diagnostic is not a
            // substitute for this product signal.
            spatialCoverage = coverage?.hullAreaNormalized
                ?.takeIf { it >= 0.0 && it.isFinite() }
                ?: 0.0,
            occupiedGridCells = occupiedGridCells,
            medianInlierErrorNormalized = normalizedMedianError,
            // P2 support validity is necessary but not sufficient. Reapply the
            // existing full-card gate to P2's pixel homography after converting
            // it to normalized reference->query coordinates.
            homographyPlausible = diagnostics.geometryAccepted && fullCardPlausible,
        )
        return SideOutcome(signals, sideScorer.score(signals))
    }

    /**
     * Reuses the P2 homography without another match/estimation pass. P2's H
     * maps reference pixels to query pixels; the existing gate consumes the
     * equivalent normalized-coordinate matrix Dquery^-1 * Hpixels * Dreference.
     */
    private fun fullCardPlausible(
        result: SiftRootSiftMatchResult,
        query: SiftRootSiftFingerprint,
        reference: SiftRootSiftFingerprint,
        medianInlierErrorNormalized: Double,
    ): Boolean {
        val pixelMatrix = result.homographyRowMajor ?: return false
        if (pixelMatrix.size != HOMOGRAPHY_VALUES) return false
        val normalizedMatrix = normalizePixelHomography(
            pixelMatrix = pixelMatrix,
            queryWidthPx = query.canonicalWidthPx,
            queryHeightPx = query.canonicalHeightPx,
            referenceWidthPx = reference.canonicalWidthPx,
            referenceHeightPx = reference.canonicalHeightPx,
        )
        return homographyPlausibilityGate.check(
            matrix = normalizedMatrix,
            medianInlierErrorNormalized = medianInlierErrorNormalized,
            medianErrorLimitNormalized =
                profile.match.inlierReprojectionTolerancePx / profile.capture.canonicalLongEdgePx,
        ).plausible
    }

    /** Converts reference/query pixel coordinates to their normalized domains. */
    private fun normalizePixelHomography(
        pixelMatrix: DoubleArray,
        queryWidthPx: Int,
        queryHeightPx: Int,
        referenceWidthPx: Int,
        referenceHeightPx: Int,
    ): DoubleArray {
        val dReference = doubleArrayOf(
            referenceWidthPx.toDouble(), 0.0, 0.0,
            0.0, referenceHeightPx.toDouble(), 0.0,
            0.0, 0.0, 1.0,
        )
        val inverseDQuery = doubleArrayOf(
            1.0 / queryWidthPx.toDouble(), 0.0, 0.0,
            0.0, 1.0 / queryHeightPx.toDouble(), 0.0,
            0.0, 0.0, 1.0,
        )
        return multiply3x3(inverseDQuery, multiply3x3(pixelMatrix, dReference))
    }

    private fun multiply3x3(left: DoubleArray, right: DoubleArray): DoubleArray {
        val result = DoubleArray(HOMOGRAPHY_VALUES)
        for (row in 0 until MATRIX_DIMENSION) {
            for (column in 0 until MATRIX_DIMENSION) {
                var value = 0.0
                for (inner in 0 until MATRIX_DIMENSION) {
                    value += left[row * MATRIX_DIMENSION + inner] *
                        right[inner * MATRIX_DIMENSION + column]
                }
                result[row * MATRIX_DIMENSION + column] = value
            }
        }
        return result
    }

    private companion object {
        const val HOMOGRAPHY_VALUES = 9
        const val MATRIX_DIMENSION = 3
        // SideScorer multiplies this by the configured maximum error, so an
        // unobservable error receives zero error credit without changing any
        // existing acceptance threshold.
        const val UNOBSERVABLE_MEDIAN_ERROR_NORMALIZED = 1.0
    }
}
