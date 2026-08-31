package dev.hryshyn.remanence.core.data.network

import dev.hryshyn.remanence.core.model.UserId
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
import kotlinx.coroutines.coroutineScope
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
        val OWNER_A = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a001")
        val OWNER_B = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a002")
    }

    private fun bound(token: String, owner: UserId = OWNER_A) =
        BoundRefreshCredential(owner, token)

    private class RecordingSink : SessionRotationSink {
        val rotations = CopyOnWriteArrayList<Pair<String, String>>()
        val clears = AtomicInteger()

        override fun rotate(accessToken: String, refreshToken: String, ownerUserId: UserId) {
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
        stored: AtomicReference<BoundRefreshCredential?>,
    ): SessionRefreshCoordinator = SessionRefreshCoordinator(
        bareAuthRepository = AuthRepository.create(ApiBaseUrl.parse(server.url("/").toString())),
        tokens = tokens,
        refreshTokenReader = RefreshTokenReader { stored.get() },
        rotationSink = object : SessionRotationSink {
            override fun rotate(accessToken: String, refreshToken: String, ownerUserId: UserId) {
                stored.set(bound(refreshToken, ownerUserId))
                sink.rotate(accessToken, refreshToken, ownerUserId)
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
            val stored = AtomicReference(bound("pm_rt_old"))
            val sink = RecordingSink()
            val coordinator = coordinator(server, tokens, sink, stored)
            val client = RefreshingAuthenticator.attach(OkHttpClient.Builder(), coordinator).build()

            val bootstrap = async(Dispatchers.IO) { coordinator.refreshForBootstrap(OWNER_A) }
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
            assertEquals("pm_rt_new", stored.get()?.refreshToken)
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
            val stored = AtomicReference(bound("pm_rt_old"))
            val sink = RecordingSink()
            val coordinator = coordinator(server, tokens, sink, stored)

            val first = async(Dispatchers.IO) { coordinator.refreshForBootstrap(OWNER_A) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val second = async(Dispatchers.IO) { coordinator.refreshForBootstrap(OWNER_A) }
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
        val stored = AtomicReference(bound("pm_rt_old"))
        val sink = RecordingSink()
        val coordinator = SessionRefreshCoordinator(
            bareAuthRepository = AuthRepository.create(ApiBaseUrl.parse(base)),
            tokens = tokens,
            refreshTokenReader = RefreshTokenReader { stored.get() },
            rotationSink = object : SessionRotationSink {
                override fun rotate(accessToken: String, refreshToken: String, ownerUserId: UserId) {
                    stored.set(bound(refreshToken, ownerUserId))
                    sink.rotate(accessToken, refreshToken, ownerUserId)
                }

                override fun clear() {
                    stored.set(null)
                    sink.clear()
                }
            },
        )

        assertEquals(CoordinatedRefreshOutcome.Unreachable, coordinator.refreshForBootstrap(OWNER_A))
        assertEquals("pm_rt_old", stored.get()?.refreshToken)
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
            val stored = AtomicReference(bound("pm_rt_old"))
            val sink = RecordingSink()
            val coordinator = coordinator(server, tokens, sink, stored)

            assertEquals(CoordinatedRefreshOutcome.Rejected, coordinator.refreshForBootstrap(OWNER_A))
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
            val stored = AtomicReference(bound("pm_rt_old"))
            val sink = RecordingSink()
            val coordinator = coordinator(server, tokens, sink, stored)

            assertEquals(CoordinatedRefreshOutcome.Unavailable, coordinator.refreshForBootstrap(OWNER_A))
            assertEquals("pm_rt_old", stored.get()?.refreshToken)
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
            assertEquals(CoordinatedRefreshOutcome.NoToken, coordinator.refreshForBootstrap(OWNER_A))
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
            val stored = AtomicReference(bound("pm_rt_old"))
            val sink = RecordingSink()
            val coordinator = coordinator(server, tokens, sink, stored)

            val inFlight = async(Dispatchers.IO) { coordinator.refreshForBootstrap(OWNER_A) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            coordinator.invalidate()
            stored.set(bound("pm_rt_login"))
            tokens.updateTokens("pm_at_login", "pm_rt_login")
            release.countDown()

            assertEquals(CoordinatedRefreshOutcome.Invalidated, inFlight.await())
            assertEquals("pm_rt_login", stored.get()?.refreshToken)
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
                val stored = AtomicReference(bound("pm_rt_old"))
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

                val inFlight = async(Dispatchers.IO) { coordinator.refreshForBootstrap(OWNER_A) }
                assertTrue(entered.await(2, TimeUnit.SECONDS))

                val replacement = Thread {
                    coordinator.invalidate()
                    events += "invalidated"
                    stored.set(bound("pm_rt_login"))
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

                assertEquals("pm_rt_login", stored.get()?.refreshToken)
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
            val stored = AtomicReference(bound("pm_rt_old"))
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

            val inFlight = async(Dispatchers.IO) { coordinator.refreshForBootstrap(OWNER_A) }
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

    @Test
    fun pendingPostThenLogoutNetworkFailureDoesNotPublishUnreachable(): Unit = runBlocking {
        val server = MockWebServer()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val refreshCount = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                refreshCount.incrementAndGet()
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                throw java.io.IOException("unreachable")
            }
        }
        server.start()
        try {
            val tokens = AuthTokenHolder(null, "pm_rt_old")
            val stored = AtomicReference(bound("pm_rt_old"))
            val sink = RecordingSink()
            val coordinator = coordinator(server, tokens, sink, stored)

            val inFlight = async(Dispatchers.IO) { coordinator.refreshForBootstrap(OWNER_A) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            coordinator.invalidate()
            stored.set(null)
            tokens.clearSession()
            release.countDown()

            assertEquals(CoordinatedRefreshOutcome.Invalidated, inFlight.await())
            assertNull(stored.get())
            assertNull(tokens.refreshToken)
            assertEquals(0, sink.rotations.size)
        } finally {
            server.close()
        }
    }

    @Test
    fun refreshDoesNotStartAfterAccountBoundaryClose(): Unit = runBlocking {
        val server = MockWebServer()
        val refreshCount = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                refreshCount.incrementAndGet()
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
            val stored = AtomicReference(bound("pm_rt_old"))
            val sink = RecordingSink()
            val coordinator = coordinator(server, tokens, sink, stored)

            coordinator.invalidate()
            assertEquals(CoordinatedRefreshOutcome.Invalidated, coordinator.refreshForBootstrap(OWNER_A))
            assertEquals(0, refreshCount.get())
            assertEquals("pm_rt_old", stored.get()?.refreshToken)
        } finally {
            server.close()
        }
    }

    @Test
    fun replacementInstallReopensTheBoundRecord(): Unit = runBlocking {
        val server = MockWebServer()
        val seenRefresh = AtomicReference<String?>(null)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                seenRefresh.set(request.body?.utf8())
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
            val stored = AtomicReference(bound("pm_rt_old"))
            val sink = RecordingSink()
            val coordinator = coordinator(server, tokens, sink, stored)

            coordinator.invalidate()
            stored.set(bound("pm_rt_login"))
            tokens.updateTokens("pm_at_login", "pm_rt_login")
            coordinator.install(OWNER_A)

            val outcome = coordinator.refreshForBootstrap(OWNER_A)
            assertTrue(outcome is CoordinatedRefreshOutcome.Rotated)
            assertTrue(seenRefresh.get().orEmpty().contains("pm_rt_login"))
            assertEquals("pm_rt_new", stored.get()?.refreshToken)
            assertEquals("pm_at_new", tokens.accessToken)
        } finally {
            server.close()
        }
    }

    @Test
    fun logoutInvalidationRetiresLeaseSoPublishCannotReopen(): Unit = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val tokens = AuthTokenHolder("pm_at_a", "pm_rt_a")
            val stored = AtomicReference(bound("pm_rt_a"))
            val sink = RecordingSink()
            val coordinator = coordinator(server, tokens, sink, stored)
            val lease = coordinator.acquireAccountLease()
            coordinator.invalidate()
            assertEquals("pm_at_a", coordinator.rawAccessToken())
            assertNull(coordinator.openDomainAccessToken())
            assertTrue(
                !coordinator.publishBoundSession(
                    lease = lease,
                    expectedOwner = OWNER_B,
                    accessToken = "pm_at_b",
                    refreshToken = "pm_rt_b",
                    currentAccountOwner = OWNER_B,
                ),
            )
            assertEquals(
                CoordinatedRefreshOutcome.Invalidated,
                coordinator.refreshForBootstrap(OWNER_B),
            )
            assertNull(tokens.accessToken)
            assertNull(stored.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun postPersistPreOpenOrdinaryRequestHasNoHeader(): Unit = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val tokens = AuthTokenHolder("pm_at_a", "pm_rt_a")
            val stored = AtomicReference(bound("pm_rt_a"))
            val sink = RecordingSink()
            val coordinator = coordinator(server, tokens, sink, stored)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            coordinator.onAfterBoundCredentialsPersisted = {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            }
            val lease = coordinator.acquireAccountLease()
            coordinator.closeAdmission()
            val publish = async(Dispatchers.IO) {
                coordinator.publishBoundSession(
                    lease = lease,
                    expectedOwner = OWNER_B,
                    accessToken = "pm_at_b",
                    refreshToken = "pm_rt_b",
                    currentAccountOwner = OWNER_B,
                )
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNull(coordinator.openDomainAccessToken())
            assertEquals("pm_at_b", coordinator.rawAccessToken())
            assertEquals(OWNER_B, stored.get()?.ownerUserId)
            release.countDown()
            assertTrue(publish.await())
            assertEquals("pm_at_b", coordinator.openDomainAccessToken())
        } finally {
            server.close()
        }
    }

    @Test
    fun logoutClosesOrdinaryBearerButKeepsRawRevocationToken(): Unit = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val tokens = AuthTokenHolder("pm_at_a", "pm_rt_a")
            val stored = AtomicReference(bound("pm_rt_a"))
            val sink = RecordingSink()
            val coordinator = coordinator(server, tokens, sink, stored)
            coordinator.invalidate()
            assertEquals("pm_at_a", coordinator.rawAccessToken())
            assertNull(coordinator.openDomainAccessToken())
            assertEquals("pm_rt_a", stored.get()?.refreshToken)
            assertEquals(0, sink.clears.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun validatedOwnerAThenReplacementBBeforeCoordinatorEntryDoesNotPostOnSuccess(): Unit =
        runBlocking {
            assertOwnerReplacementBeforeEntryDoesNotPost(success = true)
        }

    @Test
    fun validatedOwnerAThenReplacementBBeforeCoordinatorEntryDoesNotPostOnNetwork(): Unit =
        runBlocking {
            assertOwnerReplacementBeforeEntryDoesNotPost(success = false)
        }

    private suspend fun kotlinx.coroutines.CoroutineScope.assertOwnerReplacementBeforeEntryDoesNotPost(
        success: Boolean,
    ) {
        val server = MockWebServer()
        val refreshCount = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                refreshCount.incrementAndGet()
                if (!success) throw java.io.IOException("unreachable")
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
            val stored = AtomicReference(bound("pm_rt_old", OWNER_A))
            val sink = RecordingSink()
            val coordinator = coordinator(server, tokens, sink, stored)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            coordinator.onBeforeRefreshMutex = {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            }

            val inFlight = async(Dispatchers.IO) {
                coordinator.refreshForBootstrap(OWNER_A)
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            stored.set(bound("pm_rt_b", OWNER_B))
            tokens.updateTokens("pm_at_b", "pm_rt_b")
            coordinator.install(OWNER_B)
            release.countDown()

            assertEquals(CoordinatedRefreshOutcome.Invalidated, inFlight.await())
            assertEquals(0, refreshCount.get())
            assertEquals(OWNER_B, stored.get()?.ownerUserId)
            assertEquals("pm_rt_b", stored.get()?.refreshToken)
            assertEquals("pm_at_b", tokens.accessToken)
            assertEquals(0, sink.rotations.size)
            assertEquals(0, sink.clears.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun corruptReadOfAThenPublishedBDoesNotClearB(): Unit = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val tokens = AuthTokenHolder("pm_at_a", "pm_rt_a")
            val stored = AtomicReference(bound("pm_rt_a", OWNER_A))
            val sink = RecordingSink()
            val failRead = java.util.concurrent.atomic.AtomicBoolean(true)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val coordinator = SessionRefreshCoordinator(
                bareAuthRepository = AuthRepository.create(ApiBaseUrl.parse(server.url("/").toString())),
                tokens = tokens,
                refreshTokenReader = RefreshTokenReader {
                    if (failRead.get()) error("corrupt A")
                    stored.get()
                },
                rotationSink = object : SessionRotationSink {
                    override fun rotate(accessToken: String, refreshToken: String, ownerUserId: UserId) {
                        stored.set(bound(refreshToken, ownerUserId))
                        sink.rotate(accessToken, refreshToken, ownerUserId)
                    }

                    override fun clear() {
                        stored.set(null)
                        sink.clear()
                    }
                },
            )
            coordinator.onBeforeReadFailureCleanup = {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            }
            val inFlight = async(Dispatchers.IO) { coordinator.refreshForBootstrap(OWNER_A) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            failRead.set(false)
            stored.set(bound("pm_rt_b", OWNER_B))
            tokens.updateTokens("pm_at_b", "pm_rt_b")
            coordinator.install(OWNER_B)
            release.countDown()
            assertEquals(CoordinatedRefreshOutcome.Invalidated, inFlight.await())
            assertEquals(OWNER_B, stored.get()?.ownerUserId)
            assertEquals("pm_rt_b", stored.get()?.refreshToken)
            assertEquals("pm_at_b", tokens.accessToken)
            assertEquals(0, sink.clears.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun unavailableReadOfAThenPublishedBDoesNotClearB(): Unit = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val tokens = AuthTokenHolder("pm_at_a", "pm_rt_a")
            val stored = AtomicReference<BoundRefreshCredential?>(null)
            val sink = RecordingSink()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val coordinator = coordinator(server, tokens, sink, stored)
            coordinator.onBeforeReadFailureCleanup = {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            }
            val inFlight = async(Dispatchers.IO) { coordinator.refreshForBootstrap(OWNER_A) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            stored.set(bound("pm_rt_b", OWNER_B))
            tokens.updateTokens("pm_at_b", "pm_rt_b")
            coordinator.install(OWNER_B)
            release.countDown()
            assertEquals(CoordinatedRefreshOutcome.Invalidated, inFlight.await())
            assertEquals(OWNER_B, stored.get()?.ownerUserId)
            assertEquals("pm_rt_b", stored.get()?.refreshToken)
            assertEquals("pm_at_b", tokens.accessToken)
            assertEquals(0, sink.clears.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectedRefreshOfAAfterBInstalledDoesNotClearB(): Unit = runBlocking {
        val server = MockWebServer()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                return MockResponse.Builder().code(401).build()
            }
        }
        server.start()
        try {
            val tokens = AuthTokenHolder(null, "pm_rt_a")
            val stored = AtomicReference(bound("pm_rt_a", OWNER_A))
            val sink = RecordingSink()
            val coordinator = coordinator(server, tokens, sink, stored)
            val inFlight = async(Dispatchers.IO) { coordinator.refreshForBootstrap(OWNER_A) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            stored.set(bound("pm_rt_b", OWNER_B))
            tokens.updateTokens("pm_at_b", "pm_rt_b")
            coordinator.install(OWNER_B)
            release.countDown()
            assertEquals(CoordinatedRefreshOutcome.Invalidated, inFlight.await())
            assertEquals(OWNER_B, stored.get()?.ownerUserId)
            assertEquals("pm_rt_b", stored.get()?.refreshToken)
            assertEquals("pm_at_b", tokens.accessToken)
            assertEquals(0, sink.clears.get())
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
