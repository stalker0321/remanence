package dev.hryshyn.remanence.ui.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the bundled collage styles to the capsule-engine V5 freeze
 * `runtime/styles.js` (grammar groups 5, 6, 11, 18, 27) plus
 * `runtime/styles-extension.js` (groups 12, 17, 20, 21, 22, 23, 24, 25,
 * 26) and to hardcoded STYD01 v1 golden bytes/digests in the accepted
 * `V5 contract` section 7 order. The goldens were derived independently
 * from that byte order; they are not recomputed from the production
 * encoder here. The slice-1 press/blue-letter/poster goldens are frozen
 * byte-identical.
 */
class StyleRegistryTest {

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun style(id: String): StyleRegistry.Style = requireNotNull(StyleRegistry.style(id))

    @Test
    fun bundledStylesAreAllFourteenFrozenGroups() {
        assertEquals(
            setOf(
                "press", "blue-letter", "poster",
                "signal", "night-air",
                "plum", "construction", "pool",
                "proof", "ochre",
                "nocturne", "porcelain",
                "telemetry", "folio",
                "espresso", "aubergine", "lacquer",
                "evergreen", "studio",
                "ink-letter", "correspondence",
                "apricot", "mineral", "brass",
                "glacier", "ultraviolet",
                "orchid", "ember",
                "darkroom", "rose-stock", "registration",
                "saffron", "blue-hour",
            ),
            StyleRegistry.ids.filterTo(HashSet()) { style(it).version != StyleRegistry.CHARACTER_VERSION },
        )
        for (id in listOf("press", "blue-letter", "poster")) {
            assertEquals("style-study-1", style(id).version)
            assertEquals(StyleRegistry.GRAMMAR, style(id).grammar)
            assertEquals("flat", style(id).surfaceMode)
        }
        // styles.js groups carry style-study-1; styles-extension.js groups
        // carry pilot-styles-1.
        for (id in listOf("signal", "plum", "proof", "nocturne")) {
            assertEquals("style-study-1", style(id).version)
        }
        for (id in listOf("telemetry", "espresso", "evergreen", "ink-letter", "apricot", "glacier", "orchid", "darkroom", "saffron")) {
            assertEquals("pilot-styles-1", style(id).version)
        }
    }

    @Test
    fun paletteMatchesTheFreezeExactly() {
        val press = style("press").palette
        assertEquals("#f2f3b0", press.paper)
        assertEquals("#191b19", press.ink)
        assertEquals("#191b19", press.sound)
        assertEquals("#f2f3b0", press.soundInk)
        assertEquals("#f2f3b0", press.surface)
        assertEquals("#191b19", press.edge)

        val blueLetter = style("blue-letter").palette
        assertEquals("#1525a0", blueLetter.paper)
        assertEquals("#f9edd8", blueLetter.ink)
        assertEquals("#f9edd8", blueLetter.sound)
        assertEquals("#1525a0", blueLetter.soundInk)
        assertEquals("#1525a0", blueLetter.surface)
        assertEquals("#f9edd8", blueLetter.edge)

        val poster = style("poster").palette
        assertEquals("#f4c4c2", poster.paper)
        assertEquals("#521719", poster.ink)
        assertEquals("#b82025", poster.sound)
        assertEquals("#fff6de", poster.soundInk)
        assertEquals("#f4c4c2", poster.surface)
        assertEquals("#521719", poster.edge)
    }

    @Test
    fun textRolesMatchTheFreezeExactly() {
        val press = style("press")
        assertEquals(StyleRegistry.TextStyle("StudySans", 59, 700, false), press.brand)
        assertEquals(StyleRegistry.TextStyle("StudyMono", 16, 400, false), press.sender)
        assertEquals(StyleRegistry.TextStyle("StudySans", 22, 400, false), press.note)
        assertEquals(StyleRegistry.TextStyle("StudySans", 20, 400, false), press.music)
        assertEquals(-0.065, press.tracking, 0.0)

        val blueLetter = style("blue-letter")
        assertEquals(StyleRegistry.TextStyle("StudySerif", 67, 400, false), blueLetter.brand)
        assertEquals(StyleRegistry.TextStyle("StudySerif", 20, 400, true), blueLetter.sender)
        assertEquals(StyleRegistry.TextStyle("StudySerif", 26, 400, false), blueLetter.note)
        assertEquals(StyleRegistry.TextStyle("StudySerif", 22, 400, true), blueLetter.music)
        assertEquals(-0.045, blueLetter.tracking, 0.0)

        val poster = style("poster")
        assertEquals(StyleRegistry.TextStyle("Narrow", 78, 700, false), poster.brand)
        assertEquals(StyleRegistry.TextStyle("StudySans", 17, 700, false), poster.sender)
        assertEquals(StyleRegistry.TextStyle("StudySans", 23, 400, false), poster.note)
        assertEquals(StyleRegistry.TextStyle("Narrow", 24, 700, false), poster.music)
        assertEquals(-0.01, poster.tracking, 0.0)
    }

    @Test
    fun canonicalRecordBytesMatchGolden() {
        assertEquals(
            "5354594430310000000d7374796c652d73747564792d3100000005707265737300000005" +
                "000000057061706572000000072366326633623000000003696e6b0000000723313931623139" +
                "00000005736f756e64000000072331393162313900000008736f756e64496e6b0000000723663266336230" +
                "0000000773757266616365000000072366326633623000000004656467650000000723313931623139" +
                "00000009537475647953616e730000003b000002bc000000000953747564794d6f6e6f0000001000000190" +
                "0000000009537475647953616e7300000016000001900000000009537475647953616e73000000140000019000" +
                "ffffffbf00000004666c6174",
            hex(requireNotNull(StyleRegistry.canonicalBytes("press"))),
        )
        assertEquals(
            "5354594430310000000d7374796c652d73747564792d310000000b626c75652d6c657474657200000005" +
                "000000057061706572000000072331353235613000000003696e6b0000000723663965646438" +
                "00000005736f756e64000000072366396564643800000008736f756e64496e6b0000000723313532356130" +
                "0000000773757266616365000000072331353235613000000004656467650000000723663965646438" +
                "0000000a537475647953657269660000004300000190000000000a53747564795365726966000000140000019001" +
                "0000000a537475647953657269660000001a00000190000000000a53747564795365726966000000160000019001" +
                "ffffffd300000004666c6174",
            hex(requireNotNull(StyleRegistry.canonicalBytes("blue-letter"))),
        )
        assertEquals(
            "5354594430310000000d7374796c652d73747564792d3100000006706f7374657200000005" +
                "000000057061706572000000072366346334633200000003696e6b0000000723353231373139" +
                "00000005736f756e64000000072362383230323500000008736f756e64496e6b0000000723666666366465" +
                "0000000773757266616365000000072366346334633200000004656467650000000723353231373139" +
                "000000064e6172726f770000004e000002bc0000000009537475647953616e7300000011000002bc" +
                "0000000009537475647953616e73000000170000019000000000064e6172726f7700000018000002bc00" +
                "fffffff600000004666c6174",
            hex(requireNotNull(StyleRegistry.canonicalBytes("poster"))),
        )
    }

    @Test
    fun digestsMatchGoldenSha256() {
        assertEquals(
            "1f68610fa70b5210a72643d58f795c777d7a0e1eae262c522426d4b79a9a9a93",
            hex(requireNotNull(StyleRegistry.digest("press"))),
        )
        assertEquals(
            "da2d58fba44cb343a7020a8598d559d89d5e59a55f3651334b5c5647ef1872c3",
            hex(requireNotNull(StyleRegistry.digest("blue-letter"))),
        )
        assertEquals(
            "628aec674a6d38afda3029e690f7a8b8ac13db90ed3bf3b2bb6bcadceccce5d4",
            hex(requireNotNull(StyleRegistry.digest("poster"))),
        )
    }

    @Test
    fun digestIsThirtyTwoRawBytes() {
        for (id in StyleRegistry.ids) {
            assertEquals(32, requireNotNull(StyleRegistry.digest(id)).size)
        }
    }

    @Test
    fun recordExcludesEditorialKeys() {
        for (id in StyleRegistry.ids) {
            val text = String(requireNotNull(StyleRegistry.canonicalBytes(id)), Charsets.ISO_8859_1)
            assertFalse("$id must not encode name", text.contains("name"))
            assertFalse("$id must not encode idea", text.contains("idea"))
            assertFalse("$id must not encode baseline", text.contains("baseline"))
        }
    }

    @Test
    fun unknownAndOtherFreezeStylesFailClosed() {
        for (id in listOf("v4", "unknown", "", "PRESS", "press ")) {
            assertNull("expected $id to be absent", StyleRegistry.style(id))
            assertNull("expected $id to be absent", StyleRegistry.canonicalBytes(id))
            assertNull("expected $id to be absent", StyleRegistry.digest(id))
        }
        assertTrue(StyleRegistry.PALETTE_KEYS == listOf("paper", "ink", "sound", "soundInk", "surface", "edge"))
    }
}
