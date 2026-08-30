package dev.hryshyn.remanence.core.data.network

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import org.junit.Test

class SessionRefreshCoordinatorTest {

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

    private class RecordingSink : SessionRotationSink {
        val rotations = CopyOnWriteArrayList<Pair<String, String>>()
        val clears = AtomicInteger()

        override fun rotate(accessToken: String, refreshToken: String) {
            rotations += accessToken to refreshToken
        }

        override fun clear() {
            clears.incrementAndGet()
        }
    }

    private fun coordinator(
        server: MockWebServer,
        tokens: AuthTokenHolder,
        sink: RecordingSink,
        stored: AtomicReference<String?>,
    ): SessionRefreshCoordinator = SessionRefreshCoordinator(
        bareAuthRepository = AuthRepository.create(ApiBaseUrl.parse(server.url("/").toString())),
        tokens = tokens,
        refreshTokenReader = RefreshTokenReader { stored.get() },
        rotationSink = object : SessionRotationSink {
            override fun rotate(accessToken: String, refreshToken: String) {
                stored.set(refreshToken)
                sink.rotate(accessToken, refreshToken)
            }

            override fun clear() {
                stored.set(null)
                sink.clear()
            }
        },
    )

    @Test
    fun concurrentBootstrapAndAuthenticatorShareOneRefreshRoundTrip(): Unit = runBlocking {
        val server = MockWebServer()
        val refreshCount = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                if (request.url.encodedPath != "/v1/auth/refresh") {
                    return MockResponse.Builder().code(401).build()
                }
                refreshCount.incrementAndGet()
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                return MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(REFRESH_RESPONSE)
                    .build()
            }
        }
        server.start()
        try {
            val tokens = AuthTokenHolder("pm_at_old", "pm_rt_old")
            val stored = AtomicReference<String?>("pm_rt_old")
            val sink = RecordingSink()
            val coordinator = coordinator(server, tokens, sink, stored)
            val client = RefreshingAuthenticator.attach(OkHttpClient.Builder(), coordinator).build()

            val bootstrap = async(Dispatchers.IO) { coordinator.refreshForBootstrap() }
            val authenticator = async(Dispatchers.IO) {
                client.newCall(
                    Request.Builder()
                        .url(server.url("/v1/data"))
                        .header("Authorization", RefreshingAuthenticator.BEARER_PREFIX + "pm_at_old")
                        .get()
                        .build(),
                ).executeAsync().use { it.code }
            }

            assertTrue(entered.await(2, TimeUnit.SECONDS))
            release.countDown()
            val outcomes = awaitAll(bootstrap, authenticator)

            assertEquals(1, refreshCount.get())
            assertEquals(1, sink.rotations.size)
            assertEquals("pm_at_new", tokens.accessToken)
            assertEquals("pm_rt_new", stored.get())
            val bootstrapOutcome = outcomes[0] as CoordinatedRefreshOutcome
            assertTrue(
                bootstrapOutcome is CoordinatedRefreshOutcome.Rotated ||
                    bootstrapOutcome is CoordinatedRefreshOutcome.Reused,
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun bootstrapWaiterReusesTheWinningRotation(): Unit = runBlocking {
        val server = MockWebServer()
        val refreshCount = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                refreshCount.incrementAndGet()
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                return MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(REFRESH_RESPONSE)
                    .build()
            }
        }
        server.start()
        try {
            val tokens = AuthTokenHolder(null, "pm_rt_old")
            val stored = AtomicReference<String?>("pm_rt_old")
            val sink = RecordingSink()
            val coordinator = coordinator(server, tokens, sink, stored)

            val first = async(Dispatchers.IO) { coordinator.refreshForBootstrap() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val second = async(Dispatchers.IO) { coordinator.refreshForBootstrap() }
            release.countDown()

            val outcomes = awaitAll(first, second)
            assertEquals(1, refreshCount.get())
            assertEquals(1, sink.rotations.size)
            assertTrue(outcomes.any { it is CoordinatedRefreshOutcome.Rotated })
            assertTrue(outcomes.any { it is CoordinatedRefreshOutcome.Reused })
            assertEquals("pm_at_new", (outcomes[1] as? CoordinatedRefreshOutcome.Reused)?.accessToken ?: tokens.accessToken)
        } finally {
            server.close()
        }
    }

    @Test
    fun networkFailureDoesNotClearStoredToken(): Unit = runBlocking {
        val server = MockWebServer()
        server.start()
        val base = server.url("/").toString()
        server.close()
        val tokens = AuthTokenHolder(null, "pm_rt_old")
        val stored = AtomicReference<String?>("pm_rt_old")
        val sink = RecordingSink()
        val coordinator = SessionRefreshCoordinator(
            bareAuthRepository = AuthRepository.create(ApiBaseUrl.parse(base)),
            tokens = tokens,
            refreshTokenReader = RefreshTokenReader { stored.get() },
            rotationSink = object : SessionRotationSink {
                override fun rotate(accessToken: String, refreshToken: String) {
                    stored.set(refreshToken)
                    sink.rotate(accessToken, refreshToken)
                }

                override fun clear() {
                    stored.set(null)
                    sink.clear()
                }
            },
        )

        assertEquals(CoordinatedRefreshOutcome.Unreachable, coordinator.refreshForBootstrap())
        assertEquals("pm_rt_old", stored.get())
        assertEquals(0, sink.clears.get())
        assertEquals("pm_rt_old", tokens.refreshToken)
    }

    @Test
    fun rejectedReplayClearsOnlyTheOwningLineage(): Unit = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                MockResponse.Builder()
                    .code(409)
                    .setHeader("Content-Type", "application/json")
                    .body("""{"code":"SESSION_REPLAYED"}""")
                    .build()
        }
        server.start()
        try {
            val tokens = AuthTokenHolder(null, "pm_rt_old")
            val stored = AtomicReference<String?>("pm_rt_old")
            val sink = RecordingSink()
            val coordinator = coordinator(server, tokens, sink, stored)

            assertEquals(CoordinatedRefreshOutcome.Rejected, coordinator.refreshForBootstrap())
            assertNull(stored.get())
            assertNull(tokens.refreshToken)
            assertEquals(1, sink.clears.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun serverErrorDoesNotClearOrActivateAsConnectivityOnly(): Unit = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                MockResponse.Builder().code(500).build()
        }
        server.start()
        try {
            val tokens = AuthTokenHolder(null, "pm_rt_old")
            val stored = AtomicReference<String?>("pm_rt_old")
            val sink = RecordingSink()
            val coordinator = coordinator(server, tokens, sink, stored)

            assertEquals(CoordinatedRefreshOutcome.Unavailable, coordinator.refreshForBootstrap())
            assertEquals("pm_rt_old", stored.get())
            assertEquals(0, sink.clears.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun corruptStoredTokenClearsAndReportsNoToken(): Unit = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val tokens = AuthTokenHolder("pm_at_old", "pm_rt_old")
            val sink = RecordingSink()
            val coordinator = SessionRefreshCoordinator(
                bareAuthRepository = AuthRepository.create(ApiBaseUrl.parse(server.url("/").toString())),
                tokens = tokens,
                refreshTokenReader = RefreshTokenReader { error("sealed token corrupt") },
                rotationSink = sink,
            )

            assertEquals(false, coordinator.hasStoredToken())
            assertEquals(CoordinatedRefreshOutcome.NoToken, coordinator.refreshForBootstrap())
            assertNull(tokens.accessToken)
            assertNull(tokens.refreshToken)
            assertTrue(sink.clears.get() >= 1)
        } finally {
            server.close()
        }
    }

    @Test
    fun inFlightRefreshDoesNotOverwriteAReplacementLoginToken(): Unit = runBlocking {
        val server = MockWebServer()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                return MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(REFRESH_RESPONSE)
                    .build()
            }
        }
        server.start()
        try {
            val tokens = AuthTokenHolder(null, "pm_rt_old")
            val stored = AtomicReference<String?>("pm_rt_old")
            val sink = RecordingSink()
            val coordinator = coordinator(server, tokens, sink, stored)

            val inFlight = async(Dispatchers.IO) { coordinator.refreshForBootstrap() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            coordinator.invalidate()
            stored.set("pm_rt_login")
            tokens.updateTokens("pm_at_login", "pm_rt_login")
            release.countDown()

            assertEquals(CoordinatedRefreshOutcome.Invalidated, inFlight.await())
            assertEquals("pm_rt_login", stored.get())
            assertEquals("pm_at_login", tokens.accessToken)
            assertEquals("pm_rt_login", tokens.refreshToken)
            assertEquals(0, sink.rotations.size)
            assertEquals(0, sink.clears.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun publicationFenceBlocksInvalidateUntilStaleRotateFinishesThenReplacementWins(): Unit =
        runBlocking {
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                    MockResponse.Builder()
                        .code(200)
                        .setHeader("Content-Type", "application/json")
                        .body(REFRESH_RESPONSE)
                        .build()
            }
            server.start()
            try {
                val tokens = AuthTokenHolder(null, "pm_rt_old")
                val stored = AtomicReference<String?>("pm_rt_old")
                val sink = RecordingSink()
                val coordinator = coordinator(server, tokens, sink, stored)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val events = CopyOnWriteArrayList<String>()
                coordinator.onPublicationFence = {
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                    events += "fence"
                }

                val inFlight = async(Dispatchers.IO) { coordinator.refreshForBootstrap() }
                assertTrue(entered.await(2, TimeUnit.SECONDS))

                val replacement = Thread {
                    coordinator.invalidate()
                    events += "invalidated"
                    stored.set("pm_rt_login")
                    tokens.updateTokens("pm_at_login", "pm_rt_login")
                    events += "replaced"
                }
                replacement.start()
                assertTrue(waitUntil { isParkedOnLock(replacement) })
                events += "releasing"
                release.countDown()
                inFlight.await()
                replacement.join(2_000)
                assertTrue(!replacement.isAlive)

                assertEquals("pm_rt_login", stored.get())
                assertEquals("pm_at_login", tokens.accessToken)
                assertEquals("pm_rt_login", tokens.refreshToken)
                assertTrue(events.indexOf("invalidated") > events.indexOf("fence"))
                assertTrue(events.indexOf("replaced") > events.indexOf("invalidated"))
            } finally {
                server.close()
            }
        }

    @Test
    fun publicationFenceBlocksLogoutClearUntilInFlightPublicationFinishes(): Unit = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(REFRESH_RESPONSE)
                    .build()
        }
        server.start()
        try {
            val tokens = AuthTokenHolder(null, "pm_rt_old")
            val stored = AtomicReference<String?>("pm_rt_old")
            val sink = RecordingSink()
            val coordinator = coordinator(server, tokens, sink, stored)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val events = CopyOnWriteArrayList<String>()
            coordinator.onPublicationFence = {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                events += "fence"
            }

            val inFlight = async(Dispatchers.IO) { coordinator.refreshForBootstrap() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            val logout = Thread {
                coordinator.invalidate()
                events += "invalidated"
                stored.set(null)
                tokens.clearSession()
                events += "cleared"
            }
            logout.start()
            assertTrue(waitUntil { isParkedOnLock(logout) })
            release.countDown()
            inFlight.await()
            logout.join(2_000)
            assertTrue(!logout.isAlive)

            assertNull(stored.get())
            assertNull(tokens.accessToken)
            assertNull(tokens.refreshToken)
            assertTrue(events.indexOf("cleared") > events.indexOf("fence"))
            assertTrue(events.indexOf("invalidated") > events.indexOf("fence"))
        } finally {
            server.close()
        }
    }

    private fun isParkedOnLock(thread: Thread): Boolean =
        thread.state == Thread.State.WAITING ||
            thread.state == Thread.State.TIMED_WAITING ||
            thread.state == Thread.State.BLOCKED

    private fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
