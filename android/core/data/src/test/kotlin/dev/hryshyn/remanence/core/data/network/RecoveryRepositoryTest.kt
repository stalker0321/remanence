package dev.hryshyn.remanence.core.data.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
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

    private val ceremonyOwner = dev.hryshyn.remanence.core.model.UserId.parseRest("0198f0a0-0000-7000-8000-00000000a001")

    private fun refreshBody(access: String, refresh: String) =
        """{"session_id":"0198f0a0-0000-7000-8000-00000000se01","access_token":"$access","access_expires_at":"2026-08-23T04:15:00Z","refresh_token":"$refresh","refresh_expires_at":"2026-09-22T04:00:00Z"}"""

    private fun jsonReply(body: String, status: Int = 200) = MockResponse.Builder()
        .code(status).setHeader("Content-Type", "application/json").body(body).build()

    /** Signed-in ceremony fixture: installed owner, live lease, shared refreshing transport. */
    private fun ceremony(server: MockWebServer): Triple<RecoveryRepository, SessionRefreshCoordinator, AuthTokenHolder> {
        val tokens = AuthTokenHolder("rec-at-old", "rec-rt-live")
        val sink = object : SessionRotationSink {
            override fun rotate(accessToken: String, refreshToken: String, ownerUserId: dev.hryshyn.remanence.core.model.UserId) {
                tokens.updateTokens(accessToken, refreshToken)
            }
            override fun clear() { tokens.clearSession() }
        }
        val coordinator = SessionRefreshCoordinator(
            bareAuthRepository = AuthRepository.create(ApiBaseUrl.parse(server.url("/").toString())),
            tokens = tokens,
            refreshTokenReader = RefreshTokenReader {
                tokens.refreshToken?.let { BoundRefreshCredential(ceremonyOwner, it) }
            },
            rotationSink = sink,
        )
        coordinator.install(ceremonyOwner)
        val leases = SessionRequestLeaseProvider(coordinator)
        val client = RefreshingAuthenticator.attach(OkHttpClient.Builder(), coordinator).build()
        return Triple(RecoveryRepository(ApiBaseUrl.parse(server.url("/").toString()), client, leases), coordinator, tokens)
    }

    @Test fun signedCeremony401RefreshesOnceThenReusesCurrentBearer(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val (api, coordinator, tokens) = ceremony(server)
            val lease = assertNotNull(SessionRequestLeaseProvider(coordinator).capture())
            // First ceremony request: stale bearer -> ONE shared refresh -> retry wins.
            server.enqueue(MockResponse.Builder().code(401).build())
            server.enqueue(jsonReply(refreshBody("rec-at-new", "rec-rt-new")))
            server.enqueue(jsonReply("""{"state":"NOT_STARTED"}"""))
            val status = api.requestAuthenticated(lease, "status")
            assertEquals("NOT_STARTED", status["state"]!!.jsonPrimitive.content)
            val attempt = server.takeRequest()
            assertEquals("/v1/recovery/status", attempt.url.encodedPath)
            assertEquals("Bearer rec-at-old", attempt.headers["Authorization"])
            assertEquals("/v1/auth/refresh", server.takeRequest().url.encodedPath)
            val retry = server.takeRequest()
            assertEquals("/v1/recovery/status", retry.url.encodedPath)
            assertEquals("Bearer rec-at-new", retry.headers["Authorization"])
            assertEquals("rec-at-new", tokens.accessToken)
            // Second request in the SAME ceremony sends the CURRENT bearer
            // directly: no stale resend, no second refresh round trip.
            server.enqueue(jsonReply("""{"state":"STORED","credential_id":"cred-1"}"""))
            val reread = api.requestAuthenticated(lease, "record")
            assertEquals("cred-1", reread["credential_id"]!!.jsonPrimitive.content)
            val followUp = server.takeRequest()
            assertEquals("/v1/recovery/record", followUp.url.encodedPath)
            assertEquals("Bearer rec-at-new", followUp.headers["Authorization"])
            assertEquals(4, server.requestCount)
        } finally { server.close() }
    }

    @Test fun replacementDuringSuspensionRefusesBeforeNetwork(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val (api, coordinator, _) = ceremony(server)
            val lease = assertNotNull(SessionRequestLeaseProvider(coordinator).capture())
            coordinator.closeAdmission()
            assertFailsWith<RequestLeaseRejectedException> {
                api.requestAuthenticated(lease, "status")
            }
            assertEquals(0, server.requestCount)
        } finally { server.close() }
    }

    @Test fun logoutDuringSuspensionRefusesBeforeNetwork(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val (api, coordinator, _) = ceremony(server)
            val lease = assertNotNull(SessionRequestLeaseProvider(coordinator).capture())
            coordinator.invalidate()
            assertFailsWith<RequestLeaseRejectedException> {
                api.requestAuthenticated(lease, "status")
            }
            assertEquals(0, server.requestCount)
        } finally { server.close() }
    }

    @Test fun non401AndOversizedNeverReplay(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val (api, coordinator, _) = ceremony(server)
            val lease = assertNotNull(SessionRequestLeaseProvider(coordinator).capture())
            server.enqueue(jsonReply("""{"code":"INTERNAL_ERROR"}""", 500))
            assertFails { api.requestAuthenticated(lease, "status") }
            server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json").body(" ".repeat(100 * 1024)).build())
            assertFails { api.requestAuthenticated(lease, "status") }
            assertEquals(2, server.requestCount)
        } finally { server.close() }
    }

    @Test fun anonymousLoginNeedsNoSessionAndUnwiredRefuses(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            // Bare shape exactly as the anonymous passkey login uses it:
            // no coordinator, no installed owner, no tokens anywhere.
            val bare = RecoveryRepository(ApiBaseUrl.parse(server.url("/").toString()))
            server.enqueue(jsonReply("""{"flow":"f","options":{},"nonce":"n"}"""))
            val options = bare.request("login/options", JsonObject(emptyMap()))
            assertEquals("f", options["flow"]!!.jsonPrimitive.content)
            val sent = server.takeRequest()
            assertEquals("/v1/recovery/login/options", sent.url.encodedPath)
            assertNull(sent.headers["Authorization"])
            // The authenticated entry fails closed when unwired.
            assertFails {
                bare.requestAuthenticated(SessionRequestLease(ceremonyOwner, 0), "status")
            }
            assertEquals(1, server.requestCount)
        } finally { server.close() }
    }
}
