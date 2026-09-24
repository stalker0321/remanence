package dev.hryshyn.remanence.core.model

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * S2 snapshot validation: strict UUID/length/UTF-8/bounds, fail-closed.
 * Pure JVM, no crypto, no fixtures.
 */
class CapsuleTrackSnapshotTest {

    private fun valid(
        trackId: String = "be30e36b-1111-4111-8111-000000000001",
        title: String = "505",
        artistDisplay: String = "Arctic Monkeys",
        version: String? = null,
        durationMs: Long? = 253000L,
    ) = CapsuleTrackSnapshotV1.parse(trackId, title, artistDisplay, version, durationMs)

    @Test
    fun validSnapshotRoundTripsFields() {
        val snapshot = valid(version = "Live at the Apollo")
        assertEquals(UUID.fromString("be30e36b-1111-4111-8111-000000000001"), snapshot.trackId)
        assertEquals("505", snapshot.title)
        assertEquals("Arctic Monkeys", snapshot.artistDisplay)
        assertEquals("Live at the Apollo", snapshot.version)
        assertEquals(253000L, snapshot.durationMs)
    }

    @Test
    fun optionalFieldsMayBeAbsent() {
        val snapshot = valid(version = null, durationMs = null)
        assertNull(snapshot.version)
        assertNull(snapshot.durationMs)
    }

    @Test
    fun schemaVersionIsOne() {
        assertEquals(1, CapsuleTrackSnapshotV1.SCHEMA_VERSION)
    }

    @Test
    fun nonUuidTrackIdRejected() {
        for (bad in listOf("", "not-a-uuid", "mbid-without-dashes", "be30e36b-1111-4111-8111-00000000000g")) {
            assertFailsWith<IllegalArgumentException> {
                valid(trackId = bad)
            }
        }
    }

    @Test
    fun blankOrOverlongTitleRejected() {
        assertFailsWith<IllegalArgumentException> { valid(title = "") }
        assertFailsWith<IllegalArgumentException> { valid(title = "   ") }
        assertFailsWith<IllegalArgumentException> { valid(title = "x".repeat(101)) }
        valid(title = "x".repeat(100))
    }

    @Test
    fun blankOrOverlongArtistDisplayRejected() {
        assertFailsWith<IllegalArgumentException> { valid(artistDisplay = "") }
        assertFailsWith<IllegalArgumentException> { valid(artistDisplay = "x".repeat(201)) }
        valid(artistDisplay = "x".repeat(200))
    }

    @Test
    fun blankOrOverlongVersionRejected() {
        assertFailsWith<IllegalArgumentException> { valid(version = "") }
        assertFailsWith<IllegalArgumentException> { valid(version = "x".repeat(101)) }
        valid(version = "x".repeat(100))
    }

    @Test
    fun nonPositiveOrOversizedDurationRejected() {
        assertFailsWith<IllegalArgumentException> { valid(durationMs = 0L) }
        assertFailsWith<IllegalArgumentException> { valid(durationMs = -5L) }
        assertFailsWith<IllegalArgumentException> { valid(durationMs = 86_400_001L) }
        valid(durationMs = 1L)
        valid(durationMs = 86_400_000L)
    }

    @Test
    fun loneSurrogateIsNotUtf8Encodable() {
        assertFailsWith<IllegalArgumentException> { valid(title = "bad \uD800 title") }
        assertFailsWith<IllegalArgumentException> { valid(artistDisplay = "bad \uDFFF artist") }
    }

    @Test
    fun cyrillicAndAccentedTextAccepted() {
        val snapshot = valid(title = "Группа крови", artistDisplay = "Кино / Beyoncé")
        assertEquals("Группа крови", snapshot.title)
    }

    @Test
    fun redactedToStringHidesDisplayStrings() {
        val text = valid().toString()
        assertEquals(false, text.contains("505"))
        assertEquals(false, text.contains("Arctic Monkeys"))
    }
}
