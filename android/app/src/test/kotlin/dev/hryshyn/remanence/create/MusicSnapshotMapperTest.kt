package dev.hryshyn.remanence.create

import dev.hryshyn.remanence.core.data.network.MusicTrackHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * S2b-sender mapping: picker hit -> sealed snapshot, fail-closed.
 * Pure JVM (no Robolectric needed); the ViewModel calls this exact
 * function at the publish site and converts a throw into a publish
 * failure, never a silent drop.
 */
class MusicSnapshotMapperTest {

    private fun hit(
        id: String = "be30e36b-1111-4111-8111-000000000001",
        title: String = "505",
        artists: List<String> = listOf("Arctic Monkeys"),
        version: String? = null,
        durationMs: Long? = 253000L,
    ) = MusicTrackHit(
        id = id,
        title = title,
        artists = artists,
        version = version,
        release = "Favourite Worst Nightmare",
        year = 2007,
        durationMs = durationMs,
        artworkAvailable = true,
    )

    @Test
    fun validHitMapsAllFields() {
        val snapshot = mapMusicSelectionToSnapshot(hit(version = "Live at the Apollo"))
        assertEquals("be30e36b-1111-4111-8111-000000000001", snapshot.trackId.toString())
        assertEquals("505", snapshot.title)
        assertEquals("Arctic Monkeys", snapshot.artistDisplay)
        assertEquals("Live at the Apollo", snapshot.version)
        assertEquals(253000L, snapshot.durationMs)
    }

    @Test
    fun multipleArtistsJoinWithCommaSeparator() {
        val snapshot = mapMusicSelectionToSnapshot(hit(artists = listOf("A", "B", "C")))
        assertEquals("A, B, C", snapshot.artistDisplay)
    }

    @Test
    fun absentOptionalsStayAbsent() {
        val snapshot = mapMusicSelectionToSnapshot(hit(version = null, durationMs = null))
        assertNull(snapshot.version)
        assertNull(snapshot.durationMs)
    }

    @Test
    fun invalidHitThrowsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            mapMusicSelectionToSnapshot(hit(title = "   "))
        }
        assertThrows(IllegalArgumentException::class.java) {
            mapMusicSelectionToSnapshot(hit(artists = emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            mapMusicSelectionToSnapshot(hit(id = "not-a-uuid"))
        }
        // Joined display over the model bound is rejected, never truncated.
        assertThrows(IllegalArgumentException::class.java) {
            mapMusicSelectionToSnapshot(hit(artists = listOf("x".repeat(100), "y".repeat(100), "z".repeat(100))))
        }
    }
}
