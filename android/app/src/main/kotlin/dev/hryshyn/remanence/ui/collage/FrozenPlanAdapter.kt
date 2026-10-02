package dev.hryshyn.remanence.ui.collage

import kotlin.math.floor

/**
 * T151 stage 2 — adapter from the local [FrozenGrammarPlans] algorithm DTOs to
 * the geometry the current sealed expression can carry.
 *
 * The frozen 14-family plans are ported as pure geometry in
 * [FrozenGrammarPlans]. This adapter projects one such branch into the same
 * integer-canvas cell + closing shape the accepted [Grammar5Branches] provider
 * path already consumes, and **classifies** what the sealed V3 expression cannot
 * carry instead of dropping it silently. It edits no schema, renderer,
 * StyleRegistry or UI: the remaining family/style seam is named, not faked.
 *
 * Two kinds of gap are distinguished (both typed):
 *  - [Unrepresentable.omittedLayers] — V4 layers the V3 path already drops by
 *    design (`brand`/sender provenance, decor). The geometry is still sound, so
 *    the branch is [Verdict.Representable] with the omissions disclosed.
 *  - [Unrepresentable.blocking] — features that would render **wrongly** if
 *    offered as V3 (`round`/`ellipse` shapes, music modes outside
 *    `{text, thumbnail}`). The branch is [Verdict.Blocked] until T154's V4 seam
 *    lands.
 *
 * Frozen rules: V3 placements are rectangles only; `provenance`/`decor` are V4
 * additions (`tasks/T154/interface.md`); `MusicRegion.mode` is
 * `{text, thumbnail}` in V3.
 */
internal object FrozenPlanAdapter {

    /** A frozen feature the sealed V3 expression cannot fully carry (named seam). */
    enum class Unrepresentable {
        /** `shape` is not `rect` (`round`/`ellipse` need a V4 shape slot). */
        NON_RECT_SHAPE,

        /** `plan.decor` needs the V4 decor slot + painter. */
        DECOR,

        /** `plan.provenance` needs the V4 provenance slot (brand/sender marks). */
        PROVENANCE,

        /** Music mode is outside V3 `{text, thumbnail}` (inline/tile/sleeve/spine/pulse). */
        MUSIC_MODE,
    }

    /** Integer-canvas projection of one branch, in the provider's shape. */
    data class Projection(
        val grammarId: Int,
        val branchId: String,
        val cells: List<CollageRenderer.RectI>,
        val noteRegion: CollageRenderer.RectI?,
        val musicMode: String?,
        val musicRegion: CollageRenderer.RectI?,
        /** V4 layers the V3 path already drops (disclosed, non-blocking). */
        val omittedLayers: List<Unrepresentable>,
        /** Features that would render wrongly as V3 (must gate the branch). */
        val blocking: List<Unrepresentable>,
    )

    sealed interface Verdict {
        /** Geometry is sound for V3; [Projection.omittedLayers] names dropped V4 layers. */
        data class Representable(val projection: Projection) : Verdict

        /** Cannot be offered as V3 without misrendering; [Projection.blocking] is why. */
        data class Blocked(val projection: Projection) : Verdict
    }

    /** Branch-level coverage of the V3-representable subset across a corpus. */
    data class Coverage(
        val total: Int,
        val representable: Int,
        val blocked: Int,
        val byBlockingReason: Map<Unrepresentable, Int>,
    )

    /** Music modes the sealed V3 expression already carries. */
    private val V3_MUSIC_MODES = setOf("text", "thumbnail")

    /**
     * Project one frozen plan. Cells preserve frozen source/tile order and are
     * floored to the Android integer canvas, matching [Grammar5Branches]. This
     * never rejects: it always yields geometry plus a typed [Verdict].
     */
    fun adapt(grammarId: Int, plan: FrozenGrammarPlans.Plan): Verdict {
        val blocking = ArrayList<Unrepresentable>(2)
        if (plan.tiles.any { it.shape != "rect" }) blocking += Unrepresentable.NON_RECT_SHAPE
        val music = plan.music
        if (music != null && music.mode !in V3_MUSIC_MODES) blocking += Unrepresentable.MUSIC_MODE

        val omitted = ArrayList<Unrepresentable>(2)
        if (plan.decor.isNotEmpty()) omitted += Unrepresentable.DECOR
        if (plan.provenance.isNotEmpty()) omitted += Unrepresentable.PROVENANCE

        val projection = Projection(
            grammarId = grammarId,
            branchId = plan.branch,
            cells = plan.tiles.map { rect(it.rect) },
            noteRegion = plan.note?.let { rect(it.rect) },
            musicMode = music?.mode,
            musicRegion = music?.let { rect(it.rect) },
            omittedLayers = omitted,
            blocking = blocking,
        )
        return if (blocking.isEmpty()) Verdict.Representable(projection) else Verdict.Blocked(projection)
    }

    /** Aggregate V3 coverage over `(grammarId, plan)` pairs, in frozen order. */
    fun coverage(branches: List<Pair<Int, FrozenGrammarPlans.Plan>>): Coverage {
        var representable = 0
        var blocked = 0
        val byReason = LinkedHashMap<Unrepresentable, Int>()
        for ((grammarId, plan) in branches) {
            val verdict = adapt(grammarId, plan)
            if (verdict is Verdict.Blocked) {
                blocked++
                for (reason in verdict.projection.blocking) {
                    byReason[reason] = (byReason[reason] ?: 0) + 1
                }
            } else {
                representable++
            }
        }
        return Coverage(branches.size, representable, blocked, byReason)
    }

    /** Frozen float rect → Android integer canvas (floor, as the provider does). */
    fun rect(r: FrozenGrammarPlans.Rect): CollageRenderer.RectI =
        CollageRenderer.RectI(
            floor(r.x).toInt(),
            floor(r.y).toInt(),
            floor(r.w).toInt(),
            floor(r.h).toInt(),
        )
}
