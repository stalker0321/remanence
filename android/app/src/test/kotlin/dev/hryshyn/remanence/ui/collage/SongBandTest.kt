package dev.hryshyn.remanence.ui.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as the site's `song-band.test.ts`: both platforms lay the band out alike. */
class SongBandTest {
    /** 0.5 em per glyph: widths are easy to reason about. */
    private val width = SongBand.RunWidth { text, face -> (text.codePointCount(0, text.length) * face.size * 0.5).toInt() }
    private val type = SongBand.Face("StudySans", 20, 400, false, 23)
    private val band = CollageRenderer.RectI(16, 539, 328, 64)
    private val spine = CollageRenderer.RectI(152, 0, 56, 591)

    @Test
    fun cutsALongTitleWithAnEllipsis() {
        assertEquals("Moon", SongBand.fitRun("Moon", type, 100, width))
        assertEquals("A very lo…", SongBand.fitRun("A very long song title", type, 100, width))
        assertNull(SongBand.fitRun("   ", type, 100, width))
    }

    @Test
    fun platePutsTheCoverInAFullHeightSquare() {
        val plate = requireNotNull(SongBand.layout(band, "plate", type, SongBand.Words("Bohemian Rhapsody", "Queen"), width, true))
        assertEquals(CollageRenderer.RectI(16, 539, 64, 64), plate.cover)
        assertEquals(listOf("Bohemian Rhapsody", "QUEEN"), plate.runs.map { it.text })
        assertEquals(16 + 64 + 12, plate.runs[0].x)
        val plain = requireNotNull(SongBand.layout(band, "plate", type, SongBand.Words("Moon", "Ada"), width, false))
        assertNull(plain.cover)
        assertNotNull(plain.divider)
    }

    @Test
    fun captionOnlyWhenOneLineFits() {
        assertEquals("caption", SongBand.layout(band, "caption", type, SongBand.Words("Moon", "Ada"), width, false)?.mode)
        assertNull(SongBand.layout(band, "caption", type, SongBand.Words("A long title that never fits here", "Somebody"), width, false))
    }

    @Test
    fun spineRunsTheTitleAlongTheSpine() {
        val card = requireNotNull(SongBand.layout(spine, "card", type, SongBand.Words("Под небом голубым", "Аквариум"), width, false))
        assertTrue(card.runs.all { it.vertical })
        assertEquals(26, card.runs[0].face.size)
        assertEquals(591 - 14, card.play.y + card.play.height)
        assertTrue(card.runs[0].y < card.runs[1].y)
    }
}
