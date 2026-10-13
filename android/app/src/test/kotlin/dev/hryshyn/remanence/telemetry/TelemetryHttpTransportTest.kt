package dev.hryshyn.remanence.telemetry

import dev.hryshyn.remanence.core.data.network.ApiBaseUrl
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Authenticator
import okhttp3.CookieJar

class TelemetryHttpTransportTest {
    private fun withServer(block: (MockWebServer) -> Unit) {
        MockWebServer().use { server -> server.start(InetAddress.getLoopbackAddress(), 0); block(server) }
    }
    private fun transport(server: MockWebServer) = TelemetryHttpTransport(ApiBaseUrl.parse(server.url("/onboarding-test/").toString()))
    private fun awaitCleared(bytes: ByteArray) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (bytes.any { it != 0.toByte() } && System.nanoTime() < deadline) Thread.yield()
        assertTrue("Completed/dropped requests must release buffer", bytes.all { it == 0.toByte() })
    }

    @Test fun dedicatedPostPreservesPrefixAndCarriesNoCredentialsIdentifiersOrGenericHeaders() = withServer { server ->
        server.enqueue(MockResponse.Builder().code(204).build())
        val bytes = "{\"schema_version\":1}".encodeToByteArray()
        transport(server).send(bytes)
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/onboarding-test/v1/telemetry/remanence", request.target)
        assertEquals("{\"schema_version\":1}", request.body!!.utf8())
        for (name in listOf("Authorization", "Cookie", "X-Request-ID", "X-Device-ID", "X-Account-ID", "Proxy-Authorization")) {
            assertNull(name, request.headers[name])
        }
        awaitCleared(bytes)
    }

    @Test fun redirectAndSetCookieAreNotFollowedOrStoredAcrossReports() = withServer { server ->
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/private-destination")
            .addHeader("Set-Cookie", "user=private; Path=/").build())
        val client = transport(server)
        val first = "{}".encodeToByteArray(); client.send(first)
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); awaitCleared(first)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse.Builder().code(204).build())
        val second = "{}".encodeToByteArray(); client.send(second)
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertNull(request.headers["Cookie"]); awaitCleared(second)
        assertEquals(2, server.requestCount)
    }

    @Test fun unauthenticatedFailureIsDroppedWithoutAuthenticatorOrRetry() = withServer { server ->
        server.enqueue(MockResponse.Builder().code(401).addHeader("WWW-Authenticate", "Basic realm=private").build())
        val bytes = "{}".encodeToByteArray(); transport(server).send(bytes)
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); awaitCleared(bytes)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationClearsBufferAndDoesNotCreateAnotherRequest() = withServer { server ->
        server.enqueue(MockResponse.Builder().headersDelay(2, TimeUnit.SECONDS).code(204).build())
        val bytes = "{}".encodeToByteArray(); val pending = transport(server).send(bytes)
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); pending.cancel(); awaitCleared(bytes)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun offlineConnectionDropsAndClearsWithoutThrowingIntoUi() {
        val server = MockWebServer(); server.start(InetAddress.getLoopbackAddress(), 0)
        val client = transport(server); server.close()
        val bytes = "{}".encodeToByteArray(); client.send(bytes); awaitCleared(bytes)
    }

    @Test fun oversizedRequestIsRefusedBeforeNetworking() = withServer { server ->
        try { transport(server).send(ByteArray(32769)); fail("must reject") } catch (_: IllegalArgumentException) { }
        assertEquals(0, server.requestCount)
    }

    @Test fun dedicatedClientHasNoSharedStackHooksRetriesOrLongUnboundedCalls() {
        val transport = TelemetryHttpTransport(ApiBaseUrl.parse("https://example.org/onboarding-test/"))
        val field = TelemetryHttpTransport::class.java.getDeclaredField("client").apply { isAccessible = true }
        val client = field.get(transport) as OkHttpClient
        assertEquals(CookieJar.NO_COOKIES, client.cookieJar)
        assertEquals(Authenticator.NONE, client.authenticator); assertEquals(Authenticator.NONE, client.proxyAuthenticator)
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects); assertFalse(client.retryOnConnectionFailure)
        assertTrue(client.interceptors.isEmpty()); assertTrue(client.networkInterceptors.isEmpty()); assertNull(client.cache)
        assertEquals(10000, client.callTimeoutMillis); assertEquals(5000, client.connectTimeoutMillis); assertEquals(5000, client.readTimeoutMillis)
    }
}
