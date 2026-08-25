package app.postmark.memory.ui.scan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Result of running the local hierarchy over one captured pair. */
enum class MatchOutcomeKind { AUTO_ACCEPTED, CHOOSER, SINGLE_RECAPTURE, NO_MATCH }

data class MatchOutcome(
    val kind: MatchOutcomeKind,
    val acceptedCandidateId: String? = null,
    val viaSenderFallback: Boolean = false,
    val chooserRows: List<ChooserRow> = emptyList(),
    val singlePlausible: ChooserRow? = null,
)

/**
 * Port over the local matching pipeline (M1-M01..M1-M09): consumes both
 * scanned sides of the current capture session and produces the classified
 * coordinator decision. The real adapter wires descriptors into the matcher;
 * fakes drive the ViewModel tests.
 */
fun interface ScanMatcherPort {
    suspend fun match(): MatchOutcome
}

/** Live retry/matching state of one scan flow (docs/recognition.md sections 9, 12). */
sealed interface ScanMatchUiState {

    /**
     * FIX-REVIEW-01: entry state of every fresh scan session - the FRONT
     * camera is reachable BEFORE any matching exists, then BACK, and only a
     * complete capture pair may advance to [Matching].
     */
    data object AwaitingCapture : ScanMatchUiState

    data object Matching : ScanMatchUiState

    /** Automatic rules passed; crypto verification happens before any grant. */
    data class Accepted(
        val candidateId: String,
        val viaSenderFallback: Boolean,
    ) : ScanMatchUiState

    /** Two or more plausible candidates; user picks from minimal-hint rows. */
    data class Chooser(val rows: List<ChooserRow>) : ScanMatchUiState

    /** First single-plausible hit: one guided recapture before confirming. */
    data class GuidedRecapture(val attempt: Int) : ScanMatchUiState

    /** The guided recapture also landed on one candidate: allow explicit confirm. */
    data class ConfirmSingle(val row: ChooserRow) : ScanMatchUiState

    /** Nothing plausible: show recapture guidance; never arbitrary capsules. */
    data class RecaptureGuidance(val failedAttempts: Int) : ScanMatchUiState
}

/**
 * M1-M12: wires the local candidate-matching pipeline and its retry state.
 * The classifier semantics live in :core:recognition; this ViewModel only
 * tracks attempts so the documented "one guided recapture, then explicit
 * confirmation" rule cannot loop forever, and so failures always end in
 * actionable guidance instead of arbitrary capsule suggestions.
 */
class ScanMatchingViewModel(
    private val matcher: ScanMatcherPort,
    private val scope: CoroutineScope,
    private val maxGuidedRecaptures: Int = DEFAULT_GUIDED_RECAPTURES,
) {

    init {
        require(maxGuidedRecaptures >= 0) { "guided recapture budget cannot be negative" }
    }

    private val _state = MutableStateFlow<ScanMatchUiState>(ScanMatchUiState.Matching)
    val state: StateFlow<ScanMatchUiState> = _state.asStateFlow()

    var guidedRecapturesUsed: Int = 0
        private set

    var failedMatches: Int = 0
        private set

    private var evaluationInFlight = false

    /**
     * Runs one matching pass over the freshly captured sides. Concurrent
     * invocations are ignored while an evaluation is still in flight; callers
     * invoke this again after each new capture pair.
     */
    fun evaluateCurrentCapture() {
        if (evaluationInFlight) return
        evaluationInFlight = true
        _state.value = ScanMatchUiState.Matching
        scope.launch {
            try {
                _state.value = apply(matcher.match())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = apply(MatchOutcome(MatchOutcomeKind.NO_MATCH))
            } finally {
                evaluationInFlight = false
            }
        }
    }

    private fun apply(outcome: MatchOutcome): ScanMatchUiState = when (outcome.kind) {
        MatchOutcomeKind.AUTO_ACCEPTED ->
            ScanMatchUiState.Accepted(
                candidateId = requireNotNull(outcome.acceptedCandidateId),
                viaSenderFallback = outcome.viaSenderFallback,
            )
        MatchOutcomeKind.CHOOSER ->
            ScanMatchUiState.Chooser(outcome.chooserRows.sortedByDescending { it.compositeScore })
        MatchOutcomeKind.SINGLE_RECAPTURE ->
            if (guidedRecapturesUsed < maxGuidedRecaptures) {
                guidedRecapturesUsed += 1
                ScanMatchUiState.GuidedRecapture(guidedRecapturesUsed)
            } else {
                ScanMatchUiState.ConfirmSingle(
                    requireNotNull(outcome.singlePlausible) { "single recapture without a candidate" },
                )
            }
        MatchOutcomeKind.NO_MATCH -> {
            failedMatches += 1
            ScanMatchUiState.RecaptureGuidance(failedMatches)
        }
    }

    internal companion object {
        const val DEFAULT_GUIDED_RECAPTURES = 1
    }
}
