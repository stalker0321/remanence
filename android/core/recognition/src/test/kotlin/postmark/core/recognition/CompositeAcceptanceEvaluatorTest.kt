package postmark.core.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Threshold/margin proof for M1-M07 automatic acceptance. */
class CompositeAcceptanceEvaluatorTest {

    private val ranking = RecognitionProfile.mvpOrbV1().ranking
    private val evaluator = CompositeAcceptanceEvaluator(RecognitionProfile.mvpOrbV1())

    // With weights 0.40/0.60: composite = 0.4*front + 0.6*back.
    private fun strongCandidate(id: String, front: Double, back: Double) = CompositeCandidate(
        candidateId = id,
        frontScore = front,
        frontWeakPassed = true,
        frontStrongPassed = true,
        back = BackMatchResult(backScore = back, backWeakPassed = true, backStrongPassed = true),
    )

    @Test
    fun clearLeaderOpensAutomatically() {
        val report = evaluator.evaluate(
            listOf(
                strongCandidate("leader", 0.80, 0.80), // composite 0.80
                CompositeCandidate(
                    "runner", 0.50, true, false,
                    BackMatchResult(0.40, true, false),
                ), // composite 0.44
            ),
        )

        assertEquals("leader", report.autoAccepted?.candidateId)
        assertEquals(0.8, report.autoAccepted?.compositeScore ?: -1.0, 1e-12)
        assertNull(report.rejectionRule)
    }

    @Test
    fun bothSidesWeakIsRequiredEvenWithHugeComposite() {
        val report = evaluator.evaluate(
            listOf(
                CompositeCandidate(
                    "front-strong-back-blind", 0.95, true, true,
                    BackMatchResult(0.90, backWeakPassed = false, backStrongPassed = false),
                ),
            ),
        )

        assertNull(report.autoAccepted)
        assertEquals(RejectionRule.BOTH_SIDES_WEAK_REQUIRED, report.rejectionRule)
    }

    @Test
    fun compositeMinimumSeparatesAcceptFromReject() {
        // 0.4*1.0 + 0.6*0.501 = 0.7006 comfortably above the 0.70 gate.
        val passing = CompositeCandidate(
            "passing", 1.0, true, true,
            BackMatchResult(0.501, true, true),
        )
        assertTrue(evaluator.evaluate(listOf(passing)).autoAccepted != null)

        // One notch lower: 0.4*1.0 + 0.6*0.49 = 0.694 falls under it.
        val failing = CompositeCandidate(
            "failing", 1.0, true, true,
            BackMatchResult(0.49, true, true),
        )
        val report = evaluator.evaluate(listOf(failing))
        assertNull(report.autoAccepted)
        assertEquals(RejectionRule.COMPOSITE_BELOW_MINIMUM, report.rejectionRule)
    }

    @Test
    fun marginOverRunnerUpMustReachTwelvePoints() {
        // Leader composite 0.80 vs runner-up 0.68: margin exactly 0.12 passes.
        val leader = strongCandidate("leader", 0.80, 0.80)
        // runner-up: front 0.5/back 0.8 -> 0.2+0.48=0.68.
        val runner = CompositeCandidate(
            "runner", 0.5, true, false,
            BackMatchResult(0.8, true, true),
        )
        assertTrue(evaluator.evaluate(listOf(leader, runner)).autoAccepted?.candidateId == "leader")

        // One notch closer: front 0.6/back 0.8 -> 0.24+0.48=0.72; margin 0.08 fails.
        val closer = CompositeCandidate(
            "closer", 0.6, true, false,
            BackMatchResult(0.8, true, true),
        )
        val failing = evaluator.evaluate(listOf(leader, closer))
        assertNull(failing.autoAccepted)
        assertEquals(RejectionRule.MARGIN_OVER_RUNNER_UP_TOO_SMALL, failing.rejectionRule)
    }

    @Test
    fun noRunnerUpSkipsTheMarginRule() {
        val lone = strongCandidate("lone", 0.75, 0.75)

        assertTrue(evaluator.evaluate(listOf(lone)).autoAccepted?.candidateId == "lone")
    }

    @Test
    fun weakOnlySidesNeverOpenWithoutStrongEvidence() {
        val candidate = CompositeCandidate(
            "weak-only", 0.85, frontWeakPassed = true, frontStrongPassed = false,
            back = BackMatchResult(0.85, true, false), // composite 0.85 >= 0.70
        )

        val report = evaluator.evaluate(listOf(candidate))

        assertNull(report.autoAccepted)
        assertEquals(RejectionRule.NO_STRONG_SIDE_EVIDENCE, report.rejectionRule)
    }

    @Test
    fun duplicateGroupDemandsADominantStrongBack() {
        // Leader: strong back at exactly the 0.65 floor; runner-up back only
        // 0.03 behind - inside the required 0.12 lead.
        val leader = strongCandidate("leader", 0.95, 0.65)
        val runnerUp = CompositeCandidate(
            "runner", 0.40, true, false,
            BackMatchResult(0.62, true, false),
        )

        // Composite margins are fine (0.77 vs 0.532), but the duplicate-group
        // rule demands the back itself to dominate by 0.12.
        val grouped = evaluator.evaluate(listOf(leader, runnerUp), duplicateFrontGroup = true)
        assertNull(grouped.autoAccepted)
        assertEquals(RejectionRule.DUPLICATE_GROUP_REQUIRES_DOMINANT_STRONG_BACK, grouped.rejectionRule)

        // Without a duplicate group the same universe opens normally.
        assertTrue(evaluator.evaluate(listOf(leader, runnerUp)).autoAccepted?.candidateId == "leader")

        // A back score below the duplicateFrontBackMin floor also rejects,
        // even when its own margin over the runner-up would be large enough.
        val lowBackLeader = strongCandidate("low-back", 0.95, 0.64)
        val lowBackReport = evaluator.evaluate(listOf(lowBackLeader, runnerUp), duplicateFrontGroup = true)
        assertNull(lowBackReport.autoAccepted)
        assertEquals(RejectionRule.DUPLICATE_GROUP_REQUIRES_DOMINANT_STRONG_BACK, lowBackReport.rejectionRule)
    }

    @Test
    fun candidatesWithoutBackResultsAreExcludedFromComposites() {
        val report = evaluator.evaluate(
            listOf(
                CompositeCandidate("no-back", 0.99, true, true, null),
                strongCandidate("has-back", 0.75, 0.75),
            ),
        )

        assertEquals(1, report.scored.size)
        assertEquals("has-back", report.autoAccepted?.candidateId)
    }
}
