package dev.hryshyn.remanence.core.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import dev.hryshyn.remanence.core.model.CapsuleId

/**
 * T186 M3b: typed R15 completion client tests against MockWebServer.
 * Response shape assumption (`state == "RETIRED_OPEN"` with id echoes) is
 * flagged for T185 confirmation; routes are scaffold server-side, so these
 * run isolated only.
 */
class PendingLocatorRetireRepositoryTest {

    private val capsuleId = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000ca01")
    private val operationId = "0198f0a0-0000-7000-8000-00000000c001"

    private suspend fun <T> withServer(block: suspend (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server)
        } finally {
            server.close()
        }
    }

    private fun repository(server: MockWebServer): PendingLocatorRetireRepository =
        PendingLocatorRetireRepository(
            HttpClientFactory.create(),
            ApiBaseUrl.parse(server.url("/").toString()),
        )

    private fun successJson() =
        """{"capsule_id":"0198f0a0-0000-7000-8000-00000000ca01","state":"RETIRED_OPEN","operation_id":"$operationId"}"""

    private fun problemJson(code: String, status: Int, retryable: Boolean): String =
        """{"type":"https://remanence.invalid/problems/${code.lowercase()}","title":"safe","status":$status,"code":"$code","detail":"private detail","request_id":"0198f0a0-0000-7000-8000-00000000ac01","retryable":$retryable}"""

    @Test
    fun retiredOpenCompletesAndSendsFrozenFields() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(successJson())
                    .build(),
            )
            val result = assertIs<LocatorRetireResult.Completed>(
                repository(server).complete(capsuleId, operationId, "pub-hash-opaque", null, "token"),
            )
            assertEquals(capsuleId, result.capsuleId)
            assertEquals(operationId, result.operationId)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(
                "/v2/capsules/0198f0a0-0000-7000-8000-00000000ca01/locator-open-completion",
                request.url.encodedPath,
            )
            assertEquals("Bearer token", request.headers["Authorization"])
            val body = assertNotNull(request.body).utf8()
            assertTrue(body.contains(operationId))
            assertTrue(body.contains("pub-hash-opaque"))
        }
    }

    @Test
    fun nonRetiredStateIsInvalidNeverSuccess() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body("""{"capsule_id":"0198f0a0-0000-7000-8000-00000000ca01","state":"ACTIVE","operation_id":"$operationId"}""")
                    .build(),
            )
            val result = assertIs<LocatorRetireResult.Failure>(
                repository(server).complete(capsuleId, operationId, null, null, "token"),
            )
            assertEquals(LocatorRetireFailure.INVALID_RESPONSE, result.reason)
            assertFalse(result.retryable)
        }
    }

    @Test
    fun capsuleNotFoundIsTerminal() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(404)
                    .setHeader("Content-Type", "application/problem+json")
                    .body(problemJson("CAPSULE_NOT_FOUND", 404, false))
                    .build(),
            )
            val result = assertIs<LocatorRetireResult.Failure>(
                repository(server).complete(capsuleId, operationId, null, null, "token"),
            )
            assertEquals(LocatorRetireFailure.CAPSULE_NOT_FOUND, result.reason)
            assertFalse(result.retryable)
        }
    }

    @Test
    fun serverErrorIsRetryable() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(503)
                    .setHeader("Content-Type", "application/problem+json")
                    .body(problemJson("INTERNAL_ERROR", 503, true))
                    .build(),
            )
            val result = assertIs<LocatorRetireResult.Failure>(
                repository(server).complete(capsuleId, operationId, null, null, "token"),
            )
            assertEquals(LocatorRetireFailure.INTERNAL_ERROR, result.reason)
            assertTrue(result.retryable)
        }
    }

    @Test
    fun blankTokenNeverSends() = runTest {
        withServer { server ->
            val result = assertIs<LocatorRetireResult.Failure>(
                repository(server).complete(capsuleId, operationId, null, null, "  "),
            )
            assertEquals(LocatorRetireFailure.VALIDATION_FAILED, result.reason)
            assertFalse(result.retryable)
            assertEquals(0, server.requestCount)
        }
    }
}
