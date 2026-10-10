package dev.hryshyn.remanence.core.data.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Test
import kotlin.test.*

class RecoveryRepositoryTest {
    @Test fun boundedChunkedResponseAndExplicitBearer(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json").chunkedBody("{\"stored\":true}", 3).build())
            val api = RecoveryRepository(ApiBaseUrl.parse(server.url("/").toString()))
            assertTrue(api.request("record", JsonObject(emptyMap()), "test_token")["stored"]!!.jsonPrimitive.boolean)
            val sent = server.takeRequest()
            assertEquals("POST", sent.method); assertEquals("/v1/recovery/record", sent.url.encodedPath)
            assertEquals("Bearer test_token", sent.headers["Authorization"])
        } finally { server.close() }
    }
    @Test fun oversizedResponseAndWrongMimeFailClosed(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val api = RecoveryRepository(ApiBaseUrl.parse(server.url("/").toString()))
            server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json").body(" ".repeat(100 * 1024)).build())
            assertFails { api.request("record") }
            server.enqueue(MockResponse.Builder().setHeader("Content-Type", "text/html").body("{}").build())
            assertFails { api.request("record") }
        } finally { server.close() }
    }
    @Test fun noRedirectOrCallerSelectedEndpoint(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val api = RecoveryRepository(ApiBaseUrl.parse(server.url("/").toString()))
            server.enqueue(MockResponse.Builder().code(302).setHeader("Location", server.url("/other")).build())
            assertFails { api.request("record") }; assertEquals(1, server.requestCount)
            assertFails { api.request("../../auth/login") }
        } finally { server.close() }
    }
    @Test fun logoutRetiresLocalRecoveryInstallationLease() {
        val tokens = AuthTokenHolder()
        val sink = object : SessionRotationSink {
            override fun rotate(accessToken: String, refreshToken: String, ownerUserId: dev.hryshyn.remanence.core.model.UserId) = Unit
            override fun clear() = Unit
        }
        val coordinator = SessionRefreshCoordinator(AuthRepository.create(ApiBaseUrl.parse("http://localhost:8000/")), tokens, RefreshTokenReader { null }, sink)
        val lease = coordinator.acquireAccountLease(); var installs = 0
        assertTrue(coordinator.commitLocalRecovery(lease) { installs++ })
        coordinator.invalidate()
        assertFalse(coordinator.commitLocalRecovery(lease) { installs++ })
        assertEquals(1, installs)
    }
}
