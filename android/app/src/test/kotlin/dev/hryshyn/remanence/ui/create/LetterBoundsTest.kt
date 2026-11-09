package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.core.model.CapsuleLetter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterBoundsTest {
    @Test
    fun keepsTextThatFits() {
        val text = "Дорогая мама, " + "ж".repeat(100)
        assertEquals(text, LetterBounds.clip(text))
    }

    @Test
    fun cutsAtTheByteBoundOnACharacterBoundary() {
        val clipped = LetterBounds.clip("ж".repeat(5000))
        assertEquals(4000, clipped.length)
        assertTrue(CapsuleLetter.valid(clipped))
        // A surrogate pair is never split.
        val emoji = LetterBounds.clip("a" + "😀".repeat(2500))
        assertEquals(CapsuleLetter.MAX_UTF8_BYTES - 3, emoji.toByteArray(Charsets.UTF_8).size)
        assertTrue(Character.isHighSurrogate(emoji[emoji.length - 2]))
    }
}
