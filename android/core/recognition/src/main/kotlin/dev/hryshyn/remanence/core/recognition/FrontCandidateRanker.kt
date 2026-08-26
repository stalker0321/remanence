package dev.hryshyn.remanence.core.recognition

/**
 * One scored front candidate entering hierarchical ranking. [sideScore] comes
 * from SideScorer; [weakGatePassed] decides whether it may be retained at all.
 */
data class FrontCandidate(
    val candidateId: String,
    val sideScore: Double,
    val weakGatePassed: Boolean,
)

/** M1-M06 outcome: the retained universe plus duplicate-front classification. */
data class FrontRanking(
    val retained: List<FrontCandidate>,
    val duplicateFrontGroup: Boolean,
) {
    /** True when no candidate passed weak evidence: NO_MATCH_FRONT, recapture. */
    val noMatchFront: Boolean get() = retained.isEmpty()
}

/**
 * Stage 1 of docs/recognition.md section 9: keep up to five front candidates
 * that pass weak evidence, ordered by score (stable on ties), and mark a
 * duplicate-front group when the two leading scores differ by less than the
 * configured margin — identical mass-produced designs are expected there.
 * A unique strong front never skips back capture; that decision belongs to
 * stage 2, not here.
 */
class FrontCandidateRanker(
    private val profile: RecognitionProfile,
) {

    fun rank(candidates: List<FrontCandidate>): FrontRanking {
        val eligible = candidates
            .filter { it.weakGatePassed }
            .sortedByDescending { it.sideScore }
        val retained = eligible.take(RETAINED_LIMIT)
        val duplicateGroup = retained.size >= DUPLICATE_GROUP_SIZE &&
            (retained[0].sideScore - retained[1].sideScore) < profile.ranking.duplicateFrontMargin
        return FrontRanking(
            retained = retained,
            duplicateFrontGroup = duplicateGroup,
        )
    }

    internal companion object {
        const val RETAINED_LIMIT = 5
        const val DUPLICATE_GROUP_SIZE = 2
    }
}
