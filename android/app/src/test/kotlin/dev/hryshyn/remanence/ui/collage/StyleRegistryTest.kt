package dev.hryshyn.remanence.ui.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the three bundled collage styles to the capsule-engine V5 freeze
 * `runtime/styles.js` (grammar group 5) and to hardcoded STYD01 v1 golden
 * bytes/digests. The goldens were derived independently from the record
 * grammar in [StyleRegistry]; they are not recomputed from the production
 * encoder here.
 */
class StyleRegistryTest {

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun bundledStylesAreExactlyPressBlueLetterPoster() {
        assertEquals(setOf("press", "blue-letter", "poster"), StyleRegistry.ids)
        for (id in listOf("press", "blue-letter", "poster")) {
            assertEquals("style-study-1", StyleRegistry.style(id)?.version)
        }
    }

    @Test
    fun colorsMatchTheFreezeExactly() {
        val press = requireNotNull(StyleRegistry.style("press"))
        assertEquals(0xFFF2F3B0.toInt(), press.paper)
        assertEquals(0xFF191B19.toInt(), press.ink)
        assertEquals(0xFF191B19.toInt(), press.sound)
        assertEquals(0xFFF2F3B0.toInt(), press.soundInk)

        val blueLetter = requireNotNull(StyleRegistry.style("blue-letter"))
        assertEquals(0xFF1525A0.toInt(), blueLetter.paper)
        assertEquals(0xFFF9EDD8.toInt(), blueLetter.ink)
        assertEquals(0xFFF9EDD8.toInt(), blueLetter.sound)
        assertEquals(0xFF1525A0.toInt(), blueLetter.soundInk)

        val poster = requireNotNull(StyleRegistry.style("poster"))
        assertEquals(0xFFF4C4C2.toInt(), poster.paper)
        assertEquals(0xFF521719.toInt(), poster.ink)
        assertEquals(0xFFB82025.toInt(), poster.sound)
        assertEquals(0xFFFFF6DE.toInt(), poster.soundInk)
    }

    @Test
    fun canonicalRecordBytesMatchGolden() {
        assertEquals(
            "535459443031000000010000000570726573730000000d7374796c652d73747564792d31" +
                "f2f3b0191b19191b19f2f3b0",
            hex(requireNotNull(StyleRegistry.canonicalBytes("press"))),
        )
        assertEquals(
            "535459443031000000010000000b626c75652d6c65747465720000000d7374796c652d73747564792d31" +
                "1525a0f9edd8f9edd81525a0",
            hex(requireNotNull(StyleRegistry.canonicalBytes("blue-letter"))),
        )
        assertEquals(
            "5354594430310000000100000006706f737465720000000d7374796c652d73747564792d31" +
                "f4c4c2521719b82025fff6de",
            hex(requireNotNull(StyleRegistry.canonicalBytes("poster"))),
        )
    }

    @Test
    fun digestsMatchGoldenSha256() {
        assertEquals(
            "fc3df54a496ab6f7fe55e8d28ff0d532b44b088c6c85f3c8890c1e1d17f72d94",
            hex(requireNotNull(StyleRegistry.digest("press"))),
        )
        assertEquals(
            "72ca2076a07a4c9e5e0d6ef96d35aa7f07e80f8cd50b1aad108061ec92460c83",
            hex(requireNotNull(StyleRegistry.digest("blue-letter"))),
        )
        assertEquals(
            "622aa63144843c5e4e5a044b7e34de4fc20fe0ea9ee23decb1fa5ab3ea81cb08",
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
    fun unknownAndOtherFreezeStylesFailClosed() {
        for (id in listOf("signal", "night-air", "plum", "proof", "v4", "unknown", "")) {
            assertNull("expected $id to be absent", StyleRegistry.style(id))
            assertNull("expected $id to be absent", StyleRegistry.canonicalBytes(id))
            assertNull("expected $id to be absent", StyleRegistry.digest(id))
        }
    }
}
