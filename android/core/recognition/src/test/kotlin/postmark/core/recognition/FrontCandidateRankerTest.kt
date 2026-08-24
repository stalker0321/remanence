package postmark.core.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ranking/margin proof for M1-M06 front candidate ranking. */
class FrontCandidateRankerTest {

    private val ranker = FrontCandidateRanker(RecognitionProfile.mvpOrbV1())
    private val margin = RecognitionProfile.mvpOrbV1().ranking.duplicateFrontMargin

    private fun candidate(id: String, score: Double, weak: Boolean = true) =
        FrontCandidate(id, score, weak)

    @Test
    fun retainsOnlyWeakPassingCandidatesOrderedByScore() {
        val ranking = ranker.rank(
            listOf(
                candidate("weak-low", 0.30),
                candidate("failing", 0.90, weak = false),
                candidate("strong", 0.80),
                candidate("middle", 0.55),
            ),
        )

        assertEquals(listOf("strong", "middle", "weak-low"), ranking.retained.map { it.candidateId })
        assertFalse(ranking.duplicateFrontGroup)
        assertFalse(ranking.noMatchFront)
    }

    @Test
    fun retentionCapsAtFive() {
        val candidates = (0 until 9).map { index -> candidate("c$index", 0.40 + index * 0.05) }

        val ranking = ranker.rank(candidates)

        assertEquals(5, ranking.retained.size)
        assertEquals(
            listOf("c8", "c7", "c6", "c5", "c4"),
            ranking.retained.map { it.candidateId },
        )
    }

    @Test
    fun duplicateGroupFormsBelowTheMarginAndNotBeyondIt() {
        // Binary-exact differences avoid floating-point boundary ambiguity:
        // 0.0625 is below the 0.08 margin, 0.125 is beyond it.
        assertTrue(
            ranker.rank(listOf(candidate("a", 1.0), candidate("b", 1.0 - 0.0625))).duplicateFrontGroup,
        )
        assertFalse(
            ranker.rank(listOf(candidate("a", 1.0), candidate("b", 1.0 - 0.125))).duplicateFrontGroup,
        )
        assertTrue(margin > 0.0625 && margin < 0.125)
    }

    @Test
    fun singleSurvivorNeverFormsADuplicateGroup() {
        val ranking = ranker.rank(listOf(candidate("only", 0.5), candidate("failed", 0.99, weak = false)))

        assertFalse(ranking.duplicateFrontGroup)
        assertEquals(1, ranking.retained.size)
    }

    @Test
    fun noWeakCandidatesMeansNoMatchFront() {
        val ranking = ranker.rank(
            listOf(candidate("x", 0.9, weak = false), candidate("y", 0.8, weak = false)),
        )

        assertTrue(ranking.noMatchFront)
        assertTrue(ranking.retained.isEmpty())
        assertFalse(ranking.duplicateFrontGroup)
    }

    @Test
    fun emptyUniverseYieldsNoMatch() {
        assertTrue(ranker.rank(emptyList()).noMatchFront)
    }
}
