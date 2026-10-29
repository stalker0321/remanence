package dev.hryshyn.remanence.ui.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T154: all 14 frozen groups are bundled verbatim (`styles.js` version
 * `style-study-1`, `styles-extension.js` version `pilot-styles-1`); every
 * role face resolves in [FontRegistry] (no fallback needed anywhere).
 */
class StyleRegistryV5Test {

    private fun style(id: String): StyleRegistry.Style = requireNotNull(StyleRegistry.style(id))

    @Test
    fun allFourteenGroupsBundled() {
        val character = StyleRegistry.ids.count { style(it).version == StyleRegistry.CHARACTER_VERSION }
        assertEquals(33, StyleRegistry.ids.size - character)
        // Two per grammar, plus the blue-letter copy whose music band has its own colour.
        assertEquals(29, character)
        val grammars = StyleRegistry.ids.map { style(it).grammar }.toSet()
        assertEquals(
            setOf(5, 6, 11, 12, 17, 18, 20, 21, 22, 23, 24, 25, 26, 27),
            grammars,
        )
    }

    @Test
    fun extensionSpotValuesMatchFreeze() {
        val telemetry = style("telemetry")
        assertEquals(12, telemetry.grammar)
        assertEquals("pilot-styles-1", telemetry.version)
        assertEquals("#102e35", telemetry.palette.paper)
        assertEquals(StyleRegistry.TextStyle("StudyMono", 19, 400, false), telemetry.brand)

        val espresso = style("espresso")
        assertEquals(StyleRegistry.TextStyle("StudySans", 55, 700, false), espresso.brand)
        assertEquals(-0.065, espresso.tracking, 0.0)

        val darkroom = style("darkroom")
        assertEquals("#faf8ef", darkroom.palette.surface)
        assertEquals("#d2d5c8", darkroom.palette.edge)
        assertEquals("print", darkroom.surfaceMode)

        val brass = style("brass")
        assertEquals(StyleRegistry.TextStyle("Narrow", 38, 700, false), brass.sender)

        val inkLetter = style("ink-letter")
        assertEquals(StyleRegistry.TextStyle("StudySerif", 32, 400, true), inkLetter.sender)
    }

    @Test
    fun everyRoleFaceIsBundled() {
        for (id in StyleRegistry.ids) {
            val s = style(id)
            for (role in listOf(s.brand, s.sender, s.note, s.music)) {
                assertNotNull(
                    "$id/${role.font}/${role.weight}/${role.italic}",
                    FontRegistry.resourceFor(role.font, role.weight, role.italic),
                )
            }
            // Character styles letterspace their capitals up to .3 em.
            val tracking = if (s.version == StyleRegistry.CHARACTER_VERSION) -0.1..0.35 else -0.1..0.1
            assertTrue("$id tracking sane", s.tracking in tracking)
            assertTrue(
                "$id surface mode",
                s.surfaceMode in setOf("flat", "soft", "edge", "raised", "print"),
            )
        }
    }
}
