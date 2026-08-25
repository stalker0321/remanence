package app.postmark.memory.ui.create

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Selection state for the Android Photo Picker flow. Enforces the protocol's
 * exactly-3-to-5 rule locally: additions beyond five are rejected, and the
 * create flow may only proceed inside the [3, 5] window.
 *
 * FIX-STATE-06: the selection snapshot is Compose-observable so the count
 * label and the 3..5 publish gate recompose immediately.
 */
class PhotoSelectionState {

    /** Opaque picker item IDs in selection order; no content URIs are stored here. */
    val selectedIds: List<String>
        get() = _selected

    private var _selected by mutableStateOf(emptyList<String>())

    val canProceed: Boolean
        get() = _selected.size in MIN_PHOTOS..MAX_PHOTOS

    val isAtLimit: Boolean
        get() = _selected.size >= MAX_PHOTOS

    sealed interface ToggleResult {
        data class Added(val newSize: Int) : ToggleResult

        data class Removed(val newSize: Int) : ToggleResult

        /** A sixth photo cannot be added; the user must remove one first. */
        data object RejectedAtLimit : ToggleResult
    }

    /** Adds an id if absent; removes it when already present. */
    fun toggle(id: String): ToggleResult {
        val current = _selected
        if (id in current) {
            _selected = current - id
            return ToggleResult.Removed(_selected.size)
        }
        if (current.size >= MAX_PHOTOS) return ToggleResult.RejectedAtLimit
        _selected = current + id
        return ToggleResult.Added(_selected.size)
    }

    fun remove(id: String) {
        _selected = _selected - id
    }

    fun clear() {
        _selected = emptyList()
    }

    companion object {
        const val MIN_PHOTOS: Int = 3
        const val MAX_PHOTOS: Int = 5
    }
}
