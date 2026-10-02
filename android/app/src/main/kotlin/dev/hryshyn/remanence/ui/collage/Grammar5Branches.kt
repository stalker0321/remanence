package dev.hryshyn.remanence.ui.collage

import kotlin.math.floor

/**
 * Frozen V5 grammar-5 ("Editorial Rows") **branch enumeration**, ported from the
 * authoritative freeze
 * `remanence_capsule_engine_v5_freeze_2026-09-25` runtime `grammars.js`:
 * `recipes()` (`:42`), `bands()` (`:37-41`), `closing()` (`:43-47`) and the
 * grammar-5 `basePlans` loop (`:51-54`).
 *
 * The frozen engine proposes every `recipes(N) × power{0,1} × weightSet` branch
 * and stops at the **first branch that validates**; a single branch's crop or
 * text failure must not abort the grammar. This object exposes that full branch
 * set so the provider can attempt each one independently.
 *
 * Geometry is computed with the frozen `Double` arithmetic and projected to the
 * Android integer canvas by flooring each rect, i.e. the frozen `Math.floor`
 * values. The `-equal-1` branch is **not** byte-identical to the pre-stage-1
 * integer-division path for the 3-row recipes (n=4/5) with a note: the
 * accumulated fractional row offset can shift the third-row `y` by ≤1 px
 * (`tasks/T151/stage1-erratum.md`, T156 review B1). `noteRegion`/`musicRegion`
 * are unchanged and the frozen floor geometry is authoritative. Provenance/
 * brand/sender marks, decor and non-rect shapes are not representable in the
 * current sealed expression (see `tasks/T151/parity.md` §5) and are deliberately
 * absent here.
 */
internal object Grammar5Branches {

    /** One enumerated branch: cells in source order plus the closing bands. */
    data class Grid(
        val cells: List<CollageRenderer.RectI>,
        val noteRegion: CollageRenderer.RectI?,
        val musicRegion: CollageRenderer.RectI?,
        val branchId: String,
    )

    /** Frozen `recipes(n)` (`grammars.js:42`). */
    fun recipes(photoCount: Int): List<List<Int>> = when (photoCount) {
        3 -> listOf(listOf(1, 2), listOf(2, 1))
        4 -> listOf(listOf(1, 1, 2), listOf(2, 2))
        5 -> listOf(listOf(1, 2, 2), listOf(2, 3), listOf(3, 2))
        else -> emptyList()
    }

    /**
     * Frozen grammar-5 weight sets (`grammars.js:53`), ordered so the all-ones
     * set (the legacy `-equal-1` branch) is first, then the hero-1.3 and
     * hero-0.45 sets; `power` is iterated 0 then 1. The frozen enumeration order
     * is a presentation detail; identity is per branch id.
     */
    fun weightSets(recipe: List<Int>): List<List<Double>> = listOf(
        recipe.map { 1.0 },
        recipe.mapIndexed { index, _ -> if (index == 0) 1.3 else 1.0 },
        recipe.mapIndexed { index, _ -> if (index == 0) 0.45 else 1.0 },
    )

    /**
     * All grammar-5 branches for [aspects] (width/height in source order), the
     * measured [noteHeight] (null = no note) and whether [music] is attached.
     * Returns an empty list when the frozen closing cannot fit the photo field.
     */
    fun grids(aspects: List<Double>, noteHeight: Int?, music: Boolean): List<Grid> {
        val photoCount = aspects.size
        if (recipes(photoCount).isEmpty()) return emptyList()
        val closing = closing(noteHeight, music) ?: return emptyList()
        val area = CollageRenderer.RectI(PHOTO_X, PHOTO_TOP, PHOTO_WIDTH, closing.top - PHOTO_TOP)
        if (area.height <= 0) return emptyList()
        val out = ArrayList<Grid>()
        for (recipe in recipes(photoCount)) {
            for (weights in weightSets(recipe)) {
                for (power in 0..1) {
                    val cells = bands(aspects, recipe, area, power, weights) ?: continue
                    if (cells.size != photoCount) continue
                    val mode = if (power == 1) "proportional" else "equal"
                    out += Grid(
                        cells = cells,
                        noteRegion = closing.noteRegion,
                        musicRegion = closing.musicRegion,
                        branchId = "editorial-${recipe.joinToString("+")}-$mode-${jsNumber(weights.first())}",
                    )
                }
            }
        }
        return out
    }

    /**
     * Frozen `bands(p, recipe, r, {gap, weights, power})` (`grammars.js:37-41`):
     * row heights `(r.h - gap*(rows-1)) * weights[k]/sum`, row widths
     * `(r.w - gap*(count-1)) * ar(p)^power / Σ`, where `ar` is the source
     * width/height clamped to 0.45..2. Floats are floored per rect.
     */
    private fun bands(
        aspects: List<Double>,
        recipe: List<Int>,
        area: CollageRenderer.RectI,
        power: Int,
        weights: List<Double>,
    ): List<CollageRenderer.RectI>? {
        val rows = recipe.size
        val weightSum = weights.sum()
        if (weightSum <= 0.0) return null
        val cells = ArrayList<CollageRenderer.RectI>(aspects.size)
        var position = 0
        var y = area.y.toDouble()
        for (row in recipe.indices) {
            val count = recipe[row]
            if (count <= 0 || position + count > aspects.size) return null
            val group = aspects.subList(position, position + count)
            val height = (area.height - BAND_GAP * (rows - 1)) * weights[row] / weightSum
            if (height <= 0.0) return null
            val widths = group.map { Math.pow(clamp(it, 0.45, 2.0), power.toDouble()) }
            val widthSum = widths.sum()
            if (widthSum <= 0.0) return null
            var x = area.x.toDouble()
            for (column in 0 until count) {
                val width = (area.width - BAND_GAP * (count - 1)) * widths[column] / widthSum
                if (width <= 0.0) return null
                cells += CollageRenderer.RectI(
                    floor(x).toInt(),
                    floor(y).toInt(),
                    floor(width).toInt(),
                    floor(height).toInt(),
                )
                x += width + BAND_GAP
            }
            y += height + BAND_GAP
            position += count
        }
        return cells
    }

    private data class Closing(
        val top: Int,
        val noteRegion: CollageRenderer.RectI?,
        val musicRegion: CollageRenderer.RectI?,
    )

    /** Frozen grammar-5 `closing(d, g, n, 603, 16, 328, 48)` (`grammars.js:43-47`). */
    private fun closing(noteHeight: Int?, music: Boolean): Closing? {
        var top = SHEET_BOTTOM
        val noteRegion = if (noteHeight != null && noteHeight > 0) {
            val noteTop = top - noteHeight
            if (noteTop < PHOTO_TOP + MIN_PHOTO_HEIGHT) return null
            top = noteTop - NOTE_GAP
            CollageRenderer.RectI(CONTENT_X, noteTop, CONTENT_WIDTH, noteHeight)
        } else {
            null
        }
        val musicRegion = if (music) {
            val musicTop = top - MUSIC_HEIGHT
            if (musicTop < PHOTO_TOP + MIN_PHOTO_HEIGHT) return null
            top = musicTop - MUSIC_GAP
            CollageRenderer.RectI(CONTENT_X, musicTop, CONTENT_WIDTH, MUSIC_HEIGHT)
        } else {
            null
        }
        return Closing(top, noteRegion, musicRegion)
    }

    /** Frozen JS number rendering for the branch-id weight token. */
    private fun jsNumber(value: Double): String =
        if (value == floor(value) && !value.isInfinite()) {
            value.toInt().toString()
        } else {
            value.toString()
        }

    private fun clamp(value: Double, min: Double, max: Double): Double =
        Math.max(min, Math.min(max, value))

    private const val SHEET_BOTTOM = 603
    private const val PHOTO_TOP = 101
    private const val PHOTO_X = 10
    private const val PHOTO_WIDTH = 340
    private const val CONTENT_X = 16
    private const val CONTENT_WIDTH = 328
    private const val MUSIC_HEIGHT = 48
    private const val NOTE_GAP = 10
    private const val MUSIC_GAP = 8
    private const val BAND_GAP = 5
    private const val MIN_PHOTO_HEIGHT = 80
}
