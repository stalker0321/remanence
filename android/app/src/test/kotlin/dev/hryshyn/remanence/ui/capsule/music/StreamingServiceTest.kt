package dev.hryshyn.remanence.ui.capsule.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 URL safety: exact per-service shapes, Unicode percent-encoding,
 * injection attempts staying inert inside allowlisted origins, and
 * strict scheme/host/userinfo/fragment/port rejection. Pure JVM.
 */
class StreamingServiceTest {

    @Test
    fun exactServiceShapes() {
        assertEquals(
            "https://open.spotify.com/search/505%20Arctic%20Monkeys",
            StreamingService.SPOTIFY.searchUrl("505", "Arctic Monkeys"),
        )
        assertEquals(
            "https://music.apple.com/us/search?term=505%20Arctic%20Monkeys",
            StreamingService.APPLE_MUSIC.searchUrl("505", "Arctic Monkeys"),
        )
        assertEquals(
            "https://music.youtube.com/search?q=505%20Arctic%20Monkeys",
            StreamingService.YOUTUBE_MUSIC.searchUrl("505", "Arctic Monkeys"),
        )
    }

    @Test
    fun unicodeAndReservedCharsEncoded() {
        val url = StreamingService.YOUTUBE_MUSIC.searchUrl("Группа крови", "Кино & Beyoncé?")
        assertTrue(url.startsWith("https://music.youtube.com/search?q="))
        assertTrue(url.contains("%D0%93%D1%80%D1%83%D0%BF%D0%BF%D0%B0"))
        assertTrue(!url.contains(" ") && !url.contains("&Beyonc"))
        for (service in StreamingService.entries) {
            assertTrue(StreamingService.isAllowedUrl(service.searchUrl("Café del Mar", "Beyoncé Tribute")))
        }
    }

    @Test
    fun injectionAttemptsStayInertQueryText() {
        val evil = StreamingService.SPOTIFY.searchUrl("https://evil.example/x", "a?b=c&d=e#f\"'")
        assertTrue(evil.startsWith("https://open.spotify.com/search/"))
        assertTrue(StreamingService.isAllowedUrl(evil))
        assertTrue(!evil.contains("evil.example/x ") && !evil.contains("#f"))
    }

    @Test
    fun allowlistRejectsNonConformingUrls() {
        assertFalse(StreamingService.isAllowedUrl("http://open.spotify.com/search/505"))
        assertFalse(StreamingService.isAllowedUrl("javascript:alert(1)"))
        assertFalse(StreamingService.isAllowedUrl("https://evil.example/search?q=505"))
        assertFalse(StreamingService.isAllowedUrl("https://user:pass@open.spotify.com/search/505"))
        assertFalse(StreamingService.isAllowedUrl("https://open.spotify.com/search/505#frag"))
        assertFalse(StreamingService.isAllowedUrl("https://open.spotify.com:8443/search/505"))
        assertFalse(StreamingService.isAllowedUrl("not a url"))
        assertFalse(StreamingService.isAllowedUrl(""))
    }

    @Test
    fun blankOrOversizedInputThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamingService.SPOTIFY.searchUrl("  ", "Arctic Monkeys")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StreamingService.SPOTIFY.searchUrl("505", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StreamingService.SPOTIFY.searchUrl("x".repeat(150), "y".repeat(160))
        }
    }

    @Test
    fun largestSealedSnapshotQueryFitsExactly() {
        // S3 STOP: title 100 + space + artist 200 = 301 must never throw
        // in a click handler. The cap derives from the model bounds.
        assertEquals(301, 100 + 1 + 200)
        val url = StreamingService.SPOTIFY.searchUrl("x".repeat(100), "y".repeat(200))
        assertTrue(StreamingService.isAllowedUrl(url))
        for (service in StreamingService.entries) {
            assertTrue(StreamingService.isAllowedUrl(service.searchUrl("x".repeat(100), "y".repeat(200))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StreamingService.SPOTIFY.searchUrl("x".repeat(100), "y".repeat(201))
        }
    }

    @Test
    fun bundledSetIsExactlyThreeHttpsOrigins() {
        assertEquals(
            setOf("open.spotify.com", "music.apple.com", "music.youtube.com"),
            StreamingService.entries.map { it.host }.toSet(),
        )
        assertEquals(3, StreamingService.entries.size)
    }
}
