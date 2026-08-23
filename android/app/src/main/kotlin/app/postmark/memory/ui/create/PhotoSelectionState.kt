package app.postmark.memory.ui.create

/**
 * Selection state for the Android Photo Picker flow. Enforces the protocol's
 * exactly-3-to-5 rule locally: additions beyond five are rejected, and the
 * create flow may only proceed inside the [3, 5] window.
 */
class PhotoSelectionState {

    /** Opaque picker item IDs in selection order; no content URIs are stored here. */
    val selectedIds: List<String>
        get() = _selected.toList()

    private val _selected = mutableListOf<String>()

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
        if (_selected.remove(id)) return ToggleResult.Removed(_selected.size)
        if (_selected.size >= MAX_PHOTOS) return ToggleResult.RejectedAtLimit
        _selected += id
        return ToggleResult.Added(_selected.size)
    }

    fun remove(id: String) {
        _selected.remove(id)
    }

    fun clear() {
        _selected.clear()
    }

    companion object {
        const val MIN_PHOTOS: Int = 3
        const val MAX_PHOTOS: Int = 5
    }
}
