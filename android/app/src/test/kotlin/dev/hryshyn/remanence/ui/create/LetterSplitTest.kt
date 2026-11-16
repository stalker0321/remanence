package dev.hryshyn.remanence.ui.create

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as the site's `letter-split.test.ts`: both sides split one text alike. */
class LetterSplitTest {

    @Test
    fun keepsAShortTextOnTheFrontAlone() {
        assertEquals(LetterSplit.Sides("с днём рождения, мам!", null), LetterSplit.split("  с днём рождения, мам!  "))
        assertEquals(LetterSplit.Sides("", null), LetterSplit.split("   "))
    }

    @Test
    fun putsALongTextWholeOnTheBackAndItsFirstSentencesOnTheFront() {
        val text = "Мам, привет. Мы наконец доехали до моря и я думал о тебе весь день. " + "Здесь очень тихо, и вода тёплая. ".repeat(12)
        val split = LetterSplit.split(text)
        assertEquals(text.trim(), split.letter)
        assertEquals(
            "Мам, привет. Мы наконец доехали до моря и я думал о тебе весь день. Здесь очень тихо, и вода тёплая. Здесь очень тихо, и вода тёплая.",
            split.front,
        )
    }

    @Test
    fun cutsAFirstSentenceLongerThanTheFrontAtAWordWithAnEllipsis() {
        val split = LetterSplit.split("слово ".repeat(60).trim())
        assertTrue(split.front.endsWith("слово…"))
        assertTrue(split.front.codePointCount(0, split.front.length) <= LetterSplit.FRONT_LEAD_CHARS)
    }

    @Test
    fun takesTheFrontOnlyFromTheFirstParagraph() {
        assertEquals("Привет!", LetterSplit.split("Привет!\n" + "второй абзац. ".repeat(30)).front)
    }

    @Test
    fun sendsATextOfManyShortLinesToTheBack() {
        assertEquals("a\nb\nc\nd\ne", LetterSplit.split("a\nb\nc\nd\ne").letter)
    }
}
