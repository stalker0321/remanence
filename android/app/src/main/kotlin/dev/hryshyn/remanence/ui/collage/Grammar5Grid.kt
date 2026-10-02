package dev.hryshyn.remanence.ui.collage

/**
 * Legacy single-branch accessor for grammar-5 ("Editorial Rows").
 *
 * The full frozen branch enumeration now lives in [Grammar5Branches]; this
 * object preserves the accepted `place(...)` API and returns exactly the
 * legacy `editorial-<firstRecipe>-equal-1` branch (recipe 1+2 / 1+1+2 / 1+2+2,
 * equal row heights and widths, the note and text-mode music closing the
 * sheet). It is retained for the focused geometry tests and callers that only
 * need one reference branch; the provider enumerates [Grammar5Branches].
 */
internal object Grammar5Grid {

    fun place(photoCount: Int, noteHeight: Int?, music: Boolean): Grammar5Branches.Grid? {
        val firstRecipe = Grammar5Branches.recipes(photoCount).firstOrNull() ?: return null
        // power=0 (equal widths) ignores the aspect ratio, so unit aspects give
        // the exact legacy geometry.
        val aspects = List(photoCount) { 1.0 }
        val branchId = "editorial-${firstRecipe.joinToString("+")}-equal-1"
        return Grammar5Branches.grids(aspects, noteHeight, music).firstOrNull { it.branchId == branchId }
    }
}
