package dev.hryshyn.remanence.ui.capsule

import dev.hryshyn.remanence.ui.collage.CollageRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The site's `letter-mark.test.ts`, the same numbers. */
class LetterMarkPlaceTest {

    private val region = CollageRenderer.RectI(16, 573, 328, 52)

    @Test
    fun sitsRightAfterTheLastWordWhenTheLineHasRoom() {
        val place = letterMarkPlace(region, 20, lastLineWidth = 140f, lastBaseline = 46f)
        assertFalse(place.backed)
        assertEquals(18f, place.size)
        assertEquals(16f + 140f + 6f, place.x)
        // Centred a little above the last row's baseline.
        assertEquals(573f + 46f - 6f, place.y + place.size / 2, 1e-4f)
    }

    @Test
    fun takesTheEndOfAFullLastLineOnAPatchOfPaper() {
        val place = letterMarkPlace(region, 20, lastLineWidth = 318f, lastBaseline = 46f)
        assertTrue(place.backed)
        assertEquals(16f + 328f - 18f, place.x)
    }

    @Test
    fun keepsItsSizeBetween12And18() {
        assertEquals(12f, letterMarkPlace(region, 8, 50f, 8f).size)
        assertEquals(14f, letterMarkPlace(region, 13, 50f, 13f).size)
        assertEquals(18f, letterMarkPlace(region, 40, 50f, 40f).size)
    }

    @Test
    fun staysInsideTheRegionWhateverTheLastLineHolds() {
        for (height in listOf(19, 26, 30, 41, 120)) {
            val box = CollageRenderer.RectI(12, 263, 140, height)
            for (size in listOf(14, 20, 28)) {
                if (size > height) continue
                for (last in listOf(1f, 70f, 130f, 140f)) {
                    for (baseline in listOf(size.toFloat(), height.toFloat())) {
                        val place = letterMarkPlace(box, size, last, baseline)
                        assertTrue(place.x >= box.x)
                        assertTrue(place.x + place.size <= box.x + box.width)
                        assertTrue(place.y >= box.y)
                        if (place.size <= height) assertTrue(place.y + place.size <= box.y + box.height)
                    }
                }
            }
        }
    }

    @Test
    fun aNoteWithNoRowsPutsTheMarkAtTheRegionEnd() {
        val place = letterMarkPlace(region, 20, lastLineWidth = null, lastBaseline = null)
        assertTrue(place.backed)
        assertEquals(16f + 328f - 18f, place.x)
    }
}
