package dev.hryshyn.remanence.ui.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the two freeze corrections: the photo/note field ends `music.y - 8`
 * (8px) before the music box, and the equal-count branch id carries the
 * authoritative `-1` suffix. Exact integer coordinates, so a regression to the
 * old 10px gap or the suffix-less branch id fails here.
 */
class Grammar5GridTest {

    private fun rect(x: Int, y: Int, w: Int, h: Int) = CollageRenderer.RectI(x, y, w, h)

    @Test
    fun branchIdCarriesTheAuthoritativeEqualSuffix() {
        assertEquals("editorial-1+2-equal-1", Grammar5Grid.place(3, null, false)!!.branchId)
        assertEquals("editorial-1+1+2-equal-1", Grammar5Grid.place(4, null, false)!!.branchId)
        assertEquals("editorial-1+2+2-equal-1", Grammar5Grid.place(5, null, false)!!.branchId)
    }

    @Test
    fun threePhotosWithoutClosingBands() {
        val grid = Grammar5Grid.place(3, null, false)!!
        assertNull(grid.noteRegion)
        assertNull(grid.musicRegion)
        assertEquals(
            listOf(rect(10, 101, 340, 248), rect(10, 354, 167, 248), rect(182, 354, 167, 248)),
            grid.cells,
        )
    }

    @Test
    fun noteClosesTheFieldAndMusicSitsTenAboveTheNote() {
        val grid = Grammar5Grid.place(3, 25, true)!!
        assertEquals(rect(16, 578, 328, 25), grid.noteRegion)
        assertEquals(rect(16, 520, 328, 48), grid.musicRegion)
        assertEquals(
            listOf(rect(10, 101, 340, 203), rect(10, 309, 167, 203), rect(182, 309, 167, 203)),
            grid.cells,
        )
    }

    @Test
    fun musicEndsEightAboveThePhotoField() {
        val grid = Grammar5Grid.place(3, null, true)!!
        assertNull(grid.noteRegion)
        assertEquals(rect(16, 555, 328, 48), grid.musicRegion)
        // Photo area is 446 tall because the field ends at 555 - 8 = 547.
        assertEquals(
            listOf(rect(10, 101, 340, 220), rect(10, 326, 167, 220), rect(182, 326, 167, 220)),
            grid.cells,
        )
    }

    @Test
    fun noteWithoutMusicEndsTenAboveTheNote() {
        val grid = Grammar5Grid.place(3, 25, false)!!
        assertEquals(rect(16, 578, 328, 25), grid.noteRegion)
        assertNull(grid.musicRegion)
        assertEquals(
            listOf(rect(10, 101, 340, 231), rect(10, 337, 167, 231), rect(182, 337, 167, 231)),
            grid.cells,
        )
    }
}
