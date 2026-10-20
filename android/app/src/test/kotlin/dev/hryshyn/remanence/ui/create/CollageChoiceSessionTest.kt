package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CollageCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chooser state: display cursor visible without freezing, explicit-only
 * selection, prev/next move the cursor and invalidate selection without
 * rebuilding, and invalidation on edit.
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
    fun firstCandidateIsDisplayedWithoutFreeze() {
        val session = CollageChoiceSession()
        session.replaceCandidates(built())
        assertEquals(0, session.displayIndex)
        assertEquals("press", session.displayed?.styleId)
        assertNull(session.selectedIndex)
        assertFalse(session.hasSelection)
    }

    @Test
    fun previousAndNextMoveDisplayWithoutRebuilding() {
        val session = CollageChoiceSession()
        val list = built()
        session.replaceCandidates(list)
        session.select(0)

        session.previous()
        assertEquals(0, session.displayIndex)
        assertNull(session.selectedIndex)
        session.next()
        assertEquals(1, session.displayIndex)
        assertNull(session.selectedIndex)
        session.next()
        assertEquals(2, session.displayIndex)
        session.next()
        assertEquals(2, session.displayIndex)
        assertEquals(list, session.candidates)
    }

    @Test
    fun browseInvalidatesExplicitSelection() {
        val session = CollageChoiceSession()
        session.replaceCandidates(built())
        session.select(1)
        assertEquals("blue-letter", session.selected?.styleId)
        session.next()
        assertNull(session.selectedIndex)
        assertFalse(session.hasSelection)
        assertEquals(1, session.displayIndex)
        assertEquals("blue-letter", session.displayed?.styleId)
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
    fun retiredHostCannotInvalidateItsReplacementAndClearRetiresPreparation() {
        val session = CollageChoiceSession()
        var invalidations = 0
        session.onReadinessInvalidated = { invalidations++ }
        val first = Any()
        val second = Any()
        session.attachHost(first)
        val retiredPreparation = session.beginPreparing()
        session.replaceCandidates(built())
        session.select(1)
        session.attachHost(second)
        assertFalse(session.isCurrentPreparation(retiredPreparation))
        val beforeOldDispose = invalidations
        session.detachHost(first)
        assertEquals(beforeOldDispose, invalidations)
        assertTrue(session.isCurrentHost(second))
        assertEquals(1, session.selectedIndex)
        val livePreparation = session.beginPreparing(retainCandidates = true)
        session.clear()
        assertFalse(session.isCurrentPreparation(livePreparation))
        assertTrue(session.candidates.isEmpty())
        assertNull(session.selectedIndex)
        session.detachHost(second)
        assertFalse(session.isCurrentHost(second))
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
