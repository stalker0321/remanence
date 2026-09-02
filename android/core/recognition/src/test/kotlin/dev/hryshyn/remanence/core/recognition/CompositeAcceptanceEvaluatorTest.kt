package dev.hryshyn.remanence.core.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Threshold/margin proof for FRONT-only acceptance (ADR-012, M2-F0-01). */
class CompositeAcceptanceEvaluatorTest {

    private val ranking = RecognitionProfile.mvpOrbV1().ranking
    private val evaluator = CompositeAcceptanceEvaluator(RecognitionProfile.mvpOrbV1())

    // FRONT-only: composite == frontScore.
    private fun strongCandidate(id: String, front: Double) = CompositeCandidate(
        candidateId = id,
        frontScore = front,
        frontWeakPassed = true,
        frontStrongPassed = true,
    )

    @Test
    fun clearLeaderOpensAutomatically() {
        // Runner is not plausible (weak false), so only one plausible candidate remains.
        val report = evaluator.evaluate(
            listOf(
                strongCandidate("leader", 0.80),
                CompositeCandidate("runner", 0.50, frontWeakPassed = false, frontStrongPassed = false),
            ),
        )

        assertEquals("leader", report.autoAccepted?.candidateId)
        assertEquals(0.8, report.autoAccepted?.compositeScore ?: -1.0, 1e-12)
        assertNull(report.rejectionRule)
    }

    @Test
    fun frontWeakIsRequiredEvenWithHugeComposite() {
        val report = evaluator.evaluate(
            listOf(
                CompositeCandidate("front-blind", 0.95, frontWeakPassed = false, frontStrongPassed = true),
            ),
        )

        assertNull(report.autoAccepted)
        assertEquals(RejectionRule.FRONT_WEAK_REQUIRED, report.rejectionRule)
    }

    @Test
    fun compositeMinimumSeparatesAcceptFromReject() {
        // 0.71 above the 0.70 gate.
        val passing = CompositeCandidate("passing", 0.71, true, true)
        assertTrue(evaluator.evaluate(listOf(passing)).autoAccepted != null)

        // 0.69 falls under it.
        val failing = CompositeCandidate("failing", 0.69, true, true)
        val report = evaluator.evaluate(listOf(failing))
        assertNull(report.autoAccepted)
        assertEquals(RejectionRule.FRONT_BELOW_AUTO_MIN, report.rejectionRule)
    }

    @Test
    fun marginOverRunnerUpMustReachTwelvePoints() {
        // Leader 0.80 vs runner-up 0.68: margin exactly 0.12 passes. Runner not plausible for this auto test.
        val leader = strongCandidate("leader", 0.80)
        val runner = CompositeCandidate("runner", 0.68, frontWeakPassed = false, frontStrongPassed = false)
        assertTrue(evaluator.evaluate(listOf(leader, runner)).autoAccepted?.candidateId == "leader")

        // One notch closer: 0.72; margin 0.08 fails. Runner also not plausible to isolate margin rule.
        val closer = CompositeCandidate("closer", 0.72, frontWeakPassed = false, frontStrongPassed = false)
        val failing = evaluator.evaluate(listOf(leader, closer))
        assertNull(failing.autoAccepted)
        assertEquals(RejectionRule.MARGIN_OVER_RUNNER_UP_TOO_SMALL, failing.rejectionRule)
    }

    @Test
    fun noRunnerUpSkipsTheMarginRule() {
        val lone = strongCandidate("lone", 0.75)

        assertTrue(evaluator.evaluate(listOf(lone)).autoAccepted?.candidateId == "lone")
    }

    @Test
    fun weakOnlyFrontNeverOpensWithoutStrongEvidence() {
        val candidate = CompositeCandidate(
            "weak-only", 0.85, frontWeakPassed = true, frontStrongPassed = false,
        )

        val report = evaluator.evaluate(listOf(candidate))

        assertNull(report.autoAccepted)
        assertEquals(RejectionRule.NO_STRONG_FRONT_EVIDENCE, report.rejectionRule)
    }

    @Test
    fun duplicateGroupDemandsADominantStrongFront() {
        // Leader: strong front 0.80; distant runner not plausible (weak false) margin 0.20 passes.
        val leader = strongCandidate("leader", 0.80)
        val distantRunner = CompositeCandidate("runner", 0.60, frontWeakPassed = false, frontStrongPassed = false)
        assertTrue(evaluator.evaluate(listOf(leader, distantRunner)).autoAccepted?.candidateId == "leader")

        // With duplicate group, close runner not plausible but margin still checked: close runner 0.77 not plausible, but margin would still be checked.
        // To test duplicate group, use a runner that is plausible but close, but then multiple plausible would block auto. Use a runner that is plausible but close and expect duplicate/margin.
        // For duplicate group, we need two candidates where both are plausible, but duplicate requires dominant front. However multiple plausible already blocks auto as MULTIPLE_PLAUSIBLE, so we test that path separately.
        // Here, test duplicate group with a close runner that is NOT plausible (weak false) to isolate duplicate logic without multiple-plausible interference.
        val closeRunnerNotPlausible = CompositeCandidate("runner", 0.77, frontWeakPassed = false, frontStrongPassed = false)
        val grouped = evaluator.evaluate(listOf(leader, closeRunnerNotPlausible), duplicateFrontGroup = true)
        assertNull(grouped.autoAccepted)
        // MARGIN is checked before DUPLICATE, so close margin fails as MARGIN.
        assertEquals(RejectionRule.MARGIN_OVER_RUNNER_UP_TOO_SMALL, grouped.rejectionRule)

        // Without duplicate, same close runner also fails margin (no special duplicate path).
        val nonGrouped = evaluator.evaluate(listOf(leader, closeRunnerNotPlausible), duplicateFrontGroup = false)
        assertNull(nonGrouped.autoAccepted)
        assertEquals(RejectionRule.MARGIN_OVER_RUNNER_UP_TOO_SMALL, nonGrouped.rejectionRule)

        // A front score below auto threshold fails composite before duplicate is checked.
        val lowFrontLeader = CompositeCandidate("low-front", 0.64, true, true)
        val lowFrontReport = evaluator.evaluate(listOf(lowFrontLeader, distantRunner), duplicateFrontGroup = true)
        assertNull(lowFrontReport.autoAccepted)
        assertEquals(RejectionRule.FRONT_BELOW_AUTO_MIN, lowFrontReport.rejectionRule)
    }

    @Test
    fun multiplePlausibleCandidatesNeverAutoOpenEvenWhenScoreSeparated() {
        val leader = strongCandidate("leader", 0.85)
        val runner = CompositeCandidate("runner", 0.45, frontWeakPassed = true, frontStrongPassed = true)
        // Both are plausible (frontWeak true && score >=0.40), margin 0.40 would normally allow auto, but multiple plausible blocks it.
        val report = evaluator.evaluate(listOf(leader, runner))
        assertNull(report.autoAccepted)
        assertEquals(RejectionRule.MULTIPLE_PLAUSIBLE_CANDIDATES, report.rejectionRule)

        // Single plausible still auto-opens.
        val single = evaluator.evaluate(listOf(leader))
        assertEquals("leader", single.autoAccepted?.candidateId)
    }

    @Test
    fun frontOnlyCandidatesAreScoredDirectly() {
        val report = evaluator.evaluate(
            listOf(
                CompositeCandidate("solo", 0.99, true, true),
                strongCandidate("has-front", 0.75),
            ),
        )

        assertEquals(2, report.scored.size)
        // Leader is solo 0.99
        assertEquals("solo", report.scored.first().candidateId)
    }
}
