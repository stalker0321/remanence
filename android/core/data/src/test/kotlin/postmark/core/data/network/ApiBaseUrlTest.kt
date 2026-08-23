package postmark.core.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApiBaseUrlTest {
    @Test
    fun parsesHttpsRootAndPath() {
        val root = ApiBaseUrl.parse("https://api.example.com/")
        assertEquals("https://api.example.com/", root.httpUrl.toString())
        val nested = ApiBaseUrl.parse("https://api.example.com/v1/")
        assertEquals("https://api.example.com/v1/", nested.httpUrl.toString())
    }

    @Test
    fun parsesLoopbackHttpWithPorts() {
        assertEquals(
            "http://localhost:8000/",
            ApiBaseUrl.parse("http://localhost:8000/").httpUrl.toString(),
        )
        assertEquals(
            "http://127.0.0.1:8000/",
            ApiBaseUrl.parse("http://127.0.0.1:8000/").httpUrl.toString(),
        )
        assertEquals(
            "http://[::1]:8000/",
            ApiBaseUrl.parse("http://[::1]:8000/").httpUrl.toString(),
        )
    }

    @Test
    fun parsesLoopbackHttpWithoutPorts() {
        assertEquals("http://localhost/", ApiBaseUrl.parse("http://localhost/").httpUrl.toString())
        assertEquals("http://127.0.0.1/", ApiBaseUrl.parse("http://127.0.0.1/").httpUrl.toString())
        assertEquals("http://[::1]/", ApiBaseUrl.parse("http://[::1]/").httpUrl.toString())
    }

    @Test
    fun resolvesHealthz() {
        val root = ApiBaseUrl.parse("https://api.example.com/")
        assertEquals("https://api.example.com/healthz", root.resolve("healthz").toString())
        val nested = ApiBaseUrl.parse("https://api.example.com/v1/")
        assertEquals("https://api.example.com/v1/healthz", nested.resolve("healthz").toString())
    }

    @Test
    fun rejectsWhitespace() {
        assertFailsWith<IllegalArgumentException> { ApiBaseUrl.parse(" https://api.example.com/") }
        assertFailsWith<IllegalArgumentException> { ApiBaseUrl.parse("https://api.example.com/ ") }
        assertFailsWith<IllegalArgumentException> { ApiBaseUrl.parse(" https://api.example.com/ ") }
    }

    @Test
    fun rejectsMissingTrailingSlash() {
        assertFailsWith<IllegalArgumentException> { ApiBaseUrl.parse("https://api.example.com") }
    }

    @Test
    fun rejectsNonHttpsRemoteSchemesAndRemoteHttp() {
        assertFailsWith<IllegalArgumentException> { ApiBaseUrl.parse("ftp://api.example.com/") }
        assertFailsWith<IllegalArgumentException> { ApiBaseUrl.parse("ws://api.example.com/") }
        assertFailsWith<IllegalArgumentException> { ApiBaseUrl.parse("http://api.example.com/") }
        assertFailsWith<IllegalArgumentException> { ApiBaseUrl.parse("http://192.168.1.8/") }
    }

    @Test
    fun rejectsDeceptiveLocalhostSubdomain() {
        assertFailsWith<IllegalArgumentException> {
            ApiBaseUrl.parse("http://localhost.attacker.example/")
        }
    }

    @Test
    fun rejectsUserinfoQueryFragmentMissingHostAndMalformed() {
        assertFailsWith<IllegalArgumentException> { ApiBaseUrl.parse("https://user@api.example.com/") }
        assertFailsWith<IllegalArgumentException> {
            ApiBaseUrl.parse("https://user:pass@api.example.com/")
        }
        assertFailsWith<IllegalArgumentException> { ApiBaseUrl.parse("https://api.example.com/?q=1/") }
        assertFailsWith<IllegalArgumentException> { ApiBaseUrl.parse("https://api.example.com/#frag/") }
        assertFailsWith<IllegalArgumentException> { ApiBaseUrl.parse("https:///path/") }
        assertFailsWith<IllegalArgumentException> { ApiBaseUrl.parse("https:///") }
        assertFailsWith<IllegalArgumentException> { ApiBaseUrl.parse("not a url/") }
    }

    @Test
    fun resolveRejectsAbsoluteAndLeadingSlashPaths() {
        val base = ApiBaseUrl.parse("https://api.example.com/")
        assertFailsWith<IllegalArgumentException> { base.resolve("/healthz") }
        assertFailsWith<IllegalArgumentException> { base.resolve("https://evil.example/healthz") }
    }
}
