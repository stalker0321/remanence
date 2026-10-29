package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CollageCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CollageVariantOrderTest {
    private fun candidate(grammar: String, style: String, branch: String = "first"): CollageCandidate {
        val expression = GeneratorExpression.ResolvedExpression(
            canvasVersion = 1, grammarId = grammar, grammarVersion = 1, branchId = branch,
            input = GeneratorExpression.GeneratorInput("owner", 1L, emptyList(), null, null),
            placements = emptyList(), noteTreatment = "regular-region", diagnostics = emptyList(),
            fontVersion = 1, paletteVersion = 1,
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V4,
            style = GeneratorExpression.StyleBinding(style, "style-study-1", ByteArray(32)),
        )
        return CollageCandidate("$grammar-$style-$branch", style, branch, expression)
    }

    private fun choices() = (1..12).map { candidate("g$it", "style-$it") }

    @Test
    fun branchesAndPhotoPermutationsAreOneGrammarStyleChoice() {
        val first = candidate("g5", "press", "first")
        val otherBranch = candidate("g5", "press", "second")
        val otherPhotos = first.copy(candidateId = "same-style-different-photo-order",
            expression = first.expression.copy(placements = listOf(
                GeneratorExpression.Placement("photo-2", 0, 0, 180, 320, null, null),
                GeneratorExpression.Placement("photo-1", 180, 0, 180, 320, null, null),
            )))

        val result = CollageVariantOrder("draft").arrangePrepared(listOf(first, otherBranch, otherPhotos))

        assertEquals(1, result.size)
        assertSame(first, result.single())
    }

    @Test
    fun distinctGrammarOrSealedStyleRemainsASeparateChoice() {
        val candidates = listOf(candidate("g5", "press"), candidate("g5", "poster"),
            candidate("g6", "press"))
        // Display metadata cannot make two identical sealed variants distinct.
        val metadataAlias = candidates.first().copy(candidateId = "alias", styleId = "unsealed-alias")
        val result = CollageVariantOrder("draft").arrangePrepared(candidates + metadataAlias)

        assertEquals(candidates.map { it.variantKey() }.toSet(), result.map { it.variantKey() }.toSet())
        assertEquals(3, result.size)
    }

    @Test
    fun sameDraftKeepsOrderWhenEnumerationOrAvailableSetChanges() {
        val order = CollageVariantOrder("a6a3e1c1-7b0b-42ee-b238-8cb7fe317abc")
        val all = choices()
        val first = order.arrangePrepared(all)
        assertEquals(first, order.arrangePrepared(all.reversed()))
        val remaining = all.filterIndexed { index, _ -> index % 3 != 0 }
        assertEquals(first.filter { it in remaining }, order.arrangePrepared(remaining))
        assertEquals(first, order.arrangePrepared(remaining + all))
    }

    @Test
    fun newDraftGetsNewOrderButReenteringSameDraftPreservesSelectionAndCrop() {
        val session = CollageChoiceSession()
        val input = choices()
        session.beginDraft("83de3184-024e-43fa-8eee-8fc76e1296d2")
        val first = session.arrangePreparedCandidates(input)
        session.replaceCandidates(first)
        session.select(0)
        val cropped = first.first().copy(candidateId = "corrected-crop")
        session.replaceCandidate(0, cropped)
        val generation = session.draftGeneration

        session.beginDraft("83de3184-024e-43fa-8eee-8fc76e1296d2")
        assertEquals(generation, session.draftGeneration)
        assertSame(cropped, session.selected)
        assertEquals(first.drop(1), session.candidates.drop(1))

        session.beginDraft("b3285df5-b71a-413a-a0b7-118c9fbb4e70")
        assertTrue(session.candidates.isEmpty())
        assertFalse(session.hasSelection)
        assertNotEquals(first, session.arrangePreparedCandidates(input))
    }

    @Test
    fun rebuildAndContentInvalidationKeepDraftOrder() {
        val session = CollageChoiceSession()
        session.beginDraft("draft-stable-across-edits")
        val input = choices()
        val first = session.arrangePreparedCandidates(input)
        session.replaceCandidates(first)
        session.clear()
        assertEquals(first, session.arrangePreparedCandidates(input))
        session.beginPreparing()
        assertEquals(first, session.arrangePreparedCandidates(input.reversed()))
        session.showNotice("no fit")
        assertEquals(first, session.arrangePreparedCandidates(input))
    }

    @Test
    fun everyLayoutAppearsOnceBeforeAnyPaletteRepeats() {
        val input = listOf(candidate("g5", "a"), candidate("g5", "b"), candidate("g5", "c"),
            candidate("g17", "a"), candidate("g17", "b"), candidate("g26", "a"), candidate("g26", "b"),
            candidate("g11", "a"))
        val result = CollageVariantOrder("4f0d8f3a-palettes").arrangePrepared(input)

        assertEquals(input.size, result.size)
        val grammars = result.map { it.variantKey().grammarId }
        assertEquals(listOf("g5", "g17", "g26", "g11").sorted(), grammars.take(4).sorted())
        // Second styles follow in the same grammar order as the first pass, then the third.
        val firstPass = grammars.take(4)
        assertEquals(firstPass.filter { it != "g11" }, grammars.subList(4, 7))
        assertEquals(listOf("g5"), grammars.drop(7))
    }

    @Test
    fun coreAndContentFitGrammarsLeadTheChooser() {
        val input = listOf("g11", "g17", "g22", "g24", "g12", "g20", "g18").map { candidate(it, "a") }
        repeat(20) { seed ->
            val result = CollageVariantOrder("seed-$seed").arrangePrepared(input).map { it.variantKey().grammarId }
            assertEquals(setOf("g12", "g20", "g18"), result.take(3).toSet())
        }
    }

    @Test
    fun theFirstStyleShownIsSeededNotAlphabetical() {
        val input = listOf(candidate("g5", "a"), candidate("g5", "b"), candidate("g5", "c"))
        val firstStyles = (0 until 30).map { CollageVariantOrder("draft-$it").arrangePrepared(input).first().variantKey().styleId }.toSet()
        assertEquals(setOf("a", "b", "c"), firstStyles)
    }

    @Test
    fun grammarOrderSurvivesLosingAStyle() {
        val order = CollageVariantOrder("0b5f9d36-edits")
        val input = listOf(candidate("g5", "a"), candidate("g5", "b"), candidate("g23", "a"),
            candidate("g23", "b"), candidate("g27", "a"), candidate("g17", "a"))
        val before = order.arrangePrepared(input).map { it.variantKey().grammarId }.distinct()
        val lost = order.arrangePrepared(input.drop(1)).map { it.variantKey().grammarId }.distinct()
        assertEquals(before, lost)
    }
}
