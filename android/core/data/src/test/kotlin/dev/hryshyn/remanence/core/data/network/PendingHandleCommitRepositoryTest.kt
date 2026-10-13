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

/**
 * T186 M5 prep: typed R17 handle-commit client tests (frozen §8; routes
 * scaffold, isolated only). UI wiring lands on the mount-status handoff.
 */
class PendingHandleCommitRepositoryTest {

    private suspend fun <T> withServer(block: suspend (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server)
        } finally {
            server.close()
        }
    }

    private fun repository(server: MockWebServer): PendingHandleCommitRepository =
        PendingHandleCommitRepository(
            HttpClientFactory.create(),
            ApiBaseUrl.parse(server.url("/").toString()),
        )

    private fun problemJson(code: String, status: Int, retryable: Boolean): String =
        """{"type":"https://remanence.invalid/problems/${code.lowercase()}","title":"safe","status":$status,"code":"$code","detail":"private detail","request_id":"0198f0a0-0000-7000-8000-00000000ac01","retryable":$retryable}"""

    @Test
    fun commitSendsExactShapeAndEchoes() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body("""{"user_id":"0198f0a0-0000-7000-8000-00000000aa01","handle":"taken"}""")
                    .build(),
            )
            val result = assertIs<HandleCommitResult.Committed>(
                repository(server).commit("taken", "token"),
            )
            assertEquals("taken", result.handle)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v2/me/handle", request.url.encodedPath)
            assertEquals("Bearer token", request.headers["Authorization"])
            assertTrue(assertNotNull(request.body).utf8().contains("taken"))
        }
    }

    @Test
    fun immutableConflictIsTerminal() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(409)
                    .setHeader("Content-Type", "application/problem+json")
                    .body(problemJson("HANDLE_IMMUTABLE", 409, false))
                    .build(),
            )
            val result = assertIs<HandleCommitResult.Failure>(
                repository(server).commit("other", "token"),
            )
            assertEquals(HandleCommitFailure.HANDLE_IMMUTABLE, result.reason)
            assertFalse(result.retryable)
        }
    }

    @Test
    fun echoMismatchIsInvalid() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body("""{"user_id":"0198f0a0-0000-7000-8000-00000000aa01","handle":"someone-else"}""")
                    .build(),
            )
            val result = assertIs<HandleCommitResult.Failure>(
                repository(server).commit("taken", "token"),
            )
            assertEquals(HandleCommitFailure.INVALID_RESPONSE, result.reason)
            assertFalse(result.retryable)
        }
    }

    @Test
    fun blankInputsNeverSend() = runTest {
        withServer { server ->
            val result = assertIs<HandleCommitResult.Failure>(
                repository(server).commit("  ", "token"),
            )
            assertEquals(HandleCommitFailure.VALIDATION_FAILED, result.reason)
            assertFalse(result.retryable)
            assertEquals(0, server.requestCount)
        }
    }
}
