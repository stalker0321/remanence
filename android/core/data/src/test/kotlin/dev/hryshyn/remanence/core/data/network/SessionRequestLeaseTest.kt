package dev.hryshyn.remanence.core.data.network

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import dev.hryshyn.remanence.core.model.UserId
import org.junit.Test

/** Deterministic session-boundary coverage for the production transport fence. */
class SessionRequestLeaseTest {

    private companion object {
        const val REFRESH_RESPONSE = """
            {
              "session_id":"session-new",
              "access_token":"pm_at_new",
              "access_expires_at":"2026-08-23T04:15:00Z",
              "refresh_token":"pm_rt_new",
              "refresh_expires_at":"2026-09-22T04:00:00Z"
            }
        """

        const val DIRECTORY_RESPONSE = """
            {
              "user":{"user_id":"0198f0a0-0000-7000-8000-00000000a001","handle":"mykola"},
              "key_bundle":{
                "key_bundle_id":"0198f0a0-0000-7000-8000-00000000ba01",
                "user_id":"0198f0a0-0000-7000-8000-00000000a001",
                "suite":"HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
                "protocol_version":1,
                "encryption_public_keyset":"ZW5jcnlwdGlvbg",
                "signing_public_keyset":"c2lnbmluZw",
                "status":"ACTIVE",
                "created_at":"2026-08-30T03:00:00Z"
              },
              "directory_version":"v1"
            }
        """
    }

    private val ownerA = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a001")
    private val ownerB = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a002")

    @Test
    fun queuedBeforeSendCannotBorrowBAndFreshBRequestUsesB() = runBlocking {
        val server = MockWebServer()
        val requests = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return MockResponse.Builder().code(200).body("ok").build()
            }
        }
        server.start()
        try {
            val tokens = AuthTokenHolder("a-access", "a-refresh")
            val stored = AtomicReference(BoundRefreshCredential(ownerA, "a-refresh"))
            val coordinator = coordinator(tokens, stored, server)
            coordinator.install(ownerA)
            val provider = SessionRequestLeaseProvider(coordinator)
            val oldLease = checkNotNull(provider.capture())

            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val client = OkHttpClient.Builder()
                // This is the queued-before-send seam: session invalidation
                // happens after capture but before the auth interceptor runs.
                .addInterceptor { chain ->
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                    chain.proceed(chain.request())
                }
                .let { RefreshingAuthenticator.attach(it, coordinator) }
                .build()

            val oldRequest = provider.tag(
                Request.Builder().url(server.url("/v1/directory/users/$ownerA")).get().build(),
                oldLease,
            )
            val oldCall = async(Dispatchers.IO) {
                runCatching {
                    client.newCall(oldRequest).executeAsync().use { it.code }
                }
            }
            check(entered.await(2, TimeUnit.SECONDS))

            coordinator.invalidate()
            tokens.updateTokens("b-access", "b-refresh")
            stored.set(BoundRefreshCredential(ownerB, "b-refresh"))
            coordinator.install(ownerB)
            release.countDown()

            assertIs<RequestLeaseRejectedException>(oldCall.await().exceptionOrNull())
            assertEquals(0, requests.size)

            val freshLease = checkNotNull(provider.capture())
            val freshRequest = provider.tag(
                Request.Builder().url(server.url("/v1/directory/users/$ownerB")).get().build(),
                freshLease,
            )
            client.newCall(freshRequest).executeAsync().use { assertEquals(200, it.code) }
            assertEquals(1, requests.size)
            assertEquals("Bearer b-access", requests.single().headers["Authorization"])
        } finally {
            server.close()
        }
    }

    @Test
    fun sameOwnerReincarnationRejectsOldLeaseAndAllowsOnlyFreshLease() = runBlocking {
        val server = MockWebServer()
        val authorization = mutableListOf<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                authorization += request.headers["Authorization"]
                return MockResponse.Builder().code(200).build()
            }
        }
        server.start()
        try {
            val tokens = AuthTokenHolder("a1-access", "a1-refresh")
            val stored = AtomicReference(BoundRefreshCredential(ownerA, "a1-refresh"))
            val coordinator = coordinator(tokens, stored, server)
            coordinator.install(ownerA)
            val provider = SessionRequestLeaseProvider(coordinator)
            val oldLease = checkNotNull(provider.capture())
            val oldRequest = provider.tag(
                Request.Builder().url(server.url("/v1/data")).get().build(),
                oldLease,
            )

            coordinator.invalidate()
            tokens.updateTokens("a2-access", "a2-refresh")
            stored.set(BoundRefreshCredential(ownerA, "a2-refresh"))
            coordinator.install(ownerA)
            val client = RefreshingAuthenticator.attach(
                OkHttpClient.Builder(),
                coordinator,
            ).build()

            assertFailsWith<IOException> {
                runBlocking {
                    client.newCall(oldRequest).executeAsync().use { it.code }
                }
            }
            assertNull(authorization.singleOrNull())

            val newRequest = provider.tag(
                Request.Builder().url(server.url("/v1/data")).get().build(),
                checkNotNull(provider.capture()),
            )
            client.newCall(newRequest).executeAsync().use { assertEquals(200, it.code) }
            assertEquals(listOf<String?>("Bearer a2-access"), authorization)
        } finally {
            server.close()
        }
    }

    @Test
    fun leaseAdmissionRechecksAfterOwnerRotationBeforeSelectingBearer() = runBlocking {
        val server = MockWebServer()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return MockResponse.Builder().code(200).build()
            }
        }
        server.start()
        try {
            val tokens = AuthTokenHolder("a1-access", "a1-refresh")
            val stored = AtomicReference(BoundRefreshCredential(ownerA, "a1-refresh"))
            val coordinator = coordinator(tokens, stored, server)
            coordinator.install(ownerA)
            val provider = SessionRequestLeaseProvider(coordinator)
            val retiredLease = checkNotNull(provider.capture())
            var barrierCalls = 0
            coordinator.onBeforeRequestLeaseTokenSelection = {
                barrierCalls++
                coordinator.invalidate()
                tokens.updateTokens("b-access", "b-refresh")
                stored.set(BoundRefreshCredential(ownerB, "b-refresh"))
                coordinator.install(ownerB)
            }
            val client = RefreshingAuthenticator.attach(OkHttpClient.Builder(), coordinator).build()

            val retiredRequest = provider.tag(
                Request.Builder().url(server.url("/v1/protected")).get().build(),
                retiredLease,
            )
            assertFailsWith<RequestLeaseRejectedException> {
                client.newCall(retiredRequest).executeAsync().use { it.code }
            }
            assertEquals(1, barrierCalls)
            assertEquals(0, requests.size)

            coordinator.onBeforeRequestLeaseTokenSelection = null
            val currentRequest = provider.tag(
                Request.Builder().url(server.url("/v1/protected")).get().build(),
                checkNotNull(provider.capture()),
            )
            client.newCall(currentRequest).executeAsync().use { assertEquals(200, it.code) }
            assertEquals(listOf("Bearer b-access"), requests.map { it.headers["Authorization"] })
        } finally {
            server.close()
        }
    }

    @Test
    fun earlyReusedSelectionRejectsRetiredLeaseBeforeReturningNewOwnerBearer() = runBlocking {
        val server = MockWebServer()
        val requests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.incrementAndGet()
                return MockResponse.Builder().code(200).build()
            }
        }
        server.start()
        try {
            val tokens = AuthTokenHolder("a1-access", "a1-refresh")
            val stored = AtomicReference(BoundRefreshCredential(ownerA, "a1-refresh"))
            val coordinator = coordinator(tokens, stored, server)
            coordinator.install(ownerA)
            val provider = SessionRequestLeaseProvider(coordinator)
            val oldLease = checkNotNull(provider.capture())
            coordinator.onBeforeReusedBearerSelection = {
                coordinator.invalidate()
                tokens.updateTokens("b-access", "b-refresh")
                stored.set(BoundRefreshCredential(ownerB, "b-refresh"))
                coordinator.install(ownerB)
            }

            val outcome = coordinator.refreshForAuthenticator(
                staleAccessToken = "stale-a1",
                requestLease = oldLease,
            )

            assertNull(outcome)
            assertEquals("b-access", tokens.accessToken)
            assertEquals(0, requests.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun refreshFollowUpAdmissionRejectsSameOwnerReincarnationAfterRefresh() = runBlocking {
        val server = MockWebServer()
        val protectedHeaders = CopyOnWriteArrayList<String?>()
        val refreshHeaders = CopyOnWriteArrayList<String?>()
        val refreshCount = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/v1/auth/refresh") {
                    refreshHeaders += request.headers["Authorization"]
                    refreshCount.incrementAndGet()
                    return MockResponse.Builder()
                        .code(200)
                        .setHeader("Content-Type", "application/json")
                        .body(
                            """
                            {
                              "session_id":"session-old",
                              "access_token":"old-refreshed-access",
                              "access_expires_at":"2026-08-23T04:15:00Z",
                              "refresh_token":"old-refreshed-refresh",
                              "refresh_expires_at":"2026-09-22T04:00:00Z"
                            }
                            """.trimIndent(),
                        )
                        .build()
                }
                protectedHeaders += request.headers["Authorization"]
                return MockResponse.Builder().code(401).build()
            }
        }
        server.start()
        try {
            val tokens = AuthTokenHolder("a1-access", "a1-refresh")
            val stored = AtomicReference(BoundRefreshCredential(ownerA, "a1-refresh"))
            val coordinator = coordinator(tokens, stored, server)
            coordinator.install(ownerA)
            val provider = SessionRequestLeaseProvider(coordinator)
            val authenticator = RefreshingAuthenticator.create(coordinator, provider)
            authenticator.onAfterRefreshBeforeFollowUp = {
                coordinator.invalidate()
                tokens.updateTokens("a2-access", "a2-refresh")
                stored.set(BoundRefreshCredential(ownerA, "a2-refresh"))
                coordinator.install(ownerA)
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(
                    BearerAuthInterceptor(
                        requestLeases = provider,
                        accessToken = { coordinator.openDomainAccessToken() },
                    ),
                )
                .authenticator(authenticator)
                .build()
            val request = provider.tag(
                Request.Builder().url(server.url("/v1/protected")).get().build(),
                checkNotNull(provider.capture()),
            )

            client.newCall(request).executeAsync().use { assertEquals(401, it.code) }
            assertEquals(1, refreshCount.get())
            assertEquals(listOf<String?>(null), refreshHeaders)
            assertEquals(listOf("Bearer a1-access"), protectedHeaders.toList())
        } finally {
            server.close()
        }
    }

    @Test
    fun keyBundleByIdUsesLeaseBoundProductionStack() = runBlocking {
        val server = MockWebServer()
        val authorization = CopyOnWriteArrayList<String?>()
        val protectedHits = AtomicInteger()
        val refreshCount = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/v1/auth/refresh") {
                    refreshCount.incrementAndGet()
                    return MockResponse.Builder()
                        .code(200)
                        .setHeader("Content-Type", "application/json")
                        .body(REFRESH_RESPONSE)
                        .build()
                }
                authorization += request.headers["Authorization"]
                return if (protectedHits.incrementAndGet() == 1) {
                    MockResponse.Builder().code(401).build()
                } else {
                    MockResponse.Builder()
                        .code(200)
                        .setHeader("Content-Type", "application/json")
                        .body(
                            """
                            {
                              "key_bundle_id":"0198f0a0-0000-7000-8000-00000000ba01",
                              "user_id":"0198f0a0-0000-7000-8000-00000000a001",
                              "suite":"HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
                              "protocol_version":1,
                              "encryption_public_keyset":"ZW5jcnlwdGlvbg",
                              "signing_public_keyset":"c2lnbmluZw",
                              "status":"ACTIVE",
                              "created_at":"2026-08-30T03:00:00Z"
                            }
                            """.trimIndent(),
                        )
                        .build()
                }
            }
        }
        server.start()
        try {
            val tokens = AuthTokenHolder("lease-access", "lease-refresh")
            val owner = ownerA
            val stack = ProductionApiStack.create(
                baseUrl = ApiBaseUrl.parse(server.url("/").toString()),
                tokens = tokens,
                refreshTokenReader = RefreshTokenReader {
                    BoundRefreshCredential(owner, "lease-refresh")
                },
                rotationSink = object : SessionRotationSink {
                    override fun rotate(accessToken: String, refreshToken: String, ownerUserId: UserId) = Unit
                    override fun clear() = Unit
                },
            )
            stack.sessionRefreshCoordinator.install(owner)

            val result = stack.keyBundleByIdRepository.fetch("0198f0a0-0000-7000-8000-00000000ba01")
            assertIs<KeyBundleByIdResult.Found>(result)
            assertEquals(1, refreshCount.get())
            assertEquals(listOf("Bearer lease-access", "Bearer pm_at_new"), authorization.toList())
        } finally {
            server.close()
        }
    }

    @Test
    fun concurrentLeaseAware401BurstUsesOldThenNewHeadersWithOneRefresh() = runBlocking {
        val server = MockWebServer()
        val protectedHeaders = CopyOnWriteArrayList<String?>()
        val protectedHits = AtomicInteger()
        val refreshCount = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.url.encodedPath == "/v1/auth/refresh" -> {
                    refreshCount.incrementAndGet()
                    MockResponse.Builder()
                        .code(200)
                        .setHeader("Content-Type", "application/json")
                        .body(REFRESH_RESPONSE)
                        .build()
                }
                else -> {
                    protectedHeaders += request.headers["Authorization"]
                    if (protectedHits.incrementAndGet() <= 4) {
                        MockResponse.Builder().code(401).build()
                    } else {
                        MockResponse.Builder()
                            .code(200)
                            .setHeader("Content-Type", "application/json")
                            .body(DIRECTORY_RESPONSE)
                            .build()
                    }
                }
            }
        }
        server.start()
        try {
            val tokens = AuthTokenHolder("a-access", "a-refresh")
            val stored = AtomicReference(BoundRefreshCredential(ownerA, "a-refresh"))
            val stack = ProductionApiStack.create(
                baseUrl = ApiBaseUrl.parse(server.url("/").toString()),
                tokens = tokens,
                refreshTokenReader = RefreshTokenReader { stored.get() },
                rotationSink = object : SessionRotationSink {
                    override fun rotate(accessToken: String, refreshToken: String, ownerUserId: UserId) {
                        stored.set(BoundRefreshCredential(ownerUserId, refreshToken))
                    }

                    override fun clear() {
                        stored.set(null)
                    }
                },
            )
            stack.sessionRefreshCoordinator.install(ownerA)

            val results = (1..4).map {
                async(Dispatchers.IO) { stack.directoryRepository.lookup("mykola") }
            }.map { it.await() }

            assertEquals(4, results.count { it is DirectoryLookupResult.Found })
            assertEquals(1, refreshCount.get())
            assertEquals(8, protectedHeaders.size)
            assertEquals(4, protectedHeaders.count { it == "Bearer a-access" })
            assertEquals(4, protectedHeaders.count { it == "Bearer pm_at_new" })
        } finally {
            server.close()
        }
    }

    @Test
    fun stale401RefreshCannotPublishIntoReincarnatedSession() = runBlocking {
        val server = MockWebServer()
        val refreshEntered = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val protectedHeaders = CopyOnWriteArrayList<String?>()
        val refreshHeaders = CopyOnWriteArrayList<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/v1/auth/refresh") {
                    refreshHeaders += request.headers["Authorization"]
                    refreshEntered.countDown()
                    check(releaseRefresh.await(2, TimeUnit.SECONDS))
                    return MockResponse.Builder()
                        .code(200)
                        .setHeader("Content-Type", "application/json")
                        .body(
                            """
                            {
                              "session_id":"session-old",
                              "access_token":"old-refreshed-access",
                              "access_expires_at":"2026-08-23T04:15:00Z",
                              "refresh_token":"old-refreshed-refresh",
                              "refresh_expires_at":"2026-09-22T04:00:00Z"
                            }
                        """.trimIndent(),
                        )
                        .build()
                }
                protectedHeaders += request.headers["Authorization"]
                return MockResponse.Builder().code(401).build()
            }
        }
        server.start()
        try {
            val tokens = AuthTokenHolder("a-access", "a-refresh")
            val stored = AtomicReference(BoundRefreshCredential(ownerA, "a-refresh"))
            val rotations = mutableListOf<String>()
            val coordinator = SessionRefreshCoordinator(
                bareAuthRepository = AuthRepository.create(ApiBaseUrl.parse(server.url("/").toString())),
                tokens = tokens,
                refreshTokenReader = RefreshTokenReader { stored.get() },
                rotationSink = object : SessionRotationSink {
                    override fun rotate(accessToken: String, refreshToken: String, ownerUserId: UserId) {
                        rotations += accessToken
                        stored.set(BoundRefreshCredential(ownerUserId, refreshToken))
                    }

                    override fun clear() = Unit
                },
            )
            coordinator.install(ownerA)
            val provider = SessionRequestLeaseProvider(coordinator)
            val request = provider.tag(
                Request.Builder().url(server.url("/v1/protected")).get().build(),
                checkNotNull(provider.capture()),
            )
            val client = RefreshingAuthenticator.attach(
                OkHttpClient.Builder(),
                coordinator,
            ).build()

            val oldCall = async(Dispatchers.IO) {
                client.newCall(request).executeAsync().use { it.code }
            }
            check(refreshEntered.await(2, TimeUnit.SECONDS))
            coordinator.invalidate()
            tokens.updateTokens("b-access", "b-refresh")
            stored.set(BoundRefreshCredential(ownerB, "b-refresh"))
            coordinator.install(ownerB)
            releaseRefresh.countDown()

            assertEquals(401, oldCall.await())
            assertEquals(emptyList<String>(), rotations)
            assertEquals(listOf("Bearer a-access"), protectedHeaders.toList())
            assertEquals(listOf<String?>(null), refreshHeaders)
            assertEquals("b-access", tokens.accessToken)
            assertEquals(ownerB, stored.get()?.ownerUserId)
        } finally {
            server.close()
        }
    }

    private fun coordinator(
        tokens: AuthTokenHolder,
        stored: AtomicReference<BoundRefreshCredential?>,
        server: MockWebServer,
    ): SessionRefreshCoordinator = SessionRefreshCoordinator(
        bareAuthRepository = AuthRepository.create(ApiBaseUrl.parse(server.url("/").toString())),
        tokens = tokens,
        refreshTokenReader = RefreshTokenReader { stored.get() },
        rotationSink = object : SessionRotationSink {
            override fun rotate(accessToken: String, refreshToken: String, ownerUserId: UserId) {
                stored.set(BoundRefreshCredential(ownerUserId, refreshToken))
                tokens.updateTokens(accessToken, refreshToken)
            }

            override fun clear() {
                stored.set(null)
                tokens.clearSession()
            }
        },
    )
}
