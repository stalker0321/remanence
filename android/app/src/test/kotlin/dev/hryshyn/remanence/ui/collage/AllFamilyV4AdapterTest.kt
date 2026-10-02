package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.GeneratorExpression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T151 stage-5 expected-V4 adapter: the producer's plan/placement payload maps
 * to the exact values the T154 V4 `ResolvedExpression` fields will carry
 * (shape, all-nine music mode, brand/sender texts, decor), without a competing
 * sealed schema.
 */
class AllFamilyV4AdapterTest {

    private fun rect(x: Double, y: Double, w: Double, h: Double) = FrozenGrammarPlans.Rect(x, y, w, h)

    private fun plan(): FrozenGrammarPlans.Plan = FrozenGrammarPlans.Plan(
        branch = "spanning-window-218-0.58",
        tiles = listOf(
            FrozenGrammarPlans.Tile("c0", rect(0.0, 0.0, 360.0, 218.0), "round", listOf(1.08, 1.0), null),
            FrozenGrammarPlans.Tile("c1", rect(0.0, 257.0, 205.8, 300.0), "rect", listOf(1.08, 1.0), null),
        ),
        note = FrozenGrammarPlans.Note(rect(14.0, 603.0, 332.0, 24.0), "short"),
        music = FrozenGrammarPlans.Music(rect(12.0, 539.0, 336.0, 49.0), "pulse"),
        provenance = listOf(
            FrozenGrammarPlans.Mark("Remanence", rect(9.0, 221.0, 342.0, 33.0), 30.0, "horizontal", "left", false, 700, "Gothic"),
            FrozenGrammarPlans.Mark("sender", rect(14.0, 616.0, 332.0, 20.0), 17.0, "horizontal", "left", false, 400, "StudySans"),
        ),
        decor = listOf(FrozenGrammarPlans.Decor("acid-ribbon", rect(0.0, 218.0, 360.0, 39.0))),
    )

    private fun candidate(): AllFamilyCandidate {
        val plan = plan()
        return AllFamilyCandidate(
            grammarId = 23,
            branchId = plan.branch,
            assignmentMode = "complete-safe-matching",
            compositionOrder = listOf("c0", "c1"),
            plan = plan,
            verdict = FrozenPlanAdapter.adapt(23, plan),
            placements = listOf(
                MountedPlacement(0, "c0", 0, 0, 360, 218, "round", GeneratorExpression.CropWindow(0, 0, 10_000, 10_000)),
                MountedPlacement(1, "c1", 0, 257, 205, 300, "rect", GeneratorExpression.CropWindow(100, 0, 9_900, 10_000)),
            ),
        )
    }

    @Test
    fun carriesShapeMusicNoteDecorAndProvenanceTexts() {
        val expected = AllFamilyV4Adapter.expected(candidate(), "Alice")
        assertEquals(AllFamilyV4Adapter.EXPRESSION_CONTRACT_V4, expected.expressionContractVersion)
        assertEquals(23, expected.grammarId)
        assertEquals("spanning-window-218-0.58", expected.branchId)

        assertEquals(2, expected.placements.size)
        assertEquals("round", expected.placements[0].shape)
        assertEquals("c0", expected.placements[0].contentId)
        assertEquals("rect", expected.placements[1].shape)

        assertEquals("pulse", expected.musicRegion!!.mode)
        assertEquals(Expected(12, 539, 336, 49), expected.musicRegion!!.region.toExpected())
        assertEquals(Expected(14, 603, 332, 24), expected.noteRegion!!.toExpected())

        val brand = expected.provenance.first { it.kind == "BRAND" }
        val sender = expected.provenance.first { it.kind == "SENDER" }
        assertEquals("Remanence", brand.text)
        assertEquals("From Alice", sender.text)
        assertEquals(700, brand.weight)
        assertEquals("Gothic", brand.font)

        assertEquals(1, expected.decor.size)
        assertEquals("acid-ribbon", expected.decor[0].kind)
        assertEquals(Expected(0, 218, 360, 39), expected.decor[0].region.toExpected())

        assertTrue("non-rect shape must be carried, not dropped", expected.placements.any { it.shape == "round" })
    }

    private data class Expected(val x: Int, val y: Int, val width: Int, val height: Int)

    private fun AllFamilyV4Adapter.ExpectedV4Region.toExpected() = Expected(x, y, width, height)
}
