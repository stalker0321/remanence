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
        if (containsUnpairedSurrogate(candidate)) {
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

        /** Valid surrogate pairs are legitimate supplementary code points; lone ones are malformed. */
        internal fun containsUnpairedSurrogate(value: String): Boolean {
            var index = 0
            while (index < value.length) {
                val high = value[index]
                when {
                    Character.isHighSurrogate(high) -> {
                        if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return true
                        index += 2
                    }
                    Character.isLowSurrogate(high) -> return true
                    else -> index += 1
                }
            }
            return false
        }
    }
}
