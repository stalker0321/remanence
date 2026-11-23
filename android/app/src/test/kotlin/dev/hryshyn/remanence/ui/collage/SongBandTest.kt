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

    private val peaks = IntArray(SongBand.VOICE_PEAKS) { (it * 37) % 256 }

    @Test
    fun writesAVoiceLengthAsWholeSeconds() {
        assertEquals("0:42", SongBand.voiceLength(42_400))
        assertEquals("1:00", SongBand.voiceLength(60_000))
        assertEquals("0:01", SongBand.voiceLength(200))
    }

    /** The numbers the site's `voiceBandLayout` prints for the same input (23 November). */
    @Test
    fun voiceBandsMatchTheSitePixelForPixel() {
        val plate = SongBand.voiceLayout(band, "plate", type, peaks, 42_400, width)
        assertEquals(CollageRenderer.RectI(38, 560, 20, 22), plate.play)
        assertEquals(CollageRenderer.RectI(92, 582, 12, 12), plate.mic)
        assertEquals(listOf(Triple("0:42", 109, 581)), plate.runs.map { Triple(it.text, it.x, it.y) })
        assertEquals(listOf(CollageRenderer.RectI(93, 560, 2, 3), CollageRenderer.RectI(178, 554, 2, 14), CollageRenderer.RectI(327, 550, 2, 23)), listOf(plate.bars[0], plate.bars[17], plate.bars[47]))
        assertNotNull(plate.divider)

        val caption = SongBand.voiceLayout(band, "caption", type, peaks, 42_400, width)
        assertEquals(CollageRenderer.RectI(16, 557, 36, 36), caption.play)
        assertNull(caption.mic)
        assertEquals(listOf(Triple("0:42", 304, 563)), caption.runs.map { Triple(it.text, it.x, it.y) })
        assertEquals(listOf(CollageRenderer.RectI(65, 574, 2, 3), CollageRenderer.RectI(146, 569, 2, 12), CollageRenderer.RectI(289, 566, 2, 18)), listOf(caption.bars[0], caption.bars[17], caption.bars[47]))

        val card = SongBand.voiceLayout(spine, "card", type, peaks, 42_400, width)
        assertEquals(CollageRenderer.RectI(163, 543, 34, 34), card.play)
        assertEquals(CollageRenderer.RectI(174, 519, 12, 12), card.mic)
        assertEquals(listOf(Triple("0:42", 173, 513)), card.runs.map { Triple(it.text, it.x, it.y) })
        assertEquals(listOf(CollageRenderer.RectI(179, 27, 3, 4), CollageRenderer.RectI(172, 185, 17, 4), CollageRenderer.RectI(166, 464, 28, 4)), listOf(card.bars[0], card.bars[17], card.bars[47]))
        assertTrue(card.runs.all { it.vertical })
        assertEquals(SongBand.VOICE_PEAKS, card.bars.size)
    }

    @Test
    fun voiceModesBorrowTheSongBandForms() {
        assertEquals("plate", SongBand.voiceBandShape("voice-plate"))
        assertEquals("caption", SongBand.voiceBandShape("voice-caption"))
        assertEquals("card", SongBand.voiceBandShape("voice-card"))
    }
}
