package dev.hryshyn.remanence.ui.create

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.hryshyn.remanence.ui.collage.CollageCandidate

/**
 * Sender collage chooser state. The built candidate list is stable: it only
 * changes through [setCandidates] (an explicit rebuild) or [clear] (an edit
 * invalidation). [previous]/[next] move the selection index without rebuilding,
 * and no candidate becomes publishable until the user calls [select]
 * explicitly.
 */
class CollageChoiceSession {

    var candidates: List<CollageCandidate> by mutableStateOf(emptyList())
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

    /** Typed invalidation/unavailability copy; non-null means no candidates. */
    var notice: String? by mutableStateOf(null)
        private set

    val selected: CollageCandidate?
        get() = selectedIndex?.let(candidates::getOrNull)

    val hasSelection: Boolean
        get() = selected != null

    /** A fresh build always drops any prior selection; never auto-selects. */
    fun replaceCandidates(built: List<CollageCandidate>) {
        candidates = built
        selectedIndex = null
        notice = null
        onCandidatesReplaced?.invoke()
    }

    fun showNotice(reason: String) {
        candidates = emptyList()
        selectedIndex = null
        notice = reason
    }

    /** Explicit user selection; out-of-range indices are refused. */
    fun select(index: Int): Boolean {
        if (index !in candidates.indices) return false
        selectedIndex = index
        return true
    }

    fun previous() {
        selectedIndex = selectedIndex?.let { if (it > 0) it - 1 else it }
    }

    fun next() {
        selectedIndex = selectedIndex?.let { if (it < candidates.lastIndex) it + 1 else it }
    }

    /** Source/note/music edit invalidation: drops candidates and selection. */
    fun clear() {
        candidates = emptyList()
        selectedIndex = null
        notice = null
    }
}
