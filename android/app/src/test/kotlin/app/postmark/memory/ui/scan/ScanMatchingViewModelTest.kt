package app.postmark.memory.ui.scan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Retry/matching state proof for M1-M12. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanMatchingViewModelTest {

    private fun scope() = CoroutineScope(UnconfinedTestDispatcher())

    @Test
    fun autoAcceptedOutcomeSurfacesCandidateAndFallbackFlag() = runTest {
        val vm = ScanMatchingViewModel(
            matcher = { MatchOutcome(MatchOutcomeKind.AUTO_ACCEPTED, acceptedCandidateId = "cap-1") },
            scope = scope(),
        )

        vm.evaluateCurrentCapture()

        val state = vm.state.value as ScanMatchUiState.Accepted
        assertEquals("cap-1", state.candidateId)
        assertTrue(!state.viaSenderFallback)
    }

    @Test
    fun senderFallbackAcceptanceCarriesTheDiagnosticFlag() = runTest {
        val vm = ScanMatchingViewModel(
            matcher = {
                MatchOutcome(MatchOutcomeKind.AUTO_ACCEPTED, acceptedCandidateId = "snd", viaSenderFallback = true)
            },
            scope = scope(),
        )

        vm.evaluateCurrentCapture()

        val state = vm.state.value as ScanMatchUiState.Accepted
        assertTrue(state.viaSenderFallback)
    }

    @Test
    fun chooserRowsArriveSortedByDescendingScore() = runTest {
        val vm = ScanMatchingViewModel(
            matcher = {
                MatchOutcome(
                    MatchOutcomeKind.CHOOSER,
                    chooserRows = listOf(
                        ChooserRow("low", 0.42),
                        ChooserRow("top", 0.61),
                        ChooserRow("mid", 0.55),
                    ),
                )
            },
            scope = scope(),
        )

        vm.evaluateCurrentCapture()

        val state = vm.state.value as ScanMatchUiState.Chooser
        assertEquals(listOf("top", "mid", "low"), state.rows.map { it.candidateId })
    }

    @Test
    fun singlePlausibleFirstAsksGuidedRecaptureThenExplicitConfirmation() = runTest {
        val script = ArrayDeque(
            listOf(
                MatchOutcome(MatchOutcomeKind.SINGLE_RECAPTURE, singlePlausible = ChooserRow("only", 0.5)),
                MatchOutcome(MatchOutcomeKind.SINGLE_RECAPTURE, singlePlausible = ChooserRow("only", 0.48)),
            ),
        )
        val vm = ScanMatchingViewModel(matcher = { script.removeFirstOrNull()!! }, scope = scope())

        vm.evaluateCurrentCapture()
        assertEquals(ScanMatchUiState.GuidedRecapture(1), vm.state.value)

        vm.evaluateCurrentCapture()
        val second = vm.state.value as ScanMatchUiState.ConfirmSingle
        assertEquals("only", second.row.candidateId)

        // A third hit never loops: it stays on explicit confirmation.
        script += MatchOutcome(MatchOutcomeKind.SINGLE_RECAPTURE, singlePlausible = ChooserRow("only", 0.47))
        vm.evaluateCurrentCapture()
        assertTrue(vm.state.value is ScanMatchUiState.ConfirmSingle)
    }

    @Test
    fun noMatchAccumulatesGuidanceAttempts() = runTest {
        val script = ArrayDeque(
            listOf(MatchOutcome(MatchOutcomeKind.NO_MATCH), MatchOutcome(MatchOutcomeKind.NO_MATCH)),
        )
        val vm = ScanMatchingViewModel(matcher = { script.removeFirstOrNull()!! }, scope = scope())

        vm.evaluateCurrentCapture()
        assertEquals(1, (vm.state.value as ScanMatchUiState.RecaptureGuidance).failedAttempts)

        vm.evaluateCurrentCapture()
        assertEquals(2, (vm.state.value as ScanMatchUiState.RecaptureGuidance).failedAttempts)
        assertEquals(2, vm.failedMatches)
    }

    @Test
    fun concurrentEvaluationIsIgnoredWhileOneIsInFlight() = runTest {
        var evaluations = 0
        val vm = ScanMatchingViewModel(
            matcher = {
                evaluations += 1
                MatchOutcome(MatchOutcomeKind.NO_MATCH)
            },
            scope = this,
        )

        // On the test scheduler nothing runs until we advance it, so both calls
        // race while the first evaluation is still in flight.
        vm.evaluateCurrentCapture()
        vm.evaluateCurrentCapture()
        advanceUntilIdle()

        assertEquals(1, evaluations)
        assertEquals(ScanMatchUiState.RecaptureGuidance(1), vm.state.value)
    }

    @Test
    fun negativeGuidedRecaptureBudgetIsRejected() = runTest {
        try {
            ScanMatchingViewModel(matcher = { error("unused") }, scope = scope(), maxGuidedRecaptures = -1)
            throw AssertionError("expected failure")
        } catch (expected: IllegalArgumentException) {
            assertEquals("guided recapture budget cannot be negative", expected.message)
        }
    }
}
