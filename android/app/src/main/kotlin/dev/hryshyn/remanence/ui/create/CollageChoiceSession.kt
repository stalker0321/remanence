package dev.hryshyn.remanence.ui.create

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.hryshyn.remanence.ui.collage.CollageCandidate
import java.util.UUID

/**
 * Sender collage chooser state. The built candidate list is stable: it only
 * changes through [replaceCandidates] (an explicit rebuild) or [clear] (an edit
 * invalidation). [previous]/[next] move the selection index without rebuilding,
 * and no candidate becomes publishable until the user calls [select]
 * explicitly.
 */
class CollageChoiceSession {

    private var draftId = UUID.randomUUID().toString()
    private var variantOrder = CollageVariantOrder(draftId)

    internal var draftGeneration: Long by mutableStateOf(0L)
        private set

    /** New capsule only; repeated entry/rotation and ordinary edits keep its order. */
    fun beginDraft(capsuleId: String) {
        if (capsuleId == draftId) return
        draftId = capsuleId
        variantOrder = CollageVariantOrder(capsuleId)
        draftGeneration += 1
        clear()
        onCandidatesReplaced?.invoke()
    }

    internal fun arrangePreparedCandidates(built: List<CollageCandidate>): List<CollageCandidate> =
        variantOrder.arrangePrepared(built)

    var candidates: List<CollageCandidate> by mutableStateOf(emptyList())
        private set

    /**
     * Browse cursor: always valid (0-based) once candidates exist, so the first
     * candidate is displayed as soon as it is ready — independent of any
     * explicit selection. Never null while [candidates] is non-empty.
     */
    var displayIndex: Int by mutableStateOf(0)
        private set

    /** Null until the user explicitly selects a candidate. */
    var selectedIndex: Int? by mutableStateOf(null)
        private set

    /**
     * Fired after every fresh candidate build ([replaceCandidates]); the
     * creation session uses it to drop its frozen selection even when IDs
     * happen to overlap. Null by default (no-op).
     */
    var onCandidatesReplaced: (() -> Unit)? = null
    /** Privacy observer only: clearing a cohort must discard optional collection immediately. */
    internal var onTelemetryInvalidated: (() -> Unit)? = null

    /** Typed invalidation/unavailability copy; non-null means no candidates. */
    var notice: String? by mutableStateOf(null)
        private set

    var isPreparing: Boolean by mutableStateOf(false)
        private set

    private var preparationGeneration = 0L

    fun isCurrentPreparation(generation: Long): Boolean = preparationGeneration == generation

    fun beginPreparing(): Long {
        clear()
        isPreparing = true
        onCandidatesReplaced?.invoke()
        return preparationGeneration
    }

    /**
     * Monotonic fresh-build generation, incremented on every
     * [replaceCandidates] (and only there — in-place
     * [replaceCandidate] corrections must not invalidate carry-over
     * proofs). The host's readiness-report effect keys on this so a
     * value-equal rebuild still forces one fresh report after the
     * proofs were cleared.
     */
    var buildGeneration: Long by mutableStateOf(0L)
        private set

    /** The candidate currently shown to the user (browse cursor), if any. */
    val displayed: CollageCandidate?
        get() = candidates.getOrNull(displayIndex)

    val selected: CollageCandidate?
        get() = selectedIndex?.let(candidates::getOrNull)

    val hasSelection: Boolean
        get() = selected != null

    /** A fresh build resets the browse cursor to the first candidate and drops
     *  any explicit selection; it never auto-freezes. */
    fun replaceCandidates(built: List<CollageCandidate>) {
        isPreparing = false
        candidates = built
        displayIndex = 0
        selectedIndex = null
        notice = null
        buildGeneration += 1
        onCandidatesReplaced?.invoke()
    }

    fun showNotice(reason: String) {
        onTelemetryInvalidated?.invoke()
        isPreparing = false
        candidates = emptyList()
        displayIndex = 0
        selectedIndex = null
        notice = reason
    }

    /** Explicit user selection; out-of-range indices are refused. */
    fun select(index: Int): Boolean {
        if (index !in candidates.indices) return false
        selectedIndex = index
        return true
    }

    /** Return to browsing the same expression without retaining a selection. */
    fun clearSelection() {
        selectedIndex = null
    }

    /**
     * Integration rebuild: replaces one entry in place (corrected-crop
     * application), keeping the browse position and selection. Unlike
     * [replaceCandidates] this is not a fresh build, so
     * [onCandidatesReplaced] is not fired.
     */
    fun replaceCandidate(index: Int, candidate: CollageCandidate): Boolean {
        if (index !in candidates.indices) return false
        candidates = candidates.toMutableList().also { it[index] = candidate }
        return true
    }

    /** Browsing moves the display cursor and invalidates any explicit selection. */
    fun previous() {
        if (displayIndex > 0) displayIndex -= 1
        selectedIndex = null
    }

    fun next() {
        if (displayIndex < candidates.lastIndex) displayIndex += 1
        selectedIndex = null
    }

    /** Source/note/music edit invalidation: drops candidates and selection. */
    fun clear() {
        onTelemetryInvalidated?.invoke()
        preparationGeneration += 1
        isPreparing = false
        candidates = emptyList()
        displayIndex = 0
        selectedIndex = null
        notice = null
    }
}
