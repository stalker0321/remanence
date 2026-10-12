package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.GeneratorExpression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T151 stage-7 — the actual plan → sealed V4 `ResolvedExpression` adapter.
 *
 * Consumes the accepted T154 V4 schema and checks the sealed bytes-level model:
 * authored-order placements (preserving the complete-safe mapping), shape,
 * textual provenance with the sealed line rule, decor, nine-mode music, and
 * `validateResolved` acceptance; plus typed rejection when the mapping or
 * artwork gate cannot be satisfied.
 */
class AllFamilyV4AdapterTest {

    private fun rect(x: Double, y: Double, w: Double, h: Double) = FrozenGrammarPlans.Rect(x, y, w, h)

    private fun plan(
        musicMode: String = "pulse",
        brandSize: Double = 30.0,
    ): FrozenGrammarPlans.Plan = FrozenGrammarPlans.Plan(
        branch = "spanning-window-218-0.58",
        tiles = listOf(
            FrozenGrammarPlans.Tile("c0", rect(0.0, 0.0, 360.0, 218.0), "round", listOf(1.08, 1.0), null),
            FrozenGrammarPlans.Tile("c1", rect(0.0, 257.0, 205.8, 300.0), "rect", listOf(1.08, 1.0), null),
            FrozenGrammarPlans.Tile("c2", rect(210.8, 257.0, 149.2, 300.0), "rect", listOf(1.08, 1.0), null),
        ),
        note = FrozenGrammarPlans.Note(rect(14.0, 603.0, 332.0, 24.0), "short"),
        music = FrozenGrammarPlans.Music(rect(12.0, 539.0, 336.0, 49.0), musicMode),
        provenance = listOf(
            FrozenGrammarPlans.Mark("Remanence", rect(9.0, 221.0, 342.0, 33.0), brandSize, "horizontal", "left", false, 700, "Gothic"),
            FrozenGrammarPlans.Mark("sender", rect(14.0, 616.0, 332.0, 20.0), 17.0, "horizontal", "left", false, 400, "StudySans"),
        ),
        decor = listOf(FrozenGrammarPlans.Decor("acid-ribbon", rect(0.0, 218.0, 360.0, 39.0))),
    )

    /** Complete-safe mapping: composition order is a permutation of authored order. */
    private fun candidate(
        plan: FrozenGrammarPlans.Plan = plan(),
        grammarId: Int = 23,
    ): AllFamilyCandidate = AllFamilyCandidate(
        grammarId = grammarId,
        branchId = plan.branch,
        assignmentMode = "complete-safe-matching",
        compositionOrder = listOf("c2", "c0", "c1"),
        plan = plan,
        verdict = FrozenPlanAdapter.adapt(grammarId, plan),
        placements = listOf(
            MountedPlacement(0, "c2", 0, 0, 360, 218, "round", GeneratorExpression.CropWindow(0, 0, 10_000, 10_000)),
            MountedPlacement(1, "c0", 0, 257, 205, 300, "rect", GeneratorExpression.CropWindow(100, 0, 9_900, 10_000)),
            MountedPlacement(2, "c1", 210, 257, 149, 300, "rect", GeneratorExpression.CropWindow(0, 50, 10_000, 9_950)),
        ),
    )

    private fun input(): GeneratorExpression.GeneratorInput = GeneratorExpression.GeneratorInput(
        ownerId = "owner",
        epoch = 1L,
        photos = listOf(
            GeneratorExpression.PhotoRef("c0", 0, 360, 240, "a".repeat(64)),
            GeneratorExpression.PhotoRef("c1", 1, 205, 300, "b".repeat(64)),
            GeneratorExpression.PhotoRef("c2", 2, 149, 300, "c".repeat(64)),
        ),
        note = "a note",
        music = GeneratorExpression.MusicRef("track", null),
    )

    private fun style(grammarId: Int): StyleRegistry.Style =
        requireNotNull(AllFamilyV4Adapter.styleForGrammar(grammarId)) { "no bundled style for g$grammarId" }

    @Test
    fun sealsActualV4ExpressionPreservingCompleteSafeMapping() {
        val outcome = AllFamilyV4Adapter.seal(
            candidate = candidate(),
            input = input(),
            senderHandle = "Alice",
            style = style(23),
        )
        assertTrue("expected a sealed expression, got $outcome", outcome is AllFamilyV4Adapter.Outcome.Sealed)
        val expression = (outcome as AllFamilyV4Adapter.Outcome.Sealed).expression

        assertEquals(GeneratorExpression.EXPRESSION_CONTRACT_V4, expression.expressionContractVersion)
        assertEquals("g23", expression.grammarId)
        assertTrue(
            "sealed expression must pass validateResolved",
            GeneratorExpression.validateResolved(expression) is GeneratorExpression.InputValidation.Valid,
        )
        // Authored order is preserved even though the assignment is a permutation.
        assertEquals(listOf("c0", "c1", "c2"), expression.placements.map { it.contentId })
        // c2 was matched to the round-shaped slot; c0/c1 to rect slots.
        assertEquals(GeneratorExpression.PhotoShape.RECT, expression.placements[0].shape)
        assertEquals(GeneratorExpression.PhotoShape.RECT, expression.placements[1].shape)
        assertEquals(GeneratorExpression.PhotoShape.ROUND, expression.placements[2].shape)

        val provenance = requireNotNull(expression.provenance)
        assertEquals(GeneratorExpression.BRAND_TEXT, provenance.first { it.kind == GeneratorExpression.ProvenanceKind.BRAND }.text)
        assertEquals("From Alice", provenance.first { it.kind == GeneratorExpression.ProvenanceKind.SENDER }.text)
        assertEquals(1, requireNotNull(expression.decor).size)
        assertEquals(GeneratorExpression.DecorKind.ACID_RIBBON, expression.decor!![0].kind)
        assertEquals("pulse", requireNotNull(expression.musicRegion).mode)
        assertEquals("glacier", expression.style!!.styleId)
    }

    @Test
    fun editorialBrandUsesTheSealedLineYield() {
        val outcome = AllFamilyV4Adapter.seal(
            candidate = candidate(plan(brandSize = 64.0), grammarId = 5),
            input = input(),
            senderHandle = "Alice",
            style = style(5),
        )
        val expression = (outcome as AllFamilyV4Adapter.Outcome.Sealed).expression
        val brand = requireNotNull(expression.provenance).first { it.kind == GeneratorExpression.ProvenanceKind.BRAND }
        assertEquals(64, brand.size)
        assertEquals(70, brand.line)
        assertTrue(GeneratorExpression.validateResolved(expression) is GeneratorExpression.InputValidation.Valid)
    }

    @Test
    fun rejectsWhenAnAuthoredPhotoIsNotPlaced() {
        val partial = candidate().copy(placements = candidate().placements.take(2))
        val outcome = AllFamilyV4Adapter.seal(partial, input(), "Alice", style(23))
        assertTrue(outcome is AllFamilyV4Adapter.Outcome.Rejected)
        assertTrue(
            (outcome as AllFamilyV4Adapter.Outcome.Rejected).reasons.any { it.contains("c1") },
        )
    }

    @Test
    fun rejectsCoverModeWithoutVerifiedArtwork() {
        val outcome = AllFamilyV4Adapter.seal(
            candidate = candidate(plan(musicMode = "thumbnail")),
            input = input(),
            senderHandle = "Alice",
            style = style(23),
            artwork = null,
        )
        assertTrue("thumbnail without artwork must not seal", outcome is AllFamilyV4Adapter.Outcome.Rejected)
    }

    @Test
    fun styleForGrammarIsBundledAndFailClosed() {
        assertEquals(23, style(23).grammar)
        assertNull(AllFamilyV4Adapter.styleForGrammar(99))
    }

    @Test
    fun allAuthoredStylesAreEnumeratedOnceForTheirOwnGrammar() {
        val styles = FrozenGrammarPlans.ACTIVE_GRAMMAR_IDS.flatMap(AllFamilyV4Adapter::stylesForGrammar)
        assertEquals(StyleRegistry.ids, styles.map { it.id }.toSet())
        assertEquals(StyleRegistry.ids.size, styles.size)
        assertTrue(AllFamilyV4Adapter.stylesForGrammar(5).size > 1)
        assertTrue(AllFamilyV4Adapter.stylesForGrammar(23).size > 1)
        assertTrue(AllFamilyV4Adapter.stylesForGrammar(99).isEmpty())
    }

    @Test
    fun paletteVariantsSealDistinctExpressionsWithoutChangingPhotoGeometry() {
        val expressions = AllFamilyV4Adapter.stylesForGrammar(23).map { style ->
            (AllFamilyV4Adapter.seal(candidate(), input(), "Alice", style) as AllFamilyV4Adapter.Outcome.Sealed).expression
        }
        assertTrue(expressions.size > 1)
        assertEquals(expressions.size, expressions.map { it.style!!.styleId }.toSet().size)
        assertEquals(1, expressions.map { it.placements }.toSet().size)
        assertTrue(AllFamilyV4Adapter.seal(candidate(), input(), "Alice", style(5)) is AllFamilyV4Adapter.Outcome.Rejected)
    }
}
