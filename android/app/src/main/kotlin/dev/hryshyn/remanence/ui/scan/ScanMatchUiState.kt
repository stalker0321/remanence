package dev.hryshyn.remanence.ui.scan

/**
 * Live retry/matching state of one scan flow (docs/recognition.md sections
 * 9, 12). FIX-STATE-05: the unreachable GuidedRecapture/ConfirmSingle
 * variants were removed from the production state surface - the production
 * matcher only ever produces AwaitingCapture, Matching, Accepted, Chooser,
 * and RecaptureGuidance, and every remaining state renders a working action.
 */
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

    /** Nothing plausible: show recapture guidance; never arbitrary capsules. */
    data class RecaptureGuidance(val failedAttempts: Int) : ScanMatchUiState
}
