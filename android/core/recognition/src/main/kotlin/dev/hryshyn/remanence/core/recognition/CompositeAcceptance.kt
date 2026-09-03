package dev.hryshyn.remanence.core.recognition

/**
 * FRONT-only production contract (ADR-012, M2-F0-01): candidate carries exactly
 * one required FRONT score. Composite == frontScore. Old two-sided composite
 * and back weighting are deleted; FRONT thresholds are explicitly named.
 */
data class CompositeCandidate(
    val candidateId: String,
    val frontScore: Double,
    val frontWeakPassed: Boolean,
    val frontStrongPassed: Boolean,
)

/** FRONT-only composite result, sorted material for stage decisions. */
data class ScoredComposite(
    val candidateId: String,
    val compositeScore: Double,
    val frontScore: Double,
    val frontWeakPassed: Boolean,
    val frontStrongPassed: Boolean,
)

enum class RejectionRule {
    FRONT_WEAK_REQUIRED,
    FRONT_BELOW_AUTO_MIN,
    MARGIN_OVER_RUNNER_UP_TOO_SMALL,
    NO_STRONG_FRONT_EVIDENCE,
    DUPLICATE_GROUP_REQUIRES_DOMINANT_STRONG_FRONT,
    MULTIPLE_PLAUSIBLE_CANDIDATES,
}

/** FRONT-only verdict: whether the leading FRONT candidate opens automatically. */
data class CompositeAcceptanceReport(
    val scored: List<ScoredComposite>,
    val autoAccepted: ScoredComposite?,
    val rejectionRule: RejectionRule?,
)

/**
 * Stage 2 FRONT-only (docs/recognition.md section 9, ADR-012): the FRONT
 * `frontScore` is the composite. Apply ALL automatic acceptance rules to the
 * LEADING composite only. Multiple plausible candidates never auto-open
 * regardless of margin — they return Ambiguous via the classifier. Rules:
 *
 * 1. FRONT passes weak evidence;
 * 2. composite (== frontScore) is at least [RecognitionProfile.RankingThresholds.autoFrontMin];
 * 3. margin over runner-up at least [autoMarginOverRunnerUp], or no runner-up;
 * 4. FRONT passes strong evidence;
 * 5. when the FRONT formed a duplicate group, the FRONT itself must pass
 *    strong evidence, reach [duplicateFrontMinScore], and lead the next front
 *    score by at least [autoMarginOverRunnerUp].
 * 6. if ≥2 candidates are plausible (frontWeak && composite >= chooserFrontMin),
 *    never auto-open — requires explicit chooser.
 *
 * There is no best-candidate-wins fallback below these gates; anything
 * unaccepted flows to the chooser/recapture classifier (M1-M08).
 */
class CompositeAcceptanceEvaluator(
    private val profile: RecognitionProfile,
) {

    fun evaluate(
        candidates: List<CompositeCandidate>,
        duplicateFrontGroup: Boolean = false,
    ): CompositeAcceptanceReport {
        require(candidates.isNotEmpty()) { "candidate universe is empty" }

        val scored = candidates
            .map { candidate ->
                ScoredComposite(
                    candidateId = candidate.candidateId,
                    compositeScore = candidate.frontScore,
                    frontScore = candidate.frontScore,
                    frontWeakPassed = candidate.frontWeakPassed,
                    frontStrongPassed = candidate.frontStrongPassed,
                )
            }
            .sortedByDescending { it.compositeScore }

        if (scored.isEmpty()) {
            return CompositeAcceptanceReport(scored, null, null)
        }

        // FRONT-only: multiple plausible candidates never auto-open, even if
        // score-separated. This enforces design->N without verifier/grant.
        val plausibleCount = plausibleFrontCandidates(scored, profile.ranking.chooserFrontMin).size
        if (plausibleCount >= 2) {
            return CompositeAcceptanceReport(scored, null, RejectionRule.MULTIPLE_PLAUSIBLE_CANDIDATES)
        }

        val leader = scored.first()
        val rejection = firstFailingRule(leader, scored, duplicateFrontGroup)
        return if (rejection == null) {
            CompositeAcceptanceReport(scored, leader, null)
        } else {
            CompositeAcceptanceReport(scored, null, rejection)
        }
    }

    private fun firstFailingRule(
        leader: ScoredComposite,
        scored: List<ScoredComposite>,
        duplicateFrontGroup: Boolean,
    ): RejectionRule? {
        val ranking = profile.ranking
        if (!leader.frontWeakPassed) return RejectionRule.FRONT_WEAK_REQUIRED
        if (leader.compositeScore < ranking.autoFrontMin) return RejectionRule.FRONT_BELOW_AUTO_MIN

        val runnerUp = scored.getOrNull(1)
        if (runnerUp != null &&
            leader.compositeScore - runnerUp.compositeScore < ranking.autoMarginOverRunnerUp
        ) {
            return RejectionRule.MARGIN_OVER_RUNNER_UP_TOO_SMALL
        }
        if (!leader.frontStrongPassed) return RejectionRule.NO_STRONG_FRONT_EVIDENCE

        if (duplicateFrontGroup) {
            val next = scored.getOrNull(1)
            val frontMarginOk = next == null ||
                leader.frontScore - next.frontScore >= ranking.autoMarginOverRunnerUp
            val dominantStrongFront = leader.frontStrongPassed &&
                leader.frontScore >= ranking.duplicateFrontMinScore &&
                frontMarginOk
            if (!dominantStrongFront) {
                return RejectionRule.DUPLICATE_GROUP_REQUIRES_DOMINANT_STRONG_FRONT
            }
        }
        return null
    }
}
