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
        val report = evaluator.evaluate(
            listOf(
                strongCandidate("leader", 0.80),
                CompositeCandidate("runner", 0.50, true, false),
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
        assertEquals(RejectionRule.BOTH_SIDES_WEAK_REQUIRED, report.rejectionRule)
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
        assertEquals(RejectionRule.COMPOSITE_BELOW_MINIMUM, report.rejectionRule)
    }

    @Test
    fun marginOverRunnerUpMustReachTwelvePoints() {
        // Leader 0.80 vs runner-up 0.68: margin exactly 0.12 passes.
        val leader = strongCandidate("leader", 0.80)
        val runner = CompositeCandidate("runner", 0.68, true, true)
        assertTrue(evaluator.evaluate(listOf(leader, runner)).autoAccepted?.candidateId == "leader")

        // One notch closer: 0.72; margin 0.08 fails.
        val closer = CompositeCandidate("closer", 0.72, true, true)
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
        assertEquals(RejectionRule.NO_STRONG_SIDE_EVIDENCE, report.rejectionRule)
    }

    @Test
    fun duplicateGroupDemandsADominantStrongFront() {
        // Leader: strong front 0.80; distant runner 0.60 margin 0.20 passes.
        val leader = strongCandidate("leader", 0.80)
        val distantRunner = CompositeCandidate("runner", 0.60, true, false)
        assertTrue(evaluator.evaluate(listOf(leader, distantRunner)).autoAccepted?.candidateId == "leader")

        // With duplicate group, close runner 0.77 only 0.03 behind - inside the required 0.12 lead.
        // FRONT-only duplicate margin is same as composite margin, so it fails MARGIN first.
        val closeRunner = CompositeCandidate("runner", 0.77, true, false)
        val grouped = evaluator.evaluate(listOf(leader, closeRunner), duplicateFrontGroup = true)
        assertNull(grouped.autoAccepted)
        // MARGIN is checked before DUPLICATE, so close margin fails as MARGIN.
        assertEquals(RejectionRule.MARGIN_OVER_RUNNER_UP_TOO_SMALL, grouped.rejectionRule)

        // Without duplicate, same close runner also fails margin (no special duplicate path).
        val nonGrouped = evaluator.evaluate(listOf(leader, closeRunner), duplicateFrontGroup = false)
        assertNull(nonGrouped.autoAccepted)
        assertEquals(RejectionRule.MARGIN_OVER_RUNNER_UP_TOO_SMALL, nonGrouped.rejectionRule)

        // A front score below auto threshold fails composite before duplicate is checked.
        val lowFrontLeader = CompositeCandidate("low-front", 0.64, true, true)
        val lowFrontReport = evaluator.evaluate(listOf(lowFrontLeader, distantRunner), duplicateFrontGroup = true)
        assertNull(lowFrontReport.autoAccepted)
        assertEquals(RejectionRule.COMPOSITE_BELOW_MINIMUM, lowFrontReport.rejectionRule)
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
