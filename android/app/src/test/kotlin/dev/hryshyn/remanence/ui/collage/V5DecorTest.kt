package dev.hryshyn.remanence.ui.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T154 render-fix: exact frozen paint rules (traced to `v4.css` /
 * `styles.css` / `grammars.js:182` / `expression.js:33,76`). Pure JVM.
 */
class V5DecorTest {

    @Test
    fun coverSpecFollowsTheFrozenCascade() {
        // Default thumbnail + compact override.
        assertEquals(V5Decor.CoverSpec(40, 40, V5Decor.CoverFit.CONTAIN), V5Decor.coverSpec("5", "thumbnail", 328, 100))
        assertEquals(V5Decor.CoverSpec(36, 36, V5Decor.CoverFit.CONTAIN), V5Decor.coverSpec("5", "thumbnail", 328, 60))
        // Inline + g23 inline override.
        assertEquals(V5Decor.CoverSpec(39, 39, V5Decor.CoverFit.CONTAIN), V5Decor.coverSpec("22", "inline", 336, 100))
        assertEquals(V5Decor.CoverSpec(37, 37, V5Decor.CoverFit.CONTAIN), V5Decor.coverSpec("g23", "inline", 336, 100))
        // Grammar/mode overrides.
        assertEquals(V5Decor.CoverSpec(null, 60, V5Decor.CoverFit.COVER), V5Decor.coverSpec("g11", "tile-art", 104, 200))
        assertEquals(V5Decor.CoverSpec(50, 50, V5Decor.CoverFit.CONTAIN), V5Decor.coverSpec("24", "tile-art", 104, 200))
        assertEquals(V5Decor.CoverSpec(120, 120, V5Decor.CoverFit.CONTAIN), V5Decor.coverSpec("20", "sleeve", 332, 145))
        assertEquals(V5Decor.CoverSpec(57, 57, V5Decor.CoverFit.CONTAIN), V5Decor.coverSpec("g24", "pulse", 340, 76))
        // Text-only bands never carry a cover, even verified.
        for (mode in listOf("spine", "text", "tile-text", "sleeve-empty")) {
            assertNull(mode, V5Decor.coverSpec("6", mode, 300, 300))
        }
    }

    @Test
    fun bandLayoutMatchesFrozenFlexAndPadding() {
        val spine = V5Decor.bandLayout("g6", "spine")
        assertTrue(spine.verticalText)
        assertEquals(10, spine.gap)
        assertEquals(8, spine.padTop)

        val g11tile = V5Decor.bandLayout("11", "tile-art")
        assertTrue(g11tile.column)
        assertEquals(10, g11tile.padTop)
        assertEquals(12, g11tile.padSide)

        val g24tile = V5Decor.bandLayout("g24", "tile-art")
        assertTrue(g24tile.column)
        assertEquals(5, g24tile.gap)
        assertEquals(7, g24tile.padTop)

        val sleeve = V5Decor.bandLayout("20", "sleeve")
        assertFalse(sleeve.column)
        assertEquals(14, sleeve.gap)
        assertEquals(9, sleeve.padTop)

        val pulse = V5Decor.bandLayout("24", "pulse")
        assertFalse(pulse.column)
        assertFalse(pulse.verticalText)
        assertEquals(10, pulse.gap)

        val inline = V5Decor.bandLayout("22", "inline")
        assertEquals(8, inline.gap)
        assertEquals(4, inline.padTop)
        assertEquals(0, inline.padSide)
    }

    @Test
    fun roundRadiiAreAsymmetricOnlyForG11() {
        assertEquals(
            listOf(4f, 4f, 26f, 26f, 4f, 4f, 4f, 4f),
            V5Decor.roundRadiiPx("g11", 100, 200).toList(),
        )
        assertEquals(
            listOf(12f, 24f, 12f, 24f, 12f, 24f, 12f, 24f),
            V5Decor.roundRadiiPx("6", 100, 200).toList(),
        )
    }

    @Test
    fun printPlaneShadowAndCarrierBorderRules() {
        assertTrue(V5Decor.printPlaneShadow("25", "print"))
        assertFalse(V5Decor.printPlaneShadow("g25", "edge"))
        assertTrue(V5Decor.printPlaneShadow("18", "edge"))
        assertEquals(2, V5Decor.carrierBorderWidthPx("edge"))
        assertEquals(1, V5Decor.carrierBorderWidthPx("flat"))
        assertEquals(1, V5Decor.carrierBorderWidthPx("raised"))
        assertEquals(0x30151f23, V5Decor.DECOR_SHADOW_COLOR)
    }

    @Test
    fun placeCoverSharesOneGeometryBetweenGateAndPixels() {
        // Row: g24 pulse (340x76) → 57px cover, gap 10, pads 9.
        val row = V5Decor.placeCover(10, 500, 340, 76, "g24", "pulse")!!
        assertEquals(CollageRenderer.RectI(10, 509, 57, 57), row.cover)
        assertEquals(CollageRenderer.RectI(77, 509, 273, 58), row.text)
        // Column: g24 tile (104x200) → 50px cover top-left, copy below.
        val col = V5Decor.placeCover(250, 300, 104, 200, "24", "tile-art")!!
        assertEquals(CollageRenderer.RectI(250, 307, 50, 50), col.cover)
        assertEquals(CollageRenderer.RectI(257, 362, 90, 131), col.text)
        // Collapsed remainder → null (prepare rejects fail-closed).
        assertNull(V5Decor.placeCover(10, 500, 60, 76, "g24", "pulse"))
        assertNull(V5Decor.placeCover(10, 500, 340, 76, "g6", "spine"))
    }
}
