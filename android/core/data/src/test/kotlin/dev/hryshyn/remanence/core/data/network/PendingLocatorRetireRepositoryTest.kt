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
 * T186 M4: typed R15 completion client tests against MockWebServer — EXACT
 * frozen T185 `api-contract.md` §8 shapes (routes scaffold server-side, so
 * isolated only; the backend is never claimed to work).
 */
class PendingLocatorRetireRepositoryTest {

    private val capsuleId = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000ca01")
    private val operationId = "0198f0a0-0000-7000-8000-00000000c001"
    private val publicationHex = "0f3e5a58f2e94f1923e0410be469bcc8a911b78107b0c5364834a63812d9fb68"

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
        """{"capsule_id":"0198f0a0-0000-7000-8000-00000000ca01","completion":true,"locator_state":"RETIRED_OPEN"}"""

    private fun problemJson(code: String, status: Int, retryable: Boolean): String =
        """{"type":"https://remanence.invalid/problems/${code.lowercase()}","title":"safe","status":$status,"code":"$code","detail":"private detail","request_id":"0198f0a0-0000-7000-8000-00000000ac01","retryable":$retryable}"""

    @Test
    fun retiredOpenCompletesAndSendsExactFrozenFields() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(successJson())
                    .build(),
            )
            val result = assertIs<LocatorRetireResult.Completed>(
                repository(server).complete(capsuleId, publicationHex, operationId, "token"),
            )
            assertEquals(capsuleId, result.capsuleId)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(
                "/v2/capsules/0198f0a0-0000-7000-8000-00000000ca01/locator-open-completion",
                request.url.encodedPath,
            )
            assertEquals("Bearer token", request.headers["Authorization"])
            // Exact §8 request shape: publication hash + operation id only.
            val body = assertNotNull(request.body).utf8()
            assertTrue(body.contains(publicationHex))
            assertTrue(body.contains(operationId))
            assertFalse(body.contains("capsule_id"))
            assertFalse(body.contains("prior_admission"))
        }
    }

    @Test
    fun nonRetiredStateIsInvalidNeverSuccess() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body("""{"capsule_id":"0198f0a0-0000-7000-8000-00000000ca01","completion":true,"locator_state":"ACTIVE"}""")
                    .build(),
            )
            val result = assertIs<LocatorRetireResult.Failure>(
                repository(server).complete(capsuleId, publicationHex, operationId, "token"),
            )
            assertEquals(LocatorRetireFailure.INVALID_RESPONSE, result.reason)
            assertFalse(result.retryable)
        }
    }

    @Test
    fun completionFalseIsInvalid() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body("""{"capsule_id":"0198f0a0-0000-7000-8000-00000000ca01","completion":false,"locator_state":"RETIRED_OPEN"}""")
                    .build(),
            )
            val result = assertIs<LocatorRetireResult.Failure>(
                repository(server).complete(capsuleId, publicationHex, operationId, "token"),
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
                repository(server).complete(capsuleId, publicationHex, operationId, "token"),
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
                repository(server).complete(capsuleId, publicationHex, operationId, "token"),
            )
            assertEquals(LocatorRetireFailure.INTERNAL_ERROR, result.reason)
            assertTrue(result.retryable)
        }
    }

    @Test
    fun blankTokenNeverSends() = runTest {
        withServer { server ->
            val result = assertIs<LocatorRetireResult.Failure>(
                repository(server).complete(capsuleId, publicationHex, operationId, "  "),
            )
            assertEquals(LocatorRetireFailure.VALIDATION_FAILED, result.reason)
            assertFalse(result.retryable)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun malformedHashNeverSends() = runTest {
        withServer { server ->
            val result = assertIs<LocatorRetireResult.Failure>(
                repository(server).complete(capsuleId, "not-hex", operationId, "token"),
            )
            assertEquals(LocatorRetireFailure.VALIDATION_FAILED, result.reason)
            assertFalse(result.retryable)
            assertEquals(0, server.requestCount)
        }
    }
}
