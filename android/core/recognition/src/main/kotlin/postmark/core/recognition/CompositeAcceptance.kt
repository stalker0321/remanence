package postmark.core.recognition

import kotlin.math.abs

/** Per-side evidence outcome for one candidate after back matching ran. */
data class BackMatchResult(
    val backScore: Double,
    val backWeakPassed: Boolean,
    val backStrongPassed: Boolean,
)

/** One candidate with its front scores plus the matched back result. */
data class CompositeCandidate(
    val candidateId: String,
    val frontScore: Double,
    val frontWeakPassed: Boolean,
    val frontStrongPassed: Boolean,
    val back: BackMatchResult?,
)

/** Composite result for one candidate, sorted material for stage decisions. */
data class ScoredComposite(
    val candidateId: String,
    val compositeScore: Double,
    val frontScore: Double,
    val backScore: Double,
    val frontWeakPassed: Boolean,
    val frontStrongPassed: Boolean,
    val backWeakPassed: Boolean,
    val backStrongPassed: Boolean,
) {
    val bothSidesWeak: Boolean get() = frontWeakPassed && backWeakPassed
    val anySideStrong: Boolean get() = frontStrongPassed || backStrongPassed
}

enum class RejectionRule {
    BOTH_SIDES_WEAK_REQUIRED,
    COMPOSITE_BELOW_MINIMUM,
    MARGIN_OVER_RUNNER_UP_TOO_SMALL,
    NO_STRONG_SIDE_EVIDENCE,
    DUPLICATE_GROUP_REQUIRES_DOMINANT_STRONG_BACK,
}

/** M1-M07 verdict: whether the leading candidate opens automatically. */
data class CompositeAcceptanceReport(
    val scored: List<ScoredComposite>,
    val autoAccepted: ScoredComposite?,
    val rejectionRule: RejectionRule?,
)

/**
 * Stage 2 of docs/recognition.md section 9: combine front and back scores as
 * `compositeFrontWeight * front + compositeBackWeight * back`, then apply ALL
 * automatic acceptance rules to the LEADING composite only:
 *
 * 1. both sides pass weak evidence;
 * 2. composite is at least [RecognitionProfile.RankingThresholds.autoCompositeMin];
 * 3. margin over runner-up at least [autoMarginOverRunnerUp], or no runner-up;
 * 4. at least one side passes strong evidence;
 * 5. when the FRONT formed a duplicate group, the back itself must pass
 *    strong evidence, reach [duplicateFrontBackMin], and lead the next back
 *    score by at least [autoMarginOverRunnerUp].
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
            .filter { it.back != null }
            .map { candidate ->
                val back = candidate.back!!
                ScoredComposite(
                    candidateId = candidate.candidateId,
                    compositeScore = ranking.compositeFrontWeight * candidate.frontScore +
                        ranking.compositeBackWeight * back.backScore,
                    frontScore = candidate.frontScore,
                    backScore = back.backScore,
                    frontWeakPassed = candidate.frontWeakPassed,
                    frontStrongPassed = candidate.frontStrongPassed,
                    backWeakPassed = back.backWeakPassed,
                    backStrongPassed = back.backStrongPassed,
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
        if (!leader.bothSidesWeak) return RejectionRule.BOTH_SIDES_WEAK_REQUIRED
        if (leader.compositeScore < ranking.autoCompositeMin) return RejectionRule.COMPOSITE_BELOW_MINIMUM

        val runnerUp = scored.getOrNull(1)
        if (runnerUp != null &&
            leader.compositeScore - runnerUp.compositeScore < ranking.autoMarginOverRunnerUp
        ) {
            return RejectionRule.MARGIN_OVER_RUNNER_UP_TOO_SMALL
        }
        if (!leader.anySideStrong) return RejectionRule.NO_STRONG_SIDE_EVIDENCE

        if (duplicateFrontGroup) {
            val nextBack = scored.getOrNull(1)
            val backMarginOk = nextBack == null ||
                leader.backScore - nextBack.backScore >= ranking.autoMarginOverRunnerUp
            val dominantStrongBack = leader.backStrongPassed &&
                leader.backScore >= ranking.duplicateFrontBackMinScore &&
                backMarginOk
            if (!dominantStrongBack) {
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
