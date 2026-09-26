package dev.hryshyn.remanence.ui.collage

/**
 * Minimal deterministic grammar-5 ("Editorial Rows") grid for the text-only
 * subset: hero-first justified rows (1+2 / 1+1+2 / 1+2+2) with the note and
 * the text-mode music closing the sheet, on the 360x640 reference canvas in
 * integer units. Provenance/brand marks are out of the first sealed canvas.
 *
 * This is the sender layout the provider seals; the receiver never re-lays
 * out, it draws the sealed rectangles.
 */
internal object Grammar5Grid {

    data class Grid(
        val cells: List<CollageRenderer.RectI>,
        val noteRegion: CollageRenderer.RectI?,
        val musicRegion: CollageRenderer.RectI?,
        val branchId: String,
    )

    fun place(photoCount: Int, noteHeight: Int?, music: Boolean): Grid? {
        val recipe = recipe(photoCount) ?: return null
        var bottom = SHEET_BOTTOM
        val noteRegion = if (noteHeight != null && noteHeight > 0) {
            val top = bottom - noteHeight
            if (top < PHOTO_TOP + MIN_PHOTO_HEIGHT) return null
            bottom = top - BLOCK_GAP
            CollageRenderer.RectI(CONTENT_X, top, CONTENT_WIDTH, noteHeight)
        } else {
            null
        }
        val musicRegion = if (music) {
            val top = bottom - MUSIC_HEIGHT
            if (top < PHOTO_TOP + MIN_PHOTO_HEIGHT) return null
            bottom = top - BLOCK_GAP
            CollageRenderer.RectI(CONTENT_X, top, CONTENT_WIDTH, MUSIC_HEIGHT)
        } else {
            null
        }
        val photoArea = CollageRenderer.RectI(PHOTO_X, PHOTO_TOP, PHOTO_WIDTH, bottom - PHOTO_TOP)
        if (photoArea.height <= 0) return null
        val cells = bands(recipe, photoArea) ?: return null
        if (cells.size != photoCount) return null
        return Grid(
            cells = cells,
            noteRegion = noteRegion,
            musicRegion = musicRegion,
            branchId = "editorial-" + recipe.joinToString("+") + "-equal",
        )
    }

    private fun recipe(photoCount: Int): List<Int>? = when (photoCount) {
        3 -> listOf(1, 2)
        4 -> listOf(1, 1, 2)
        5 -> listOf(1, 2, 2)
        else -> null
    }

    private fun bands(recipe: List<Int>, area: CollageRenderer.RectI): List<CollageRenderer.RectI>? {
        val rows = recipe.size
        val rowHeight = (area.height - BAND_GAP * (rows - 1)) / rows
        if (rowHeight <= 0) return null
        val cells = ArrayList<CollageRenderer.RectI>(recipe.sum())
        var y = area.y
        for (groupCount in recipe) {
            val columnWidth = (area.width - BAND_GAP * (groupCount - 1)) / groupCount
            if (columnWidth <= 0) return null
            var x = area.x
            repeat(groupCount) {
                cells += CollageRenderer.RectI(x, y, columnWidth, rowHeight)
                x += columnWidth + BAND_GAP
            }
            y += rowHeight + BAND_GAP
        }
        return cells
    }

    private const val SHEET_BOTTOM = 603
    private const val PHOTO_TOP = 101
    private const val PHOTO_X = 10
    private const val PHOTO_WIDTH = 340
    private const val CONTENT_X = 16
    private const val CONTENT_WIDTH = 328
    private const val MUSIC_HEIGHT = 48
    private const val BLOCK_GAP = 10
    private const val BAND_GAP = 5
    private const val MIN_PHOTO_HEIGHT = 80
}
