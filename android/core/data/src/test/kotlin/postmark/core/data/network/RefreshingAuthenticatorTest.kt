package postmark.core.data.network

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import okhttp3.coroutines.executeAsync
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RefreshingAuthenticatorTest {

    private companion object {
        const val REFRESH_RESPONSE = """
            {
              "session_id": "0198f0a0-0000-7000-8000-00000000se01",
              "access_token": "pm_at_new",
              "access_expires_at": "2026-08-23T04:15:00Z",
              "refresh_token": "pm_rt_new",
              "refresh_expires_at": "2026-09-22T04:00:00Z"
            }
        """
    }

    private class PathDispatcher : Dispatcher() {

        val refreshCount = AtomicInteger()
        val protectedCount = AtomicInteger()

        override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            return when {
                path == "/v1/auth/refresh" -> {
                    refreshCount.incrementAndGet()
                    MockResponse.Builder()
                        .code(200)
                        .setHeader("Content-Type", "application/json")
                        .body(REFRESH_RESPONSE)
                        .build()
                }
                else -> {
                    protectedCount.incrementAndGet()
                    MockResponse.Builder().code(401).build()
                }
            }
        }
    }

    private fun wiredClient(server: MockWebServer, tokens: AuthTokenHolder): OkHttpClient {
        val repository = AuthRepository.create(ApiBaseUrl.parse(server.url("/").toString()))
        return RefreshingAuthenticator.attach(OkHttpClient.Builder(), repository, tokens).build()
    }

    private fun protectedRequest(server: MockWebServer, token: String): Request =
        Request.Builder()
            .url(server.url("/v1/data"))
            .header("Authorization", RefreshingAuthenticator.BEARER_PREFIX + token)
            .get()
            .build()

    @Test
    fun single401TriggersExactlyOneRefreshThenRetriesWithNewToken(): Unit = runBlocking {
        val server = MockWebServer()
        val protectedHits = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                when (request.url.encodedPath) {
                    "/v1/auth/refresh" -> MockResponse.Builder()
                        .code(200)
                        .setHeader("Content-Type", "application/json")
                        .body(REFRESH_RESPONSE)
                        .build()
                    else -> if (protectedHits.incrementAndGet() == 1) {
                        MockResponse.Builder().code(401).build()
                    } else {
                        MockResponse.Builder().code(200).body("ok").build()
                    }
                }
        }
        server.start()
        try {
            val tokens = AuthTokenHolder("pm_at_old", "pm_rt_old")
            val client = wiredClient(server, tokens)
            val response = client.newCall(protectedRequest(server, "pm_at_old")).executeAsync()
            response.use { assertEquals(200, it.code) }

            assertEquals(2, protectedHits.get()) // stale attempt + retried attempt
            assertEquals("pm_at_new", tokens.accessToken)
        } finally {
            server.close()
        }
    }

    @Test
    fun concurrent401sSerializeIntoOneRefreshRoundTrip(): Unit = runBlocking {
        val server = MockWebServer()
        val dispatcher = PathDispatcher()
        server.dispatcher = dispatcher
        server.start()
        try {
            val tokens = AuthTokenHolder("pm_at_stale", "pm_rt_live")
            val client = wiredClient(server, tokens)

            val workers = (1..6).map {
                async {
                    client.newCall(protectedRequest(server, "pm_at_stale")).executeAsync().use { it.code }
                }
            }
            val codes = workers.awaitAll()

            // Every worker ultimately receives the final 401 (dispatcher always rejects /v1/data),
            // but the burst collapses into a single serialized refresh.
            assertEquals(List(6) { 401 }, codes)
            assertEquals(1, dispatcher.refreshCount.get())
            assertEquals("pm_at_new", tokens.accessToken)
            assertEquals("pm_rt_new", tokens.refreshToken)
        } finally {
            server.close()
        }
    }

    @Test
    fun failedRefreshClearsSessionAndPropagates401(): Unit = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                if (request.url.encodedPath == "/v1/auth/refresh") {
                    MockResponse.Builder().code(409).setHeader("Content-Type", "application/json").body("""{"code":"SESSION_REPLAYED"}""").build()
                } else {
                    MockResponse.Builder().code(401).build()
                }
        }
        server.start()
        try {
            val tokens = AuthTokenHolder("pm_at_stale", "pm_rt_replayed")
            val repository = AuthRepository.create(ApiBaseUrl.parse(server.url("/").toString()))
            val client = RefreshingAuthenticator.attach(OkHttpClient.Builder(), repository, tokens).build()

            val response = client.newCall(protectedRequest(server, "pm_at_stale")).executeAsync()
            response.use { assertEquals(401, it.code) }
            assertNull(tokens.accessToken)
            assertNull(tokens.refreshToken)
        } finally {
            server.close()
        }
    }
}
