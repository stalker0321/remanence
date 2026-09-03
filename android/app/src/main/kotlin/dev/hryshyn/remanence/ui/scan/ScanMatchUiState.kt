package dev.hryshyn.remanence.ui.scan

import dev.hryshyn.remanence.core.recognition.CandidateOrigin

/**
 * Live retry/matching state of one scan flow (docs/recognition.md sections
 * 9, 12). FIX-STATE-05: the unreachable GuidedRecapture/ConfirmSingle
 * variants were removed from the production state surface - the production
 * matcher only ever produces AwaitingCapture, Matching, Accepted, Chooser,
 * RecaptureGuidance, and MaterialPending, and every remaining state renders a
 * working action.
 */
sealed interface ScanMatchUiState {

    /**
     * M2-F0-07: entry state of every fresh scan session - the FRONT
     * camera is reachable BEFORE any matching exists, and one accepted
     * FRONT advances to [Matching].
     */
    data object AwaitingCapture : ScanMatchUiState

    data object Matching : ScanMatchUiState

    /** Automatic rules passed; crypto verification happens before any grant. */
    data class Accepted(
        val candidateId: String,
        val viaSenderFallback: Boolean,
    ) : ScanMatchUiState

    /** Two or more plausible candidates; user picks from minimal-hint rows. */
    data class Chooser(
        val rows: List<ChooserRow>,
        val origin: CandidateOrigin,
        val generation: Int,
    ) : ScanMatchUiState

    /** Nothing plausible: show recapture guidance; never arbitrary capsules. */
    data class RecaptureGuidance(val failedAttempts: Int) : ScanMatchUiState

    /**
     * The scan recognized an owner-scoped incoming capsule whose sender index
     * is local ([dev.hryshyn.remanence.core.model.LocalMaterialState.INDEX_CACHED])
     * but encrypted body material is not yet
     * [dev.hryshyn.remanence.core.model.LocalMaterialState.MATERIAL_CACHED].
     * This is not a recognition failure: existing owner-scoped KEEP sync /
     * prefetch continues, and the ordinary offline grant path resumes once
     * material is cached. [connected] selects the online vs offline copy.
     */
    data class MaterialPending(
        val capsuleId: String,
        val connected: Boolean,
    ) : ScanMatchUiState
}
