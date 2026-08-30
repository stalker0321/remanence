package dev.hryshyn.remanence.core.data.network

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import dev.hryshyn.remanence.core.model.CapsuleId

class RecipientMaterialSyncedRepositoryTest {

    private val capsuleId = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000ca01")

    @Test
    fun sendsExactAuthenticatedEmptyPostWithoutIdempotencyKey() = runTest {
        withServer { server ->
            server.enqueue(MockResponse.Builder().code(204).build())

            val result = repository(server).markMaterialSynced(capsuleId, ACCESS_TOKEN)

            assertEquals(RecipientMaterialSyncedResult.Success(204), result)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(
                "/v1/capsules/${capsuleId.toRestString()}/material-synced",
                request.url.encodedPath,
            )
            assertEquals("Bearer $ACCESS_TOKEN", request.headers["Authorization"])
            assertEquals("0", request.headers["Content-Length"])
            assertNull(request.headers["Idempotency-Key"])
            assertEquals(0L, request.body?.size ?: 0L)
        }
    }

    @Test
    fun empty204ReplayIsTheSameSuccessfulResult() = runTest {
        withServer { server ->
            server.enqueue(MockResponse.Builder().code(204).build())
            server.enqueue(MockResponse.Builder().code(204).build())

            val first = repository(server).markMaterialSynced(capsuleId, ACCESS_TOKEN)
            val replay = repository(server).markMaterialSynced(capsuleId, ACCESS_TOKEN)

            assertEquals(RecipientMaterialSyncedResult.Success(204), first)
            assertEquals(RecipientMaterialSyncedResult.Success(204), replay)
            assertNull(server.takeRequest().headers["Idempotency-Key"])
            assertNull(server.takeRequest().headers["Idempotency-Key"])
        }
    }

    @Test
    fun nonempty204IsRejected() = runTest {
        withServer { server ->
            server.enqueue(MockResponse.Builder().code(204).body("unexpected").build())

            val failure = assertIs<RecipientMaterialSyncedResult.Failure>(
                repository(server).markMaterialSynced(capsuleId, ACCESS_TOKEN),
            )

            assertEquals(RecipientMaterialSyncedFailure.INVALID_RESPONSE, failure.reason)
            assertEquals(204, failure.httpStatus)
            assertFalse(failure.retryable)
        }
    }

    @Test
    fun canonicalProblemsMapToTypedRetryableAndTerminalResults() = runTest {
        withServer { server ->
            val cases = listOf(
                Triple(503, "INTERNAL_ERROR", true) to
                    RecipientMaterialSyncedResult.Failure(
                        RecipientMaterialSyncedFailure.INTERNAL_ERROR,
                        503,
                        true,
                    ),
                Triple(500, "INTERNAL_ERROR", false) to
                    RecipientMaterialSyncedResult.Failure(
                        RecipientMaterialSyncedFailure.INTERNAL_ERROR,
                        500,
                        false,
                    ),
                Triple(429, "RATE_LIMITED", true) to
                    RecipientMaterialSyncedResult.Failure(
                        RecipientMaterialSyncedFailure.RATE_LIMITED,
                        429,
                        true,
                    ),
                Triple(401, "AUTH_INVALID", false) to
                    RecipientMaterialSyncedResult.Failure(
                        RecipientMaterialSyncedFailure.AUTH_INVALID,
                        401,
                        false,
                    ),
                Triple(404, "CAPSULE_NOT_FOUND", false) to
                    RecipientMaterialSyncedResult.Failure(
                        RecipientMaterialSyncedFailure.CAPSULE_NOT_FOUND,
                        404,
                        false,
                    ),
                Triple(422, "VALIDATION_FAILED", false) to
                    RecipientMaterialSyncedResult.Failure(
                        RecipientMaterialSyncedFailure.VALIDATION_FAILED,
                        422,
                        false,
                    ),
            )
            cases.forEach { (responseCase, expected) ->
                val (status, code, retryable) = responseCase
                server.enqueue(
                    MockResponse.Builder()
                        .code(status)
                        .setHeader("Content-Type", "application/problem+json")
                        .body(problemJson(code, status, retryable))
                        .build(),
                )

                val actual = repository(server).markMaterialSynced(capsuleId, ACCESS_TOKEN)

                assertEquals(expected, actual)
                assertFalse(actual.toString().contains(SECRET_DETAIL))
                assertFalse(actual.toString().contains(ACCESS_TOKEN))
            }
        }
    }

    @Test
    fun malformedUnknownAndMismatchedResponsesFailClosedWithSafeFallback() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(503)
                    .setHeader("Content-Type", "application/problem+json")
                    .body("not-json")
                    .build(),
            )
            val malformed503 = assertIs<RecipientMaterialSyncedResult.Failure>(
                repository(server).markMaterialSynced(capsuleId, ACCESS_TOKEN),
            )
            assertEquals(RecipientMaterialSyncedFailure.HTTP, malformed503.reason)
            assertEquals(503, malformed503.httpStatus)
            assertEquals(true, malformed503.retryable)

            server.enqueue(
                MockResponse.Builder()
                    .code(418)
                    .setHeader("Content-Type", "application/problem+json")
                    .body(problemJson("UNKNOWN", 418, false))
                    .build(),
            )
            val unknown = assertIs<RecipientMaterialSyncedResult.Failure>(
                repository(server).markMaterialSynced(capsuleId, ACCESS_TOKEN),
            )
            assertEquals(RecipientMaterialSyncedFailure.HTTP, unknown.reason)
            assertEquals(418, unknown.httpStatus)
            assertFalse(unknown.retryable)

            server.enqueue(
                MockResponse.Builder()
                    .code(422)
                    .setHeader("Content-Type", "application/problem+json")
                    .body(problemJson("VALIDATION_FAILED", 422, true))
                    .build(),
            )
            val mismatched = assertIs<RecipientMaterialSyncedResult.Failure>(
                repository(server).markMaterialSynced(capsuleId, ACCESS_TOKEN),
            )
            assertEquals(RecipientMaterialSyncedFailure.HTTP, mismatched.reason)
            assertEquals(422, mismatched.httpStatus)
            assertFalse(mismatched.retryable)

            server.enqueue(MockResponse.Builder().code(418).body("proxy").build())
            val unexpected = assertIs<RecipientMaterialSyncedResult.Failure>(
                repository(server).markMaterialSynced(capsuleId, ACCESS_TOKEN),
            )
            assertEquals(RecipientMaterialSyncedFailure.INVALID_RESPONSE, unexpected.reason)
            assertFalse(unexpected.retryable)
        }
    }

    @Test
    fun networkFailureIsRetryable() = runTest {
        val server = MockWebServer()
        server.start()
        val baseUrl = ApiBaseUrl.parse(server.url("/").toString())
        server.close()

        val failure = assertIs<RecipientMaterialSyncedResult.Failure>(
            RecipientMaterialSyncedRepository(HttpClientFactory.create(), baseUrl)
                .markMaterialSynced(capsuleId, ACCESS_TOKEN),
        )
        assertEquals(RecipientMaterialSyncedFailure.NETWORK, failure.reason)
        assertNull(failure.httpStatus)
        assertEquals(true, failure.retryable)
    }

    @Test
    fun cancellationIsRethrownExactly() = runTest {
        val expected = CancellationException("caller cancelled")
        val client = OkHttpClient.Builder()
            .addInterceptor {
                throw expected
            }
            .build()

        val actual = assertFailsWith<CancellationException> {
            RecipientMaterialSyncedRepository(
                client,
                ApiBaseUrl.parse("http://localhost/"),
            ).markMaterialSynced(capsuleId, ACCESS_TOKEN)
        }

        assertSame(expected, actual)
    }

    @Test
    fun canonicalCapsuleAndAccessTokenAreValidatedBeforeNetwork() = runTest {
        withServer { server ->
            assertFailsWith<IllegalArgumentException> {
                repository(server).markMaterialSynced(capsuleId, " \t")
            }
            assertEquals(0, server.requestCount)
        }
        assertFailsWith<IllegalArgumentException> {
            CapsuleId.parseRest(capsuleId.toRestString().uppercase())
        }
    }

    private suspend fun <T> withServer(block: suspend (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server)
        } finally {
            server.close()
        }
    }

    private fun repository(server: MockWebServer): RecipientMaterialSyncedRepository =
        RecipientMaterialSyncedRepository(
            HttpClientFactory.create(),
            ApiBaseUrl.parse(server.url("/").toString()),
        )

    private fun problemJson(code: String, status: Int, retryable: Boolean): String =
        """
        {"type":"https://remanence.invalid/problems/$code","title":"safe","status":$status,"code":"$code","detail":"$SECRET_DETAIL","request_id":"0198f0a0-0000-7000-8000-00000000ac01","retryable":$retryable}
        """.trimIndent()

    private companion object {
        const val ACCESS_TOKEN = "pm_at_live"
        const val SECRET_DETAIL = "private detail must not cross the repository boundary"
    }
}
