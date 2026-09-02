package dev.hryshyn.remanence.core.recognition

import java.util.UUID

/**
 * One locally indexed candidate capsule with its stored FRONT fingerprint.
 * ADR-012 FRONT-only contract: exactly one required FRONT per capsule.
 * The `back` parameter is retained only for compile compatibility and is
 * always null in production; any non-null legacy back is ignored fail-closed.
 */
data class IndexedCandidate(
    val capsuleId: UUID,
    val front: PostcardFingerprint,
    val back: PostcardFingerprint? = null,
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

/**
 * M2-F0-01 FRONT-only: runs the complete documented hierarchy — descriptor
 * matching, RANSAC geometry, coverage, plausibility gates, FRONT scoring,
 * front ranking with duplicate grouping, FRONT-only acceptance, outcome
 * classification, and recipient-first coordination — then issues a ONE-TIME
 * memory-only scan grant ONLY after the injected crypto verifier accepts the
 * winning capsule. A refused verification never produces a grant.
 *
 * The engine operates on exactly one required FRONT per candidate. Any legacy
 * `back` field is ignored. The `queryBack` overload is retained only for
 * compile compatibility and delegates to FRONT-only logic.
 */
class LocalMatchEngine(
    private val profile: RecognitionProfile,
    private val verifier: CapsuleVerifier,
    private val grantIssuer: ScanGrantIssuer,
    private val estimatorProvider: () -> HomographyEstimator = { HomographyEstimator() },
    private val matcher: DescriptorMatcher = DescriptorMatcher(),
) {

    private val plausibilityGate = HomographyPlausibilityGate(profile.match)
    private val sideScorer = SideScorer(profile)
    private val frontRanker = FrontCandidateRanker(profile)
    private val acceptanceEvaluator = CompositeAcceptanceEvaluator(profile)
    private val outcomeClassifier = ScanOutcomeClassifier(profile)
    private val coordinator = MatchCoordinator(profile)

    /** FRONT-only entry point. */
    suspend fun run(
        queryFront: PostcardFingerprint,
        candidates: List<IndexedCandidate>,
    ): ScanFlowResult {
        require(queryFront.side == FingerprintSide.FRONT) { "query must be FRONT" }
        if (candidates.isEmpty()) {
            return ScanFlowResult.RecaptureRequired
        }

        val recipientList = candidates.filter { it.recipientPreferred }
        val senderList = candidates.filterNot { it.recipientPreferred }

        val recipientUniverse = evaluateUniverse(recipientList, queryFront)
        val senderUniverse = if (recipientUniverse.frontRanking.retained.isEmpty() && senderList.isNotEmpty()) {
            evaluateUniverse(senderList, queryFront)
        } else {
            null
        }

        return when (val decision = coordinator.coordinate(recipientUniverse, senderUniverse)) {
            is CoordinatorDecision.AutoAccepted -> {
                val capsuleId = UUID.fromString(decision.candidateId)
                if (!verifier.verify(capsuleId)) return ScanFlowResult.RecaptureRequired
                val grantId = grantIssuer.issue(capsuleId) ?: return ScanFlowResult.RecaptureRequired
                val score = (if (decision.origin == CandidateOrigin.RECIPIENT_PREFERRED) recipientUniverse else senderUniverse)
                    ?.acceptance?.autoAccepted?.compositeScore ?: Double.NaN
                ScanFlowResult.Granted(capsuleId, decision.origin, grantId, score)
            }
            is CoordinatorDecision.SenderFallbackAccepted -> {
                val capsuleId = UUID.fromString(decision.candidateId)
                if (!verifier.verify(capsuleId)) return ScanFlowResult.RecaptureRequired
                val grantId = grantIssuer.issue(capsuleId) ?: return ScanFlowResult.RecaptureRequired
                ScanFlowResult.Granted(
                    capsuleId, CandidateOrigin.SENDER_FALLBACK, grantId,
                    senderUniverse?.acceptance?.autoAccepted?.compositeScore ?: Double.NaN,
                )
            }
            is CoordinatorDecision.Ambiguous -> ScanFlowResult.Ambiguous(
                origin = decision.origin,
                rows = decision.classification.chooserRows.map { UUID.fromString(it.candidateId) to it.compositeScore },
                singleRecaptureFirst =
                    decision.classification.outcome == ScanOutcome.SINGLE_CANDIDATE_RECAPTURE,
            )
            CoordinatorDecision.NoMatchEverywhere -> ScanFlowResult.RecaptureRequired
        }
    }

    /** Legacy overload retained for compile compatibility; BACK is ignored. */
    @Deprecated("FRONT-only contract: queryBack is ignored", ReplaceWith("run(queryFront, candidates)"))
    suspend fun run(
        queryFront: PostcardFingerprint,
        queryBack: PostcardFingerprint,
        candidates: List<IndexedCandidate>,
    ): ScanFlowResult {
        require(queryFront.side == FingerprintSide.FRONT) { "queryFront must be FRONT" }
        // queryBack is intentionally ignored in FRONT-only contract; fail closed
        // if it is not a FRONT (legacy two-sided callers passed BACK here).
        // We do not use its bytes for scoring.
        return run(queryFront, candidates)
    }

    private data class UniverseResult(
        val origin: CandidateOrigin,
        val frontRanking: FrontRanking,
        val acceptance: CompositeAcceptanceReport?,
    )

    private fun evaluateUniverse(
        universe: List<IndexedCandidate>,
        queryFront: PostcardFingerprint,
    ): UniverseScanResult {
        val origin = if (universe.firstOrNull()?.recipientPreferred == true || universe.isEmpty()) {
            CandidateOrigin.RECIPIENT_PREFERRED
        } else {
            CandidateOrigin.SENDER_FALLBACK
        }

        val frontOutcomes = HashMap<String, FrontCandidate>(universe.size)
        val frontStrengths = HashMap<String, Boolean>(universe.size)
        universe.forEach { candidate ->
            require(candidate.front.side == FingerprintSide.FRONT) { "candidate front must be FRONT" }
            val outcome = evaluateSide(queryFront, candidate.front)
            val front = FrontCandidate(candidate.capsuleId.toString(), outcome.report.sideScore, outcome.report.weakGatePassed)
            frontOutcomes[candidate.capsuleId.toString()] = front
            frontStrengths[candidate.capsuleId.toString()] = outcome.report.strongGatePassed
        }
        val frontRanking = frontRanker.rank(frontOutcomes.values.toList())

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

    private data class SideOutcome(
        val signals: SideMatchSignals,
        val report: SideScoreReport,
    )

    /** Descriptor matching + RANSAC + coverage + plausibility + scoring. */
    private fun evaluateSide(query: PostcardFingerprint, reference: PostcardFingerprint): SideOutcome {
        val matches = matcher.match(query, reference)
        val insufficient = profile.match.weakMinRatioMatches
        if (matches.size < insufficient) {
            return SideOutcome(
                signalsOf(matches.size, 0, 0.0, 0.0, 0, 1.0, false),
                sideScorer.score(signalsOf(matches.size, 0, 0.0, 0.0, 0, 1.0, false)),
            )
        }

        val points = matches.map { m ->
            MatchPoint(
                query.keypoints[m.queryIndex].xNormalized,
                query.keypoints[m.queryIndex].yNormalized,
                reference.keypoints[m.referenceIndex].xNormalized,
                reference.keypoints[m.referenceIndex].yNormalized,
            )
        }
        val report = estimatorProvider().estimate(points)

        var coverageValue = 0.0
        var gridCells = 0
        var plausible = false
        if (report.success && report.matrix != null) {
            val inlierQuery = report.inlierIndices.map { points[it].queryX to points[it].queryY }
            val inlierReference = report.inlierIndices.map { points[it].referenceX to points[it].referenceY }
            val coverage = SpatialCoverageMeter(profile.match.coverageGridSize).measure(inlierQuery, inlierReference)
            coverageValue = coverage.hullAreaNormalized
            gridCells = coverage.occupiedGridCells
            plausible = plausibilityGate.check(
                report.matrix!!,
                report.medianInlierErrorNormalized,
                profile.match.inlierReprojectionTolerancePx / profile.capture.canonicalLongEdgePx,
            ).plausible
        }

        val signals = SideMatchSignals(
            ratioMutualMatches = matches.size,
            ransacInliers = report.inlierCount,
            inlierRatio = report.inlierRatio,
            spatialCoverage = coverageValue,
            occupiedGridCells = gridCells,
            medianInlierErrorNormalized = report.medianInlierErrorNormalized,
            homographyPlausible = plausible,
        )
        return SideOutcome(signals, sideScorer.score(signals))
    }

    private fun signalsOf(
        matches: Int,
        inliers: Int,
        ratio: Double,
        coverage: Double,
        cells: Int,
        medianError: Double,
        plausible: Boolean,
    ) = SideMatchSignals(matches, inliers, ratio, coverage, cells, medianError, plausible)
}
