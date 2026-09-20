package dev.hryshyn.remanence.core.data.network

import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import okhttp3.ResponseBody
import okio.BufferedSource

class CapsuleFirstOpenRepositoryTest {

    private val capsuleId = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000cf01")
    private val firstOpenedAt = "2026-09-09T01:02:03.004Z"

    private suspend fun <T> withServer(block: suspend (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server)
        } finally {
            server.close()
        }
    }

    @Test
    fun freshAndLostResponseReplayUseAuthenticatedEmptyPost() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(successJson(isReplay = false))
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(successJson(isReplay = true))
                    .build(),
            )

            val repository = repository(server)
            val first = assertIs<CapsuleFirstOpenResult.Success>(
                repository.claim(capsuleId, "pm_at_live"),
            )
            val replay = assertIs<CapsuleFirstOpenResult.Success>(
                repository.claim(capsuleId, "pm_at_live"),
            )

            assertFalse(first.open.isReplay)
            assertTrue(replay.open.isReplay)
            assertEquals(1_788_915_723_004L, first.open.firstOpenedAtEpochMs)
            assertEquals(first.open.firstOpenedAtEpochMs, replay.open.firstOpenedAtEpochMs)

            repeat(2) {
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals(
                    "/v1/capsules/${capsuleId.toRestString()}/first-open",
                    request.url.encodedPath,
                )
                assertEquals("Bearer pm_at_live", request.headers["Authorization"])
                assertEquals(0, request.body?.size ?: 0)
                assertEquals(null, request.headers["Content-Type"])
            }
        }
    }

    @Test
    fun documentedDenialsAndOfflineFailureAreFailClosed() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(409)
                    .setHeader("Content-Type", "application/problem+json")
                    .body(problemJson("CAPSULE_STATE_INVALID", 409, false))
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder()
                    .code(503)
                    .setHeader("Content-Type", "application/problem+json")
                    .body(problemJson("INTERNAL_ERROR", 503, true))
                    .build(),
            )

            val denied = assertIs<CapsuleFirstOpenResult.Failure>(
                repository(server).claim(capsuleId, "token"),
            )
            assertEquals(CapsuleFirstOpenFailure.CAPSULE_STATE_INVALID, denied.reason)
            assertFalse(denied.retryable)

            val transient = assertIs<CapsuleFirstOpenResult.Failure>(
                repository(server).claim(capsuleId, "token"),
            )
            assertEquals(CapsuleFirstOpenFailure.INTERNAL_ERROR, transient.reason)
            assertTrue(transient.retryable)
        }

        val server = MockWebServer()
        server.start()
        val baseUrl = ApiBaseUrl.parse(server.url("/").toString())
        server.close()
        val offline = assertIs<CapsuleFirstOpenResult.Failure>(
            CapsuleFirstOpenRepository.create(baseUrl).claim(capsuleId, "token"),
        )
        assertEquals(CapsuleFirstOpenFailure.NETWORK, offline.reason)
        assertTrue(offline.retryable)
    }

    @Test
    fun malformedSuccessAndBlankTokenNeverAdmit() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        "{\"capsule_id\":\"${UUID.randomUUID()}\",\"state\":\"OPENED\",\"first_opened_at\":\"$firstOpenedAt\",\"is_replay\":false}",
                    )
                    .build(),
            )

            val malformed = assertIs<CapsuleFirstOpenResult.Failure>(
                repository(server).claim(capsuleId, "token"),
            )
            assertEquals(CapsuleFirstOpenFailure.INVALID_RESPONSE, malformed.reason)
            val blank = assertIs<CapsuleFirstOpenResult.Failure>(
                repository(server).claim(capsuleId, "   "),
            )
            assertEquals(CapsuleFirstOpenFailure.VALIDATION_FAILED, blank.reason)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun delayedHeadersAndBodyNeverBlockMainAndCancellationCancelsCall() = runBlocking {
        val mainExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "first-open-main")
        }
        val main = mainExecutor.asCoroutineDispatcher()
        try {
            withServer { server ->
                val bodyReadStarted = CompletableDeferred<String>()
                val bodyClosed = CompletableDeferred<Unit>()
                server.enqueue(
                    MockResponse.Builder()
                        .code(200)
                        .setHeader("Content-Type", "application/json")
                        .headersDelay(100, TimeUnit.MILLISECONDS)
                        .bodyDelay(5, TimeUnit.SECONDS)
                        .body(successJson(isReplay = false))
                        .build(),
                )
                val client = HttpClientFactory.create().newBuilder()
                    .addInterceptor { chain ->
                        val response = chain.proceed(chain.request())
                        response.newBuilder()
                            .body(
                                SignallingResponseBody(
                                    delegate = response.body,
                                    onSource = {
                                        bodyReadStarted.complete(Thread.currentThread().name)
                                    },
                                    onClose = {
                                        bodyClosed.complete(Unit)
                                    },
                                ),
                            )
                            .build()
                    }
                    .build()
                val operation = async(main) {
                    CapsuleFirstOpenRepository(
                        client,
                        ApiBaseUrl.parse(server.url("/").toString()),
                    ).claim(capsuleId, "token")
                }

                val bodyThread = withTimeout(2_000) { bodyReadStarted.await() }
                assertFalse(bodyThread == "first-open-main")
                val heartbeat = async(main) { "heartbeat" }
                assertEquals("heartbeat", withTimeout(500) { heartbeat.await() })

                operation.cancelAndJoin()
                assertTrue(operation.isCancelled)
                withTimeout(2_000) { bodyClosed.await() }
            }
        } finally {
            main.close()
            mainExecutor.shutdownNow()
        }
    }

    @Test
    fun oversizedSuccessBodyNeverAdmits() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body("x".repeat(64 * 1024 + 1))
                    .build(),
            )

            val result = assertIs<CapsuleFirstOpenResult.Failure>(
                repository(server).claim(capsuleId, "token"),
            )
            assertEquals(CapsuleFirstOpenFailure.INVALID_RESPONSE, result.reason)
            assertFalse(result.retryable)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun productionStackRejectsInvalidatedLeaseBeforeSendAndAdmitsFreshInstall() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(successJson(isReplay = false))
                    .build(),
            )
            val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000cf03")
            val tokens = AuthTokenHolder("pm_at_live", "pm_rt_live")
            val stack = ProductionApiStack.create(
                baseUrl = ApiBaseUrl.parse(server.url("/").toString()),
                tokens = tokens,
                refreshTokenReader = RefreshTokenReader {
                    BoundRefreshCredential(owner, "pm_rt_live")
                },
                rotationSink = object : SessionRotationSink {
                    override fun rotate(accessToken: String, refreshToken: String, ownerUserId: UserId) = Unit
                    override fun clear() = Unit
                },
            )
            stack.sessionRefreshCoordinator.install(owner)
            stack.sessionRefreshCoordinator.onBeforeRequestLeaseTokenSelection = {
                stack.sessionRefreshCoordinator.invalidate()
            }

            val stale = assertIs<CapsuleFirstOpenResult.Failure>(
                stack.capsuleFirstOpenRepository.claim(capsuleId, "pm_at_live"),
            )
            assertEquals(CapsuleFirstOpenFailure.NETWORK, stale.reason)
            assertEquals(0, server.requestCount)

            stack.sessionRefreshCoordinator.onBeforeRequestLeaseTokenSelection = null
            stack.sessionRefreshCoordinator.install(owner)
            val fresh = assertIs<CapsuleFirstOpenResult.Success>(
                stack.capsuleFirstOpenRepository.claim(capsuleId, "pm_at_live"),
            )
            assertFalse(fresh.open.isReplay)
            assertEquals("Bearer pm_at_live", server.takeRequest().headers["Authorization"])
        }
    }

    @Test
    fun staleA1LeaseAfterLogoutAndSameOwnerA2CreatesZeroServerClaims() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(successJson(isReplay = false))
                    .build(),
            )
            val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000cf03")
            val tokens = AuthTokenHolder("pm_at_live", "pm_rt_live")
            val stack = ProductionApiStack.create(
                baseUrl = ApiBaseUrl.parse(server.url("/").toString()),
                tokens = tokens,
                refreshTokenReader = RefreshTokenReader {
                    BoundRefreshCredential(owner, "pm_rt_live")
                },
                rotationSink = object : SessionRotationSink {
                    override fun rotate(accessToken: String, refreshToken: String, ownerUserId: UserId) = Unit
                    override fun clear() = Unit
                },
            )
            stack.sessionRefreshCoordinator.install(owner)
            val staleA1 = requireNotNull(stack.captureSessionRequestLease())

            // Logout retires A1. Reinstalling the same owner creates A2; an
            // old prepared presentation must not borrow A2's bearer.
            stack.sessionRefreshCoordinator.invalidate()
            stack.sessionRefreshCoordinator.install(owner)
            val stale = assertIs<CapsuleFirstOpenResult.Failure>(
                stack.capsuleFirstOpenRepository.claim(capsuleId, "pm_at_live", staleA1),
            )
            assertEquals(CapsuleFirstOpenFailure.NETWORK, stale.reason)
            assertEquals(0, server.requestCount)

            val freshA2 = requireNotNull(stack.captureSessionRequestLease())
            val fresh = assertIs<CapsuleFirstOpenResult.Success>(
                stack.capsuleFirstOpenRepository.claim(capsuleId, "pm_at_live", freshA2),
            )
            assertFalse(fresh.open.isReplay)
            assertEquals(1, server.requestCount)
        }
    }

    private fun repository(server: MockWebServer): CapsuleFirstOpenRepository =
        CapsuleFirstOpenRepository.create(ApiBaseUrl.parse(server.url("/").toString()))

    private fun successJson(isReplay: Boolean): String =
        "{\"capsule_id\":\"${capsuleId.toRestString()}\",\"state\":\"OPENED\",\"first_opened_at\":\"$firstOpenedAt\",\"is_replay\":$isReplay}"

    private fun problemJson(code: String, status: Int, retryable: Boolean): String =
        "{\"type\":\"https://remanence.invalid/problems/${code.lowercase()}\",\"title\":\"safe\",\"status\":$status,\"code\":\"$code\",\"detail\":\"private detail\",\"request_id\":\"0198f0a0-0000-7000-8000-00000000cf02\",\"retryable\":$retryable}"

    private class SignallingResponseBody(
        private val delegate: ResponseBody,
        private val onSource: () -> Unit,
        private val onClose: () -> Unit,
    ) : ResponseBody() {
        override fun contentType() = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        override fun source(): BufferedSource {
            onSource()
            return delegate.source()
        }

        override fun close() {
            delegate.close()
            onClose()
        }
    }
}
