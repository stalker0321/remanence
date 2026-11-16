package dev.hryshyn.remanence.ui.create

/**
 * One text field, two sides (ADR-021 §2); the site's `splitLetter` rule for
 * rule. A short text is the note. A longer one goes whole to the back; the
 * front takes whole sentences from its start up to [FRONT_LEAD_CHARS], or
 * words with an ellipsis when the first sentence is longer than that.
 */
internal object LetterSplit {

    /** [front] is what the layout prints (the sealed note); [letter] the whole text for the back, or null. */
    data class Sides(val front: String, val letter: String?)

    /** A text this short, in at most this many lines, stays whole on the front. */
    const val FRONT_WHOLE_CHARS = 240
    private const val FRONT_WHOLE_LINES = 4
    /** The front's share of a longer text. */
    const val FRONT_LEAD_CHARS = 160

    private val SENTENCE = Regex("(?U)[^.!?…]+(?:[.!?…]+[\"»”)]*|$)\\s*")
    private val TRAILING = Regex("(?U)[\\s,;:—–-]+$")

    fun split(text: String): Sides {
        val whole = text.trim()
        if (whole.isEmpty()) return Sides("", null)
        if (chars(whole) <= FRONT_WHOLE_CHARS && whole.split('\n').size <= FRONT_WHOLE_LINES &&
            NoteEditorState.utf8ByteCount(whole) <= NoteEditorState.MAX_NOTE_BYTES
        ) {
            return Sides(whole, null)
        }
        return Sides(lead(whole), whole)
    }

    private fun chars(text: String): Int = text.codePointCount(0, text.length)

    private fun lead(whole: String): String {
        // The first paragraph is the most the front takes.
        val paragraph = whole.split('\n').first().trim()
        val sentences = SENTENCE.findAll(paragraph).map { it.value }.toList().ifEmpty { listOf(paragraph) }
        var out = ""
        for (sentence in sentences) {
            if (chars((out + sentence).trimEnd()) > FRONT_LEAD_CHARS) break
            out += sentence
        }
        out = out.trimEnd()
        if (out.isNotEmpty()) return out
        // The first sentence alone is too long: whole words, then an ellipsis.
        for (word in paragraph.split(Regex("(?U)\\s+"))) {
            val next = if (out.isEmpty()) word else "$out $word"
            if (chars(next) > FRONT_LEAD_CHARS - 1) break
            out = next
        }
        if (out.isEmpty()) out = paragraph.substring(0, paragraph.offsetByCodePoints(0, FRONT_LEAD_CHARS - 1))
        return out.replace(TRAILING, "") + "…"
    }
}
