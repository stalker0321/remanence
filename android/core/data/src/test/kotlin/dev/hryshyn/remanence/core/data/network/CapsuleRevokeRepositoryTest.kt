package dev.hryshyn.remanence.core.data.network

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import dev.hryshyn.remanence.core.model.CapsuleId

class CapsuleRevokeRepositoryTest {

    private val capsuleId = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000ca01")

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
    fun freshAndReplayResponsesUseTheSameEmptyAuthenticatedPost() = runTest {
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
            val first = assertIs<CapsuleRevokeResult.Success>(repository.revoke(capsuleId, "pm_at_live"))
            val replay = assertIs<CapsuleRevokeResult.Success>(repository.revoke(capsuleId, "pm_at_live"))

            assertEquals(false, first.revoke.isReplay)
            assertEquals(true, replay.revoke.isReplay)
            assertEquals(CapsuleRevokeState.REVOKED, first.revoke.state)
            assertEquals(CapsuleRevokeState.REVOKED, replay.revoke.state)
            assertEquals(200, first.httpStatus)
            assertEquals(200, replay.httpStatus)

            repeat(2) {
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/v1/capsules/${capsuleId.toRestString()}/revoke", request.url.encodedPath)
                assertEquals("Bearer pm_at_live", request.headers["Authorization"])
                assertEquals(0, request.body?.size ?: 0)
                assertNull(request.headers["Content-Type"])
            }
        }
    }

    @Test
    fun mapsDocumentedProblemsAndKeepsServerRetryability() = runTest {
        withServer { server ->
            val cases = listOf(
                401 to ("AUTH_INVALID" to false),
                422 to ("VALIDATION_FAILED" to false),
                404 to ("CAPSULE_NOT_FOUND" to false),
                409 to ("CAPSULE_STATE_INVALID" to false),
                409 to ("WINDOW_EXPIRED" to false),
                429 to ("RATE_LIMITED" to true),
                500 to ("INTERNAL_ERROR" to false),
                503 to ("INTERNAL_ERROR" to true),
            )
            cases.forEach { (status, codeAndRetryable) ->
                val (code, retryable) = codeAndRetryable
                server.enqueue(
                    MockResponse.Builder()
                        .code(status)
                        .setHeader("Content-Type", "application/problem+json")
                        .body(problemJson(code, status, retryable))
                        .build(),
                )
            }

            cases.forEach { (_, codeAndRetryable) ->
                val (code, retryable) = codeAndRetryable
                val failure = assertIs<CapsuleRevokeResult.Failure>(repository(server).revoke(capsuleId, "token"))
                assertEquals(code.toFailure(), failure.reason)
                assertEquals(retryable, failure.retryable)
            }
        }
    }

    @Test
    fun malformedSuccessAndUnknownProblemsFailClosedWithoutLeakingBody() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body("{\"capsule_id\":\"${UUID.randomUUID()}\",\"state\":\"REVOKED\",\"is_replay\":false}")
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder()
                    .code(418)
                    .setHeader("Content-Type", "application/problem+json")
                    .body(problemJson("UNKNOWN_PRIVATE_CODE", 418, false))
                    .build(),
            )

            val invalid = assertIs<CapsuleRevokeResult.Failure>(repository(server).revoke(capsuleId, "token"))
            assertEquals(CapsuleRevokeFailure.INVALID_RESPONSE, invalid.reason)

            val unknown = assertIs<CapsuleRevokeResult.Failure>(repository(server).revoke(capsuleId, "token"))
            assertEquals(CapsuleRevokeFailure.HTTP, unknown.reason)
            assertFalse(unknown.toString().contains("private detail"))
        }
    }

    @Test
    fun malformedProxyAndNetworkFailuresRetainFallbackRetryability() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(503)
                    .setHeader("Content-Type", "application/problem+json")
                    .body("not-json")
                    .build(),
            )
            val malformed = assertIs<CapsuleRevokeResult.Failure>(repository(server).revoke(capsuleId, "token"))
            assertEquals(CapsuleRevokeFailure.HTTP, malformed.reason)
            assertTrue(malformed.retryable)
        }

        val server = MockWebServer()
        server.start()
        val baseUrl = ApiBaseUrl.parse(server.url("/").toString())
        server.close()
        val network = assertIs<CapsuleRevokeResult.Failure>(
            CapsuleRevokeRepository.create(baseUrl).revoke(capsuleId, "token"),
        )
        assertEquals(CapsuleRevokeFailure.NETWORK, network.reason)
        assertTrue(network.retryable)
    }

    private fun repository(server: MockWebServer): CapsuleRevokeRepository =
        CapsuleRevokeRepository.create(ApiBaseUrl.parse(server.url("/").toString()))

    private fun successJson(isReplay: Boolean): String =
        """{"capsule_id":"${capsuleId.toRestString()}","state":"REVOKED","is_replay":$isReplay}"""

    private fun problemJson(code: String, status: Int, retryable: Boolean): String =
        """{"type":"https://remanence.invalid/problems/${code.lowercase()}","title":"safe","status":$status,"code":"$code","detail":"private detail","request_id":"0198f0a0-0000-7000-8000-00000000ac01","retryable":$retryable}"""

    private fun String.toFailure(): CapsuleRevokeFailure = when (this) {
        "AUTH_INVALID" -> CapsuleRevokeFailure.AUTH_INVALID
        "VALIDATION_FAILED" -> CapsuleRevokeFailure.VALIDATION_FAILED
        "CAPSULE_NOT_FOUND" -> CapsuleRevokeFailure.CAPSULE_NOT_FOUND
        "CAPSULE_STATE_INVALID" -> CapsuleRevokeFailure.CAPSULE_STATE_INVALID
        "WINDOW_EXPIRED" -> CapsuleRevokeFailure.WINDOW_EXPIRED
        "RATE_LIMITED" -> CapsuleRevokeFailure.RATE_LIMITED
        "INTERNAL_ERROR" -> CapsuleRevokeFailure.INTERNAL_ERROR
        else -> error("unmapped test failure $this")
    }
}
