package dev.hryshyn.remanence.core.data.network

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import dev.hryshyn.remanence.core.model.UserId

class RecipientUserLookupRepositoryTest {

    private val userId = UserId.parseRest("1f0a1234-5678-4abc-9def-aabbccdd1001")
    private val otherUserId = "1f0a1234-5678-4abc-9def-aabbccdd1002"
    private val keyBundleId = "2f0a1234-5678-4abc-9def-aabbccdd2002"

    @Test
    fun lookupUsesCanonicalUserPathAndAuthenticatedHeaders() = runTest {
        withServer { server ->
            server.enqueue(json(200, successJson()))

            val result = repository(server).lookup(userId, ACCESS_TOKEN)

            val found = assertIs<RecipientUserLookupResult.Found>(result)
            assertEquals(userId, found.snapshot.userId)
            assertEquals("@mykola", found.snapshot.handle.toDisplayString())
            assertEquals(keyBundleId, found.snapshot.keyBundleId.toRestString())
            assertEquals(SUPPORTED_SUITE, found.snapshot.suite)
            assertEquals(1, found.snapshot.protocolVersion)
            assertEquals("ACTIVE", found.snapshot.keyBundleStatus)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/directory/users/${userId.toRestString()}", request.url.encodedPath)
            assertEquals("Bearer $ACCESS_TOKEN", request.headers["Authorization"])
            assertEquals("application/json", request.headers["Accept"])
        }
    }

    @Test
    fun responseUserIdMismatchFailsClosed() = runTest {
        withServer { server ->
            server.enqueue(json(200, successJson(userJsonId = otherUserId, bundleJsonId = userId.toRestString())))

            val failure = assertIs<RecipientUserLookupResult.Failure>(repository(server).lookup(userId, ACCESS_TOKEN))
            assertEquals(RecipientUserLookupFailure.INVALID_RESPONSE, failure.reason)
            assertFalse(failure.retryable)
        }
    }

    @Test
    fun keyBundleUserIdMismatchFailsClosed() = runTest {
        withServer { server ->
            server.enqueue(json(200, successJson(bundleJsonId = otherUserId)))

            val failure = assertIs<RecipientUserLookupResult.Failure>(repository(server).lookup(userId, ACCESS_TOKEN))
            assertEquals(RecipientUserLookupFailure.INVALID_RESPONSE, failure.reason)
            assertFalse(failure.retryable)
        }
    }

    @Test
    fun onlyActiveSupportedBundleIsAccepted() = runTest {
        for (status in listOf("RETIRED", "REVOKED")) {
            withServer { server ->
                server.enqueue(json(200, successJson(status = status)))
                val result = repository(server).lookup(userId, ACCESS_TOKEN)
                val failure = assertIs<RecipientUserLookupResult.Failure>(result)
                assertEquals(RecipientUserLookupFailure.INVALID_RESPONSE, failure.reason)
                assertFalse(failure.retryable)
            }
        }

        for ((field, value) in listOf("suite" to "UNSUPPORTED_SUITE", "protocol_version" to "99")) {
            withServer { server ->
                val body = successJson().replace(
                    if (field == "suite") {
                        "\"suite\": \"$SUPPORTED_SUITE\""
                    } else {
                        "\"protocol_version\": 1"
                    },
                    if (field == "suite") {
                        "\"suite\": \"$value\""
                    } else {
                        "\"protocol_version\": $value"
                    },
                )
                server.enqueue(json(200, body))
                val result = repository(server).lookup(userId, ACCESS_TOKEN)
                val failure = assertIs<RecipientUserLookupResult.Failure>(result)
                assertEquals(RecipientUserLookupFailure.INVALID_RESPONSE, failure.reason)
                assertFalse(failure.retryable)
            }
        }
    }

    @Test
    fun canonicalUserNotFoundIsDistinctAndDoesNotRetainProblemDetails() = runTest {
        withServer { server ->
            server.enqueue(
                problem(
                    status = 404,
                    code = "USER_NOT_FOUND",
                    retryable = false,
                    detail = PRIVATE_DETAIL,
                ),
            )

            val result = repository(server).lookup(userId, ACCESS_TOKEN)

            assertEquals(RecipientUserLookupResult.NotFound, result)
            assertFalse(result.toString().contains(PRIVATE_DETAIL))
        }
    }

    @Test
    fun canonicalTransientAndOrdinaryInternalErrorsPreserveServerRetryability() = runTest {
        val cases = listOf(
            Triple(429, "RATE_LIMITED", true) to RecipientUserLookupFailure.RATE_LIMITED,
            Triple(503, "INTERNAL_ERROR", true) to RecipientUserLookupFailure.INTERNAL_ERROR,
            Triple(500, "INTERNAL_ERROR", false) to RecipientUserLookupFailure.INTERNAL_ERROR,
            Triple(401, "AUTH_INVALID", false) to RecipientUserLookupFailure.AUTH_INVALID,
            Triple(422, "VALIDATION_FAILED", false) to RecipientUserLookupFailure.INVALID_RESPONSE,
        )
        for ((responseCase, expectedReason) in cases) {
            withServer { server ->
                val (status, code, retryable) = responseCase
                server.enqueue(problem(status, code, retryable))
                val result = repository(server).lookup(userId, ACCESS_TOKEN)
                val failure = assertIs<RecipientUserLookupResult.Failure>(result)
                assertEquals(expectedReason, failure.reason)
                assertEquals(retryable, failure.retryable)
                assertEquals(status, failure.httpStatus)
            }
        }
    }

    @Test
    fun contradictoryProblemTupleFallsBackToActualHttpStatus() = runTest {
        val cases = listOf(
            Triple(503, 500, true),
            Triple(503, 503, false),
            Triple(429, 503, false),
        )
        for ((actualStatus, bodyStatus, bodyRetryable) in cases) {
            withServer { server ->
                server.enqueue(problem(actualStatus, "INTERNAL_ERROR", bodyRetryable, statusInBody = bodyStatus))
                val result = repository(server).lookup(userId, ACCESS_TOKEN)
                val failure = assertIs<RecipientUserLookupResult.Failure>(result)
                assertEquals(actualStatus, failure.httpStatus)
                assertEquals(actualStatus == 429 || actualStatus in 500..599, failure.retryable)
                assertFalse(failure.toString().contains(PRIVATE_DETAIL))
            }
        }
    }

    @Test
    fun malformedOversizedAndWrongTypeTransientResponsesSafelyRetry() = runTest {
        val cases = listOf(
            MockResponse.Builder()
                .code(503)
                .setHeader("Content-Type", "application/problem+json")
                .body("not-json")
                .build(),
            MockResponse.Builder()
                .code(429)
                .setHeader("Content-Type", "application/problem+json")
                .body("x".repeat(MAX_BODY_BYTES + 1))
                .build(),
            MockResponse.Builder()
                .code(503)
                .setHeader("Content-Type", "application/json")
                .body(PRIVATE_DETAIL)
                .build(),
        )
        for (response in cases) {
            withServer { server ->
                server.enqueue(response)
                val result = repository(server).lookup(userId, ACCESS_TOKEN)
                val failure = assertIs<RecipientUserLookupResult.Failure>(result)
                assertTrue(failure.retryable)
                assertFalse(failure.toString().contains(PRIVATE_DETAIL))
            }
        }
    }

    @Test
    fun malformedUnknownClientAndSuccessfulResponsesDoNotRetry() = runTest {
        val cases = listOf(
            MockResponse.Builder()
                .code(418)
                .setHeader("Content-Type", "application/problem+json")
                .body("malformed")
                .build(),
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("malformed")
                .build(),
        )
        for (response in cases) {
            withServer { server ->
                server.enqueue(response)
                val result = repository(server).lookup(userId, ACCESS_TOKEN)
                val failure = assertIs<RecipientUserLookupResult.Failure>(result)
                assertFalse(failure.retryable)
            }
        }
    }

    @Test
    fun networkFailureIsRetryable() = runTest {
        val result = RecipientUserLookupRepository(
            client = HttpClientFactory.create(),
            baseUrl = ApiBaseUrl.parse("http://127.0.0.1:1/"),
        ).lookup(userId, ACCESS_TOKEN)

        val failure = assertIs<RecipientUserLookupResult.Failure>(result)
        assertEquals(RecipientUserLookupFailure.NETWORK, failure.reason)
        assertTrue(failure.retryable)
        assertFalse(failure.toString().contains(PRIVATE_DETAIL))
    }

    @Test
    fun productionStackRefreshesOnceAndDoesNotRecurse() = runTest {
        val server = MockWebServer()
        val protectedAuthorization = CopyOnWriteArrayList<String?>()
        val refreshAuthorization = CopyOnWriteArrayList<String?>()
        val refreshCount = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/v1/auth/refresh" -> {
                    refreshCount.incrementAndGet()
                    refreshAuthorization += request.headers["Authorization"]
                    MockResponse.Builder()
                        .code(200)
                        .setHeader("Content-Type", "application/json")
                        .body(REFRESH_RESPONSE)
                        .build()
                }
                "/v1/directory/users/${userId.toRestString()}" -> {
                    protectedAuthorization += request.headers["Authorization"]
                    if (request.headers["Authorization"] == "Bearer $OLD_ACCESS") {
                        MockResponse.Builder().code(401).build()
                    } else {
                        json(200, successJson())
                    }
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        try {
            val tokens = AuthTokenHolder(OLD_ACCESS, OLD_REFRESH)
            val rotations = mutableListOf<Pair<String, String>>()
            val sink = object : SessionRotationSink {
                override fun rotate(accessToken: String, refreshToken: String) {
                    rotations += accessToken to refreshToken
                }

                override fun clear() = Unit
            }
            val stack = ProductionApiStack.create(
                baseUrl = ApiBaseUrl.parse(server.url("/").toString()),
                tokens = tokens,
                rotationSink = sink,
            )

            val result = stack.recipientUserLookupRepository.lookup(userId)

            assertIs<RecipientUserLookupResult.Found>(result)
            assertEquals(listOf("Bearer $OLD_ACCESS", "Bearer $NEW_ACCESS"), protectedAuthorization.toList())
            assertEquals(listOf<String?>(null), refreshAuthorization.toList())
            assertEquals(1, refreshCount.get())
            assertEquals(listOf(NEW_ACCESS to NEW_REFRESH), rotations)
            assertEquals(NEW_ACCESS, tokens.accessToken)
        } finally {
            server.close()
        }
    }

    private fun repository(server: MockWebServer): RecipientUserLookupRepository =
        RecipientUserLookupRepository(
            client = HttpClientFactory.create(),
            baseUrl = ApiBaseUrl.parse(server.url("/").toString()),
        )

    private suspend fun <T> withServer(block: suspend (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server)
        } finally {
            server.close()
        }
    }

    private fun json(status: Int, body: String): MockResponse =
        MockResponse.Builder()
            .code(status)
            .setHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun problem(
        status: Int,
        code: String,
        retryable: Boolean,
        detail: String = "safe problem detail",
        statusInBody: Int = status,
    ): MockResponse =
        MockResponse.Builder()
            .code(status)
            .setHeader("Content-Type", "application/problem+json")
            .body(
                """
                {
                  "type": "https://example.invalid/problems/$code",
                  "title": "private title",
                  "status": $statusInBody,
                  "code": "$code",
                  "detail": "$detail",
                  "request_id": "private-request-id",
                  "retryable": $retryable
                }
                """.trimIndent(),
            )
            .build()

    private fun successJson(
        userJsonId: String = userId.toRestString(),
        bundleJsonId: String = userId.toRestString(),
        status: String = "ACTIVE",
    ): String =
        """
        {
          "user": {"user_id": "$userJsonId", "handle": "mykola"},
          "key_bundle": {
            "key_bundle_id": "$keyBundleId",
            "user_id": "$bundleJsonId",
            "suite": "$SUPPORTED_SUITE",
            "protocol_version": 1,
            "encryption_public_keyset": "CIabc123",
            "signing_public_keyset": "CJxyz789",
            "status": "$status",
            "created_at": "2026-08-23T03:00:00Z"
          },
          "directory_version": "9f1c0d2e7a6b4c5d"
        }
        """.trimIndent()

    private companion object {
        const val ACCESS_TOKEN = "pm_at_live"
        const val OLD_ACCESS = "pm_at_old"
        const val OLD_REFRESH = "pm_rt_old"
        const val NEW_ACCESS = "pm_at_new"
        const val NEW_REFRESH = "pm_rt_new"
        const val PRIVATE_DETAIL = "private-request-detail-secret"
        const val MAX_BODY_BYTES = 64 * 1024
        const val SUPPORTED_SUITE = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519"
        const val REFRESH_RESPONSE =
            """
            {
              "session_id": "session-1",
              "access_token": "pm_at_new",
              "access_expires_at": "2026-08-23T04:15:00Z",
              "refresh_token": "pm_rt_new",
              "refresh_expires_at": "2026-09-22T04:00:00Z"
            }
            """
    }
}
