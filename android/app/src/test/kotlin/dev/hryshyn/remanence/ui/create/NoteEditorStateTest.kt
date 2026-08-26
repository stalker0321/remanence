package dev.hryshyn.remanence.ui.create

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEditorStateTest {

    @Test
    fun emptyNoteIsOptionalAndValid() {
        val state = NoteEditorState()
        assertTrue(state.isEmpty)
        assertTrue(state.canIncludeInCapsule)
    }

    @Test
    fun asciiBoundaryAt1000BytesAccepted() {
        val state = NoteEditorState()
        assertTrue(state.onChange("a".repeat(1000)))
        assertEquals(1000, NoteEditorState.utf8ByteCount(state.text))
        assertTrue(state.canIncludeInCapsule)
        assertFalse(state.limitReached)
    }

    @Test
    fun asciiOverLimitRejectedWithoutTruncation() {
        val state = NoteEditorState()
        assertTrue(state.onChange("a".repeat(999)))
        assertFalse(state.onChange("a".repeat(1001)))
        // Previous valid text is preserved; nothing was silently cut.
        assertEquals(999, NoteEditorState.utf8ByteCount(state.text))
        assertTrue(state.limitReached)
    }

    @Test
    fun fourByteEmojiCountsAsFourCodePointsWorth() {
        val state = NoteEditorState()
        // 250 emoji × 4 UTF-8 bytes = 1000 bytes exactly.
        val emojis = "😀".repeat(250)
        assertTrue(state.onChange(emojis))
        assertEquals(1000, NoteEditorState.utf8ByteCount(state.text))

        // One more 4-byte emoji would exceed: rejected whole.
        assertFalse(state.onChange(emojis + "😀"))
        assertEquals(emojis, state.text)
        assertTrue(state.limitReached)
    }

    @Test
    fun twoByteCharsFillExactlyToBoundary() {
        val state = NoteEditorState()
        // 500 two-byte cyrillic chars = 1000 bytes.
        assertTrue(state.onChange("б".repeat(500)))
        assertTrue(state.canIncludeInCapsule)

        val other = NoteEditorState()
        // 1000 bytes + one more ASCII char = 1001 → rejected whole.
        assertFalse(other.onChange("б".repeat(500) + "x"))
        assertEquals("", other.text)
    }

    @Test
    fun malformedSurrogateInputRejected() {
        val state = NoteEditorState()
        val loneSurrogate = String(Character.toChars(0xD83D)) // high surrogate alone
        assertFalse(state.onChange("ok" + loneSurrogate))
        assertEquals("", state.text)
    }
}
