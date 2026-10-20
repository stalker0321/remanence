package dev.hryshyn.remanence.core.data.network

import dev.hryshyn.remanence.core.model.UserId
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import mockwebserver3.*
import kotlin.test.*

class SendAccessRepositoryTest {
    private val owner = UserId.parseRest("10000000-0000-4000-8000-000000000001")
    private suspend fun fixture(block: suspend (MockWebServer, ProductionApiStack) -> Unit) {
        val server = MockWebServer()
        server.start()
        val stack = ProductionApiStack.create(ApiBaseUrl.parse(server.url("/").toString()),
            AuthTokenHolder("synthetic-access", "synthetic-refresh"), RefreshTokenReader { null },
            object : SessionRotationSink {
                override fun rotate(accessToken: String, refreshToken: String, ownerUserId: UserId) = Unit
                override fun clear() = Unit
            })
        stack.sessionRefreshCoordinator.install(owner)
        try { block(server, stack) } finally { server.close() }
    }
    private fun wire(self: Int = 2, other: Int = 2, mode: String = "enforce", lifetime: Boolean = false): String =
        """{"policy_version":1,"mode":"$mode","lifetime":$lifetime,"free":{"self":{"limit":2,"used":${2-self},"remaining":$self},"other":{"limit":2,"used":${2-other},"remaining":$other}},"can_publish":{"self":${mode == "record" || lifetime || self > 0},"other":${mode == "record" || lifetime || other > 0}}}"""
    private fun MockWebServer.respond(body: String, code: Int = 200, type: String = "application/json") =
        enqueue(MockResponse.Builder().code(code).setHeader("Content-Type", type).body(body).build())

    @Test fun realAuthenticatedNoStoreGetHasNoOwnerSelectorAndReadsEveryTime() = runTest { fixture { server, stack ->
        repeat(2) {
            server.respond(wire(1, 0))
            val value = assertIs<SendAccessResult.Loaded>(stack.sendAccessRepository.read(owner)).value
            assertEquals(SendAccessBucket(1, 1), value.self)
            assertEquals(SendAccessBucket(2, 0), value.other)
            val request = server.takeRequest()
            assertEquals("/v1/me/send-access", request.url.encodedPath)
            assertNull(request.url.encodedQuery)
            assertEquals("GET", request.method)
            assertEquals(0L, request.body?.size ?: 0L)
            assertEquals("Bearer synthetic-access", request.headers["Authorization"])
            assertEquals("no-store", request.headers["Cache-Control"])
        }
        assertEquals(2, server.requestCount)
    } }

    @Test fun allNineSeparateCombinationsPlusRecordAndLifetimeKeepHistoricalCounts() = runTest { fixture { server, stack ->
        for (self in 0..2) for (other in 0..2) for ((mode, life) in listOf("enforce" to false, "record" to false, "enforce" to true)) {
            server.respond(wire(self, other, mode, life))
            val value = assertIs<SendAccessResult.Loaded>(stack.sendAccessRepository.read(owner)).value
            assertEquals(self, value.self.remaining); assertEquals(2-self, value.self.used)
            assertEquals(other, value.other.remaining); assertEquals(2-other, value.other.used)
            assertEquals(mode == "record" || life || self > 0, value.canPublishSelf)
            assertEquals(mode == "record" || life || other > 0, value.canPublishOther)
        }
    } }

    @Test fun unknownAndMalformedNeverBecomeAllowance() = runTest { fixture { server, stack ->
        val valid = wire()
        val invalid = listOf("{}", "null", "[]", "not json",
            valid.replace("\"policy_version\":1", "\"policy_version\":2"),
            valid.replace("\"policy_version\":1", "\"policy_version\":\"1\""),
            valid.replace("\"policy_version\":1", "\"policy_version\":1.0"),
            valid.replace("\"mode\":\"enforce\"", "\"mode\":\"off\""),
            valid.replace("\"lifetime\":false", "\"lifetime\":\"false\""),
            valid.replace("\"limit\":2", "\"limit\":3"),
            valid.replace("\"used\":0", "\"used\":1"),
            valid.replace("\"remaining\":2", "\"remaining\":-1"),
            valid.replace("\"remaining\":2", "\"remaining\":\"2\""),
            valid.replace("\"self\":true", "\"self\":false"),
            valid.replace("\"other\":true", "\"other\":1"),
            valid.replace("\"mode\":\"enforce\",", ""),
            valid.replace("\"free\":", "\"extra\":0,\"free\":"),
            valid.replace("\"limit\":2", "\"extra\":0,\"limit\":2"),
            valid.replace("\"self\":true", "\"extra\":0,\"self\":true"),
            wire(0, 0, "record").replace("\"self\":true", "\"self\":false"),
            wire(0, 0, lifetime = true).replace("\"other\":true", "\"other\":false"),
            " ".repeat(32 * 1024 + 1))
        for (body in invalid) {
            server.respond(body)
            assertEquals(SendAccessResult.Failed(SendAccessFailure.UNAVAILABLE), stack.sendAccessRepository.read(owner), body.take(200))
        }
        server.respond(valid, type = "text/html")
        assertEquals(SendAccessResult.Failed(SendAccessFailure.UNAVAILABLE), stack.sendAccessRepository.read(owner))
    } }

    @Test fun offAndServerErrorsDoNotInventBalance() = runTest { fixture { server, stack ->
        server.respond("{}", 404)
        assertEquals(SendAccessResult.Hidden, stack.sendAccessRepository.read(owner))
        for (status in listOf(503, 500, 422, 429, 302)) {
            server.respond("{}", status)
            assertEquals(SendAccessResult.Failed(SendAccessFailure.UNAVAILABLE), stack.sendAccessRepository.read(owner))
        }
        server.respond("{}", 401)
        assertEquals(SendAccessResult.Failed(SendAccessFailure.SIGN_IN), stack.sendAccessRepository.read(owner))
    } }

    @Test fun wrongOwnerNeverRequestsAndSameOwnerReinstalledLeaseDiscardsLateReply() = runTest { fixture { server, stack ->
        assertEquals(SendAccessResult.Failed(SendAccessFailure.SIGN_IN),
            stack.sendAccessRepository.read(UserId.parseRest("10000000-0000-4000-8000-000000000002")))
        assertEquals(0, server.requestCount)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                stack.sessionRefreshCoordinator.install(owner)
                return MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(wire()).build()
            }
        }
        assertEquals(SendAccessResult.Failed(SendAccessFailure.SIGN_IN), stack.sendAccessRepository.read(owner))
    } }

    @Test fun transportFailureAndCancellationStayUnknown() = runTest { fixture { server, stack ->
        val repository = SendAccessRepository(HttpClientFactory.create().newBuilder()
            .addInterceptor { throw IOException("synthetic offline") }.build(),
            ApiBaseUrl.parse(server.url("/").toString()), SessionRequestLeaseProvider(stack.sessionRefreshCoordinator))
        assertEquals(SendAccessResult.Failed(SendAccessFailure.UNAVAILABLE), repository.read(owner))
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val held = SendAccessRepository(HttpClientFactory.create().newBuilder().addInterceptor {
            entered.complete(Unit)
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            throw IOException("synthetic cancelled transport")
        }.build(), ApiBaseUrl.parse(server.url("/").toString()), SessionRequestLeaseProvider(stack.sessionRefreshCoordinator))
        val job = async { held.read(owner) }
        try {
            entered.await()
            job.cancel()
        } finally { release.countDown() }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, server.requestCount)
    } }
}
