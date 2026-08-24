package app.postmark.memory.ui.create

/**
 * Optional capsule note with the v1 limit of 1000 UTF-8 bytes. Input that
 * does not fit is REJECTED whole — the state never silently truncates, and
 * malformed surrogate input is refused outright.
 */
class NoteEditorState(private val maxBytes: Int = MAX_NOTE_BYTES) {

    var text: String = ""
        private set

    /** True after the most recent [onChange] was rejected for size. */
    var limitReached: Boolean = false
        private set

    val isEmpty: Boolean get() = text.isEmpty()

    val canIncludeInCapsule: Boolean get() = utf8ByteCount(text) <= maxBytes

    /** @return true when the candidate was accepted as the new note text. */
    fun onChange(candidate: String): Boolean {
        if (candidate.any { Character.isSurrogate(it) }) {
            limitReached = false
            return false
        }
        if (utf8ByteCount(candidate) > maxBytes) {
            limitReached = true
            return false
        }
        text = candidate
        limitReached = false
        return true
    }

    companion object {
        const val MAX_NOTE_BYTES: Int = 1000

        fun utf8ByteCount(value: String): Int = value.toByteArray(Charsets.UTF_8).size
    }
}
