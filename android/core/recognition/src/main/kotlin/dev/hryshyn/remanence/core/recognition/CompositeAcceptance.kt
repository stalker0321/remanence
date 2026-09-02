package dev.hryshyn.remanence.core.recognition

import kotlin.math.abs

/**
 * FRONT-only production contract (ADR-012, M2-F0-01): candidate carries exactly
 * one required FRONT score. Legacy two-sided composite (front+back weighted) is
 * deleted; composite == frontScore. Old two-sided payloads fail closed at the
 * fingerprint codec before reaching this evaluator.
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
) {
    val frontWeak: Boolean get() = frontWeakPassed
    val frontStrong: Boolean get() = frontStrongPassed
}

enum class RejectionRule {
    BOTH_SIDES_WEAK_REQUIRED,
    COMPOSITE_BELOW_MINIMUM,
    MARGIN_OVER_RUNNER_UP_TOO_SMALL,
    NO_STRONG_SIDE_EVIDENCE,
    DUPLICATE_GROUP_REQUIRES_DOMINANT_STRONG_BACK,
}

/** M2-F0-01 FRONT-only verdict: whether the leading FRONT candidate opens automatically. */
data class CompositeAcceptanceReport(
    val scored: List<ScoredComposite>,
    val autoAccepted: ScoredComposite?,
    val rejectionRule: RejectionRule?,
)

/**
 * Stage 2 FRONT-only (docs/recognition.md section 9, ADR-012): the FRONT
 * `frontScore` is the composite. Apply ALL automatic acceptance rules to the
 * LEADING composite only:
 *
 * 1. FRONT passes weak evidence;
 * 2. composite (== frontScore) is at least [RecognitionProfile.RankingThresholds.autoCompositeMin];
 * 3. margin over runner-up at least [autoMarginOverRunnerUp], or no runner-up;
 * 4. FRONT passes strong evidence;
 * 5. when the FRONT formed a duplicate group, the FRONT itself must pass
 *    strong evidence, reach [duplicateFrontBackMinScore] (reused as front
 *    threshold), and lead the next front score by at least [autoMarginOverRunnerUp].
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
        val ranking = profile.ranking
        val weightsSum = ranking.compositeFrontWeight + ranking.compositeBackWeight
        if (abs(weightsSum - ONE) > WEIGHT_EPSILON) {
            throw IllegalArgumentException("composite weights must sum to one")
        }

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
        if (!leader.frontWeakPassed) return RejectionRule.BOTH_SIDES_WEAK_REQUIRED
        if (leader.compositeScore < ranking.autoCompositeMin) return RejectionRule.COMPOSITE_BELOW_MINIMUM

        val runnerUp = scored.getOrNull(1)
        if (runnerUp != null &&
            leader.compositeScore - runnerUp.compositeScore < ranking.autoMarginOverRunnerUp
        ) {
            return RejectionRule.MARGIN_OVER_RUNNER_UP_TOO_SMALL
        }
        if (!leader.frontStrongPassed) return RejectionRule.NO_STRONG_SIDE_EVIDENCE

        if (duplicateFrontGroup) {
            val next = scored.getOrNull(1)
            val frontMarginOk = next == null ||
                leader.frontScore - next.frontScore >= ranking.autoMarginOverRunnerUp
            val dominantStrongFront = leader.frontStrongPassed &&
                leader.frontScore >= ranking.duplicateFrontBackMinScore &&
                frontMarginOk
            if (!dominantStrongFront) {
                return RejectionRule.DUPLICATE_GROUP_REQUIRES_DOMINANT_STRONG_BACK
            }
        }
        return null
    }

    internal companion object {
        const val ONE = 1.0
        const val WEIGHT_EPSILON = 1e-9
    }
}
