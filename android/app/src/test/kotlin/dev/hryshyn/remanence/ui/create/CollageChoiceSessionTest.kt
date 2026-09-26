package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CollageCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chooser state: list stability, explicit-only selection, prev/next without
 * rebuild, and invalidation on edit.
 */
class CollageChoiceSessionTest {

    private fun candidate(id: String) = CollageCandidate(
        candidateId = id,
        styleId = id,
        branchId = "editorial-1+2-equal",
        expression = GeneratorExpression.ResolvedExpression(
            canvasVersion = 1,
            grammarId = "remanence-editorial-rows",
            grammarVersion = 1,
            branchId = "editorial-1+2-equal",
            input = GeneratorExpression.GeneratorInput(
                ownerId = "owner-1", epoch = 1L,
                photos = listOf(
                    GeneratorExpression.PhotoRef("p0", 0, 1000, 1000, "a".repeat(64)),
                ),
                note = null, music = null,
            ),
            placements = emptyList(),
            noteTreatment = "regular-region",
            diagnostics = emptyList(),
            fontVersion = 1,
            paletteVersion = 1,
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V3,
        ),
    )

    private fun built() = listOf(candidate("press"), candidate("blue-letter"), candidate("poster"))

    @Test
    fun setCandidatesRequiresExplicitSelection() {
        val session = CollageChoiceSession()
        session.replaceCandidates(built())
        assertNull(session.selectedIndex)
        assertFalse(session.hasSelection)

        assertTrue(session.select(1))
        assertEquals("blue-letter", session.selected?.styleId)
        assertFalse(session.select(-1))
        assertFalse(session.select(99))
        assertEquals("blue-letter", session.selected?.styleId)
    }

    @Test
    fun previousAndNextMoveIndexWithoutRebuilding() {
        val session = CollageChoiceSession()
        val list = built()
        session.replaceCandidates(list)
        session.select(0)

        session.previous()
        assertEquals(0, session.selectedIndex)
        session.next()
        assertEquals(1, session.selectedIndex)
        session.next()
        assertEquals(2, session.selectedIndex)
        session.next()
        assertEquals(2, session.selectedIndex)
        assertEquals(list, session.candidates)
    }

    @Test
    fun clearDropsCandidatesAndSelection() {
        val session = CollageChoiceSession()
        session.replaceCandidates(built())
        session.select(2)
        session.clear()
        assertTrue(session.candidates.isEmpty())
        assertNull(session.selectedIndex)
        assertNull(session.notice)
    }

    @Test
    fun noticeReplacesCandidatesAndSelection() {
        val session = CollageChoiceSession()
        session.replaceCandidates(built())
        session.select(1)
        session.showNotice("authoritative grammar-5 crop analysis is not available on this build")
        assertTrue(session.candidates.isEmpty())
        assertNull(session.selectedIndex)
        assertTrue(session.notice!!.contains("not available"))
    }

    @Test
    fun freshBuildNeverAutoSelects() {
        val session = CollageChoiceSession()
        session.replaceCandidates(built())
        session.select(0)
        session.replaceCandidates(built())
        assertNull(session.selectedIndex)
    }
}
