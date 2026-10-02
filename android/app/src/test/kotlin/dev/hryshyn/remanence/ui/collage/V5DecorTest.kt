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
        // Independently derived from runtime/v4.css (line numbers are file
        // lines of the single-line CSS): base `.music` (line 4), V3/V4
        // overrides (lines 12/16), compact (line 20), spine (line 16).
        val spine = V5Decor.bandLayout("g6", "spine", 590)
        assertTrue(spine.verticalText)
        assertEquals(10, spine.gap)
        assertEquals(8, spine.padTop)
        assertEquals(3, spine.padSide)

        // g11 tile: line-12 padding:9px/gap:7px supersedes line-4
        // padding:12px/gap:6px AND padding-top:10px (equal specificity,
        // later shorthand wins); column survives.
        val g11tile = V5Decor.bandLayout("11", "tile-art", 200)
        assertTrue(g11tile.column)
        assertEquals(7, g11tile.gap)
        assertEquals(9, g11tile.padTop)
        assertEquals(9, g11tile.padSide)
        assertEquals(9, g11tile.padBottom)
        val g11tileText = V5Decor.bandLayout("11", "tile-text", 200)
        assertTrue(g11tileText.column)
        assertEquals(7, g11tileText.gap)

        val g24tile = V5Decor.bandLayout("g24", "tile-art", 200)
        assertTrue(g24tile.column)
        assertEquals(5, g24tile.gap)
        assertEquals(7, g24tile.padTop)

        // Compact beats every grammar rule: 3/5/3, gap 7; column kept.
        val compactTile = V5Decor.bandLayout("g24", "tile-art", 60)
        assertTrue(compactTile.column)
        assertEquals(7, compactTile.gap)
        assertEquals(3, compactTile.padTop)
        assertEquals(5, compactTile.padSide)
        val compactThumb = V5Decor.bandLayout("5", "thumbnail", 48)
        assertFalse(compactThumb.column)
        assertEquals(7, compactThumb.gap)
        assertEquals(3, compactThumb.padTop)
        assertEquals(5, compactThumb.padSide)

        val sleeve = V5Decor.bandLayout("20", "sleeve", 145)
        assertFalse(sleeve.column)
        assertEquals(14, sleeve.gap)
        assertEquals(9, sleeve.padTop)
        assertEquals(0, sleeve.padSide)
        // sleeve-empty matches no sleeve rule → base padding.
        val sleeveEmpty = V5Decor.bandLayout("20", "sleeve-empty", 100)
        assertEquals(9, sleeveEmpty.gap)
        assertEquals(7, sleeveEmpty.padTop)
        assertEquals(4, sleeveEmpty.padSide)

        val pulse = V5Decor.bandLayout("24", "pulse", 76)
        assertFalse(pulse.column)
        assertFalse(pulse.verticalText)
        assertEquals(10, pulse.gap)
        assertEquals(9, pulse.padTop)
        assertEquals(9, pulse.padSide)

        val inline = V5Decor.bandLayout("22", "inline", 100)
        assertEquals(8, inline.gap)
        assertEquals(4, inline.padTop)
        assertEquals(0, inline.padSide)
        // g23 inline keeps its own 5/7 padding (higher specificity).
        val g23inline = V5Decor.bandLayout("g23", "inline", 100)
        assertEquals(5, g23inline.padTop)
        assertEquals(7, g23inline.padSide)

        val g6thumb = V5Decor.bandLayout("6", "thumbnail", 100)
        assertEquals(5, g6thumb.padTop)
        assertEquals(8, g6thumb.padSide)
        val g12text = V5Decor.bandLayout("12", "text", 100)
        assertEquals(3, g12text.padTop)
        assertEquals(6, g12text.padSide)
        val g18text = V5Decor.bandLayout("18", "text", 100)
        assertEquals(4, g18text.padTop)
        assertEquals(5, g18text.padSide)
        val g21text = V5Decor.bandLayout("21", "text", 100)
        assertEquals(3, g21text.padTop)
        assertEquals(3, g21text.padSide)
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
        // Row: g24 pulse (340x76) at x=10 — content-left is 19 (10+9
        // padding), cover 57, gap 10, so the copy starts at x=86
        // (v4.css `.g24 .music-pulse{padding:9px;gap:10px}`).
        val row = V5Decor.placeCover(10, 500, 340, 76, "g24", "pulse")!!
        assertEquals(CollageRenderer.RectI(19, 509, 57, 57), row.cover)
        assertEquals(CollageRenderer.RectI(86, 509, 255, 58), row.text)
        // Column: g24 tile (104x200) — 50px cover top-left of the content
        // box, copy full content width below.
        val col = V5Decor.placeCover(250, 300, 104, 200, "24", "tile-art")!!
        assertEquals(CollageRenderer.RectI(257, 307, 50, 50), col.cover)
        assertEquals(CollageRenderer.RectI(257, 362, 90, 131), col.text)
        // g11 tile-art: FILL is the content width (360−2·9=342), not the
        // border width; copy below after gap 7.
        val g11 = V5Decor.placeCover(0, 0, 360, 200, "g11", "tile-art")!!
        assertEquals(CollageRenderer.RectI(9, 9, 342, 60), g11.cover)
        assertEquals(CollageRenderer.RectI(9, 76, 342, 115), g11.text)
        assertEquals(V5Decor.CoverFit.COVER, g11.fit)
        // Compact thumbnail: 36px cover inside the 3/5/3 content box.
        val compact = V5Decor.placeCover(16, 480, 328, 48, "5", "thumbnail")!!
        assertEquals(CollageRenderer.RectI(21, 486, 36, 36), compact.cover)
        assertEquals(CollageRenderer.RectI(64, 483, 275, 42), compact.text)
        // Collapsed remainder → null (prepare rejects fail-closed).
        assertNull(V5Decor.placeCover(10, 500, 60, 76, "g24", "pulse"))
        assertNull(V5Decor.placeCover(10, 500, 340, 76, "g6", "spine"))
    }
}
