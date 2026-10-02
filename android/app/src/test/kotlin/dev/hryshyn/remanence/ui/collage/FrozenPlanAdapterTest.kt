package dev.hryshyn.remanence.ui.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T151 stage-2 adapter tests: the all-family [FrozenGrammarPlans] DTOs project
 * onto the existing provider-shaped integer geometry, and every feature the
 * sealed V3 expression cannot carry is classified (not silently dropped).
 */
class FrozenPlanAdapterTest {

    private val stub = FrozenGrammarPlans.LINE_HEIGHT_STUB

    private fun input(
        photos: Int,
        aspects: List<Double> = List(photos) { 1.0 },
        note: String? = null,
        artwork: Boolean? = null,
        faces: Boolean = false,
    ): FrozenGrammarPlans.Input = FrozenGrammarPlans.Input(
        id = "adapter",
        sender = "T151",
        photos = aspects.mapIndexed { i, a ->
            FrozenGrammarPlans.Photo("p$i", a * 1000.0, 1000.0, faces)
        },
        note = note,
        music = artwork?.let { FrozenGrammarPlans.MusicInput(it) },
    )

    private fun plans(d: FrozenGrammarPlans.Input, gid: Int): List<FrozenGrammarPlans.Plan> {
        val g = FrozenGrammarPlans.def(gid)
        return FrozenGrammarPlans.plans(d, g, FrozenGrammarPlans.noteState(d, g, stub), stub)
    }

    @Test
    fun grammar5RectBranchProjectsToProviderGridAndDisclosesProvenance() {
        val d = input(photos = 3)
        val plan = requireNotNull(plans(d, 5).firstOrNull { it.branch == "editorial-1+2-equal-1" }) {
            "missing frozen editorial-1+2-equal-1 branch"
        }
        val verdict = FrozenPlanAdapter.adapt(5, plan)
        assertTrue("g5 rect branch must be representable", verdict is FrozenPlanAdapter.Verdict.Representable)
        val projection = (verdict as FrozenPlanAdapter.Verdict.Representable).projection

        val legacy = requireNotNull(Grammar5Grid.place(3, null, false)) { "missing legacy grid" }
        assertEquals("cells must match the accepted provider grid", legacy.cells, projection.cells)
        assertEquals(null, projection.noteRegion)
        assertEquals(null, projection.musicMode)
        assertEquals(null, projection.musicRegion)
        assertEquals(
            listOf(FrozenPlanAdapter.Unrepresentable.PROVENANCE),
            projection.omittedLayers,
        )
        assertTrue("no blocking feature", projection.blocking.isEmpty())
    }

    @Test
    fun grammar5ThumbnailMusicStaysRepresentable() {
        val d = input(photos = 3, artwork = true)
        val plan = requireNotNull(plans(d, 5).firstOrNull()) { "missing g5 music branch" }
        val verdict = FrozenPlanAdapter.adapt(5, plan)
        assertTrue(verdict is FrozenPlanAdapter.Verdict.Representable)
        val projection = (verdict as FrozenPlanAdapter.Verdict.Representable).projection
        assertEquals("thumbnail", projection.musicMode)
        assertTrue("rect tiles only", projection.cells.isNotEmpty())
    }

    @Test
    fun nonRectShapeBlocksInterlockUntilV4ShapeSlot() {
        val d = input(photos = 3, artwork = false)
        val plan = requireNotNull(plans(d, 11).firstOrNull()) { "missing g11 interlock branch" }
        val verdict = FrozenPlanAdapter.adapt(11, plan)
        assertTrue("g11 uses round modules", verdict is FrozenPlanAdapter.Verdict.Blocked)
        val projection = (verdict as FrozenPlanAdapter.Verdict.Blocked).projection
        assertTrue(
            projection.blocking.contains(FrozenPlanAdapter.Unrepresentable.NON_RECT_SHAPE),
        )
        assertTrue("geometry is still projected, not dropped", projection.cells.isNotEmpty())
        assertTrue(
            projection.omittedLayers.contains(FrozenPlanAdapter.Unrepresentable.PROVENANCE),
        )
    }

    @Test
    fun sleeveMusicModeBlocksUntilV4MusicModes() {
        val d = input(photos = 3, artwork = true)
        val plan = requireNotNull(plans(d, 20).firstOrNull()) { "missing g20 sleeve-bridge branch" }
        val verdict = FrozenPlanAdapter.adapt(20, plan)
        assertTrue("g20 sleeve music is beyond V3", verdict is FrozenPlanAdapter.Verdict.Blocked)
        val projection = (verdict as FrozenPlanAdapter.Verdict.Blocked).projection
        assertEquals("sleeve", projection.musicMode)
        assertTrue(projection.blocking.contains(FrozenPlanAdapter.Unrepresentable.MUSIC_MODE))
    }

    @Test
    fun coverageSeparatesRepresentableFromBlocked() {
        val corpus = ArrayList<Pair<Int, FrozenGrammarPlans.Plan>>()
        val plain = input(photos = 3)
        val music = input(photos = 3, artwork = true)
        for (gid in FrozenGrammarPlans.ACTIVE_GRAMMAR_IDS) {
            val d = if (FrozenGrammarPlans.def(gid).music == "required") music else plain
            val g = FrozenGrammarPlans.def(gid)
            if (!FrozenGrammarPlans.applicability(d, g).ok) continue
            for (plan in FrozenGrammarPlans.plans(d, g, FrozenGrammarPlans.noteState(d, g, stub), stub)) {
                corpus += gid to plan
            }
        }
        val coverage = FrozenPlanAdapter.coverage(corpus)
        assertTrue("corpus must be substantial, got ${coverage.total}", coverage.total > 100)
        assertTrue("some families are V3-representable", coverage.representable > 0)
        assertTrue("some families are blocked on the V4 seam", coverage.blocked > 0)
        assertEquals(
            "blocked + representable must cover every branch",
            coverage.total,
            coverage.representable + coverage.blocked,
        )
        assertTrue(
            coverage.byBlockingReason.keys.containsAll(
                setOf(
                    FrozenPlanAdapter.Unrepresentable.NON_RECT_SHAPE,
                    FrozenPlanAdapter.Unrepresentable.MUSIC_MODE,
                ),
            ),
        )
    }
}
