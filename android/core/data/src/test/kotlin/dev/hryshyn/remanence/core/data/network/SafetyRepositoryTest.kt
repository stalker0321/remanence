package dev.hryshyn.remanence.core.data.network

import dev.hryshyn.remanence.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

class SafetyRepositoryTest {
    private val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a001")

    private fun coordinator(server: MockWebServer): SessionRefreshCoordinator {
        val tokens = AuthTokenHolder("safety-fixture-access", "safety-fixture-refresh")
        return SessionRefreshCoordinator(
            bareAuthRepository = AuthRepository.create(ApiBaseUrl.parse(server.url("/").toString())),
            tokens = tokens,
            refreshTokenReader = RefreshTokenReader { BoundRefreshCredential(owner, "safety-fixture-refresh") },
            rotationSink = object : SessionRotationSink {
                override fun rotate(accessToken: String, refreshToken: String, ownerUserId: UserId) {
                    tokens.updateTokens(accessToken, refreshToken)
                }
                override fun clear() { tokens.clearSession() }
            },
        ).also { it.install(owner) }
    }

    private fun json(body: String, status: Int = 200) = MockResponse.Builder()
        .code(status).setHeader("Content-Type", "application/json").body(body).build()

    @Test fun updatesUseAuthenticatedOwnerAndOnlyContractFields() = runTest {
        MockWebServer().use { server ->
            server.start()
            val coordinator = coordinator(server)
            val repository = SafetyRepository(
                RefreshingAuthenticator.attach(OkHttpClient.Builder(), coordinator).build(),
                ApiBaseUrl.parse(server.url("/").toString()), SessionRequestLeaseProvider(coordinator),
            )
            server.enqueue(json("""{"status":"ACCEPTED"}"""))
            assertTrue(repository.acceptTerms())
            val terms = server.takeRequest()
            assertEquals("/v1/me/terms", terms.url.encodedPath)
            assertEquals("Bearer safety-fixture-access", terms.headers["Authorization"])
            assertEquals("""{"version":"2026-10-11"}""", terms.body!!.utf8())
            server.enqueue(json("""{"status":"RECEIVED"}""", 201))
            assertTrue(repository.report("friend", "SPAM"))
            assertEquals("""{"handle":"friend","reason":"SPAM"}""", server.takeRequest().body!!.utf8())
        }
    }

    @Test fun malformedOversizedAndUnconfirmedResponsesFailClosed() = runTest {
        MockWebServer().use { server ->
            server.start()
            val coordinator = coordinator(server)
            val repository = SafetyRepository(OkHttpClient(), ApiBaseUrl.parse(server.url("/").toString()), SessionRequestLeaseProvider(coordinator))
            for (body in listOf("not-json", "x".repeat(32769), """{"status":"BLOCKED","unexpected":"value"}""", """{"status":"RECEIVED"}""")) {
                server.enqueue(json(body))
                assertFalse(repository.block("friend"))
            }
            for (body in listOf("""{"terms_version":"unknown","terms_accepted":true,"blocked_handles":[]}""",
                """{"terms_version":"2026-10-11","terms_accepted":true,"blocked_handles":["INVALID"]}""")) {
                server.enqueue(json(body))
                assertNull(repository.state())
            }
        }
    }

    @Test fun invalidatedSessionCannotReadOrMutateSafety() = runTest {
        MockWebServer().use { server ->
            server.start()
            val coordinator = coordinator(server)
            val repository = SafetyRepository(OkHttpClient(), ApiBaseUrl.parse(server.url("/").toString()), SessionRequestLeaseProvider(coordinator))
            coordinator.invalidate()
            assertNull(repository.state())
            assertFalse(repository.block("friend"))
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun ownerFenceIsRecheckedAfterReadingResponseBody() = runTest {
        MockWebServer().use { server ->
            server.start()
            val coordinator = coordinator(server)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val original = response.body
                val wrapped = object : ResponseBody() {
                    private val fencedSource = object : ForwardingSource(original.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            coordinator.invalidate()
                            return super.read(sink, byteCount)
                        }
                    }.buffer()
                    override fun contentType() = original.contentType()
                    override fun contentLength() = original.contentLength()
                    override fun source() = fencedSource
                }
                response.newBuilder().body(wrapped).build()
            }.build()
            val repository = SafetyRepository(client, ApiBaseUrl.parse(server.url("/").toString()), SessionRequestLeaseProvider(coordinator))
            server.enqueue(json("""{"terms_version":"2026-10-11","terms_accepted":true,"blocked_handles":[]}"""))
            assertNull(repository.state())
            coordinator.install(owner)
            server.enqueue(json("""{"status":"BLOCKED"}"""))
            assertFalse(repository.block("friend"))
        }
    }
}
