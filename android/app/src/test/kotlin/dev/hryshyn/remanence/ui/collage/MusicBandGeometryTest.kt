package dev.hryshyn.remanence.ui.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBandGeometryTest {
    @Test
    fun textCoverAndSpineKeepPlayInsideTheSealedBand() {
        val cases = listOf(
            Triple("5", "text", CollageRenderer.RectI(10, 500, 340, 60)),
            Triple("24", "pulse", CollageRenderer.RectI(10, 500, 340, 90)),
            Triple("11", "tile-art", CollageRenderer.RectI(10, 400, 150, 180)),
            Triple("20", "sleeve", CollageRenderer.RectI(10, 430, 340, 170)),
            Triple("6", "spine", CollageRenderer.RectI(290, 10, 60, 500)),
        )
        for ((grammar, mode, region) in cases) {
            val withArtwork = mode in V5Decor.COVER_IMAGE_MODES
            val layout = requireNotNull(MusicBandGeometry.layout(region, grammar, mode, withArtwork))
            assertTrue("$grammar/$mode play must be contained", contains(region, layout.play))
            assertTrue("$grammar/$mode copy must be contained", contains(region, layout.copy))
            if (mode != "spine") assertFalse("$grammar/$mode play must not obscure copy", overlaps(layout.play, layout.copy))
        }
    }

    @Test
    fun coverCopyReservesSpaceForPlayBeforeMeasuringText() {
        val region = CollageRenderer.RectI(10, 500, 340, 90)
        val art = requireNotNull(V5Decor.placeCover(10, 500, 340, 90, "24", "pulse"))
        val layout = requireNotNull(MusicBandGeometry.layout(region, "24", "pulse", true))
        assertTrue(layout.copy.x > art.text.x)
        assertTrue(layout.copy.width < art.text.width)
        assertEquals(36, layout.play.width)
        assertFalse(overlaps(layout.play, art.cover))
    }

    @Test
    fun narrowBandRefusesInsteadOfPaintingOutsideSealedBounds() {
        assertNull(MusicBandGeometry.layout(CollageRenderer.RectI(0, 0, 20, 60), "5", "text", false))
        assertNull(MusicBandGeometry.layout(CollageRenderer.RectI(0, 0, 60, 76), "24", "pulse", true))
        assertNotNull(MusicBandGeometry.layout(CollageRenderer.RectI(0, 0, 360, 60), "5", "text", false))
    }

    private fun contains(outer: CollageRenderer.RectI, inner: CollageRenderer.RectI) =
        inner.x >= outer.x && inner.y >= outer.y &&
            inner.x + inner.width <= outer.x + outer.width &&
            inner.y + inner.height <= outer.y + outer.height

    private fun overlaps(a: CollageRenderer.RectI, b: CollageRenderer.RectI) =
        a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y
}
