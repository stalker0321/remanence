package dev.hryshyn.remanence.core.data.network

import dev.hryshyn.remanence.core.model.UserId
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class IncomingTombstoneRepositoryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun authenticatedFeedUsesSinceAndReturnsOnlyRedactedIdentity() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body(
                    """{"items":[{"capsule_id":"$CAPSULE","revoked_at":"2026-08-28T00:00:00Z"}],"has_more":false,"next_cursor":"r1"}""",
                )
                .build(),
        )
        val result = IncomingTombstoneRepository(
            OkHttpClient(),
            ApiBaseUrl.parse(server.url("/").toString()),
        ).fetchPage(
            ownerUserId = OWNER,
            cursor = "opaque-since",
            limit = 50,
            accessToken = "access-token",
        )

        val success = assertIs<IncomingTombstoneResult.Success>(result)
        assertEquals(CAPSULE, success.page.items.single().capsuleId.toRestString())
        assertEquals("r1", success.page.nextCursor)
        val request = server.takeRequest()
        assertEquals("/v1/incoming/tombstones", request.url.encodedPath)
        assertEquals("opaque-since", request.url.queryParameter("since"))
        assertEquals("50", request.url.queryParameter("limit"))
        assertEquals("Bearer access-token", request.headers["Authorization"])
    }

    @Test
    fun unsupportedFeedStatusesBecomeEmptyCapabilityNoOps() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(404)
                .setHeader("Content-Type", "application/problem+json")
                .body(
                    """{"type":"about:blank","title":"Route not found","status":404,"code":"ROUTE_NOT_FOUND","detail":"redacted","request_id":"redacted","retryable":false}""",
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(405)
                .setHeader("Content-Type", "application/problem+json")
                .body(
                    """{"type":"about:blank","title":"Method not allowed","status":405,"code":"METHOD_NOT_ALLOWED","detail":"redacted","request_id":"redacted","retryable":false}""",
                )
                .build(),
        )

        val repository = IncomingTombstoneRepository(
            OkHttpClient(),
            ApiBaseUrl.parse(server.url("/").toString()),
        )

        listOf(404, 405).forEach { status ->
            val cursor = "cursor-$status"
            val success = assertIs<IncomingTombstoneResult.Success>(
                repository.fetchPage(OWNER, cursor, 50, "access-token"),
            )
            assertTrue(success.capabilityUnsupported)
            assertTrue(success.page.items.isEmpty())
            assertFalse(success.page.hasMore)
            assertEquals(cursor, success.page.nextCursor)
            assertEquals(status, success.httpStatus)
        }
    }

    @Test
    fun notFoundRejectsEveryMalformedCompatibilityResponse() = runTest {
        assertMalformedCompatibilityResponsesRejected(404)
    }

    @Test
    fun methodNotAllowedRejectsEveryMalformedCompatibilityResponse() = runTest {
        assertMalformedCompatibilityResponsesRejected(405)
    }

    @Test
    fun ioExceptionIsReturnedAsRetryableNetworkFailure() = runTest {
        val transportFailure = IOException("injected transport failure")
        val client = OkHttpClient.Builder()
            .addInterceptor { throw transportFailure }
            .build()

        val result = IncomingTombstoneRepository(
            client,
            ApiBaseUrl.parse(server.url("/").toString()),
        ).fetchPage(OWNER, "transport-cursor", 50, "access-token")

        val failure = assertIs<IncomingTombstoneResult.Failure>(result)
        assertEquals(IncomingTombstoneFailure.NETWORK, failure.reason)
        assertEquals(null, failure.httpStatus)
        assertTrue(failure.retryable)
    }

    @Test
    fun callerCancellationPropagatesInsteadOfBecomingNetworkFailure() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .onResponseStart(SocketEffect.Stall)
                .build(),
        )
        val operation = async(Dispatchers.IO) {
            repository().fetchPage(OWNER, "cancel-cursor", 50, "access-token")
        }

        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        operation.cancel()
        assertFailsWith<CancellationException> { operation.await() }
        assertTrue(operation.isCancelled)
    }

    @Test
    fun invalidProblemIsClassifiedWithoutRetainingDetail() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .setHeader("Content-Type", "application/problem+json")
                .body(
                    """{"type":"https://invalid/problem","title":"Authentication invalid","status":401,"code":"AUTH_INVALID","detail":"secret-email@example.com","request_id":"request-secret","retryable":false}""",
                )
                .build(),
        )

        val result = IncomingTombstoneRepository(
            OkHttpClient(),
            ApiBaseUrl.parse(server.url("/").toString()),
        ).fetchPage(OWNER, null, 50, "access-token")

        val failure = assertIs<IncomingTombstoneResult.Failure>(result)
        assertEquals(IncomingTombstoneFailure.AUTH_INVALID, failure.reason)
        assertFalse(failure.toString().contains("secret-email@example.com"))
        assertFalse(failure.toString().contains("request-secret"))
    }

    @Test
    fun authServerAndMalformedResponsesAreNeverCapabilityNoOps() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .setHeader("Content-Type", "application/problem+json")
                .body(
                    """{"type":"about:blank","title":"Authentication invalid","status":401,"code":"AUTH_INVALID","detail":"redacted","request_id":"redacted","retryable":false}""",
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .setHeader("Content-Type", "application/problem+json")
                .body(
                    """{"type":"about:blank","title":"Forbidden","status":403,"code":"AUTH_INVALID","detail":"redacted","request_id":"redacted","retryable":false}""",
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(500)
                .setHeader("Content-Type", "application/problem+json")
                .body(
                    """{"type":"about:blank","title":"Internal error","status":500,"code":"INTERNAL_ERROR","detail":"redacted","request_id":"redacted","retryable":false}""",
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("{malformed")
                .build(),
        )

        val repository = IncomingTombstoneRepository(
            OkHttpClient(),
            ApiBaseUrl.parse(server.url("/").toString()),
        )

        val auth = assertIs<IncomingTombstoneResult.Failure>(
            repository.fetchPage(OWNER, null, 50, "access-token"),
        )
        assertEquals(IncomingTombstoneFailure.AUTH_INVALID, auth.reason)

        val forbidden = assertIs<IncomingTombstoneResult.Failure>(
            repository.fetchPage(OWNER, null, 50, "access-token"),
        )
        assertEquals(IncomingTombstoneFailure.INVALID_RESPONSE, forbidden.reason)

        val serverFailure = assertIs<IncomingTombstoneResult.Failure>(
            repository.fetchPage(OWNER, null, 50, "access-token"),
        )
        assertEquals(IncomingTombstoneFailure.INTERNAL_ERROR, serverFailure.reason)
        assertTrue(serverFailure.retryable.not())

        val malformed = assertIs<IncomingTombstoneResult.Failure>(
            repository.fetchPage(OWNER, null, 50, "access-token"),
        )
        assertEquals(IncomingTombstoneFailure.INVALID_RESPONSE, malformed.reason)
        assertFalse(malformed.retryable)
    }

    @Test
    fun problemShapedUnsupportedStatusRemainsFailClosed() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(404)
                .setHeader("Content-Type", "application/problem+json")
                .body(
                    """{"type":"about:blank","title":"Not found","status":404,"code":"CAPSULE_NOT_FOUND","detail":"redacted","request_id":"redacted","retryable":false}""",
                )
                .build(),
        )

        val result = IncomingTombstoneRepository(
            OkHttpClient(),
            ApiBaseUrl.parse(server.url("/").toString()),
        ).fetchPage(OWNER, null, 50, "access-token")

        assertEquals(
            IncomingTombstoneFailure.INVALID_RESPONSE,
            assertIs<IncomingTombstoneResult.Failure>(result).reason,
        )
    }

    @Test
    fun malformedUnsupportedProblemIsNotACompatibilityNoOp() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(405)
                .setHeader("Content-Type", "application/problem+json")
                .body("{malformed")
                .build(),
        )

        val result = IncomingTombstoneRepository(
            OkHttpClient(),
            ApiBaseUrl.parse(server.url("/").toString()),
        ).fetchPage(OWNER, null, 50, "access-token")

        assertEquals(
            IncomingTombstoneFailure.INVALID_RESPONSE,
            assertIs<IncomingTombstoneResult.Failure>(result).reason,
        )
    }

    @Test
    fun tokenAndLimitValidationHappensBeforeNetwork() = runTest {
        val repository = IncomingTombstoneRepository(
            OkHttpClient(),
            ApiBaseUrl.parse(server.url("/").toString()),
        )

        val result = repository.fetchPage(OWNER, null, 0, "")

        assertEquals(IncomingTombstoneFailure.VALIDATION_FAILED,
            assertIs<IncomingTombstoneResult.Failure>(result).reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun malformedNon2xxUsesHttpFallbackRetryabilityButMalformed200IsTerminal() = runTest {
        val repository = IncomingTombstoneRepository(
            OkHttpClient(),
            ApiBaseUrl.parse(server.url("/").toString()),
        )
        listOf(
            Triple(429, "text/plain", ""),
            Triple(502, "text/html", "<html>proxy unavailable</html>"),
            Triple(503, "application/json", "{malformed"),
        ).forEach { (status, contentType, body) ->
            server.enqueue(
                MockResponse.Builder()
                    .code(status)
                    .setHeader("Content-Type", contentType)
                    .body(body)
                    .build(),
            )
        }
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("not-json")
                .build(),
        )

        listOf(429, 502, 503).forEach { status ->
            val failure = assertIs<IncomingTombstoneResult.Failure>(
                repository.fetchPage(OWNER, null, 50, "access-token"),
            )
            assertEquals(IncomingTombstoneFailure.INVALID_RESPONSE, failure.reason)
            assertEquals(status, failure.httpStatus)
            assertTrue(failure.retryable)
        }
        val malformed200 = assertIs<IncomingTombstoneResult.Failure>(
            repository.fetchPage(OWNER, null, 50, "access-token"),
        )
        assertEquals(IncomingTombstoneFailure.INVALID_RESPONSE, malformed200.reason)
        assertEquals(200, malformed200.httpStatus)
        assertFalse(malformed200.retryable)
    }

    private companion object {
        val OWNER = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a001")
        const val CAPSULE = "0198f0a0-0000-7000-8000-00000000c001"
    }

    private suspend fun assertMalformedCompatibilityResponsesRejected(status: Int) {
        val expectedCode = if (status == 404) "ROUTE_NOT_FOUND" else "METHOD_NOT_ALLOWED"
        val wrongCode = if (status == 404) "METHOD_NOT_ALLOWED" else "ROUTE_NOT_FOUND"
        val malformedResponses = listOf(
            "empty" to ("application/problem+json" to ""),
            "plain text" to ("application/problem+json" to "unsupported"),
            "text/html" to ("text/html" to "<html>unsupported</html>"),
            "malformed JSON" to ("application/problem+json" to "{malformed"),
            "wrong content type" to (
                "application/json" to canonicalProblem(status, expectedCode)
            ),
            "wrong code" to (
                "application/problem+json" to canonicalProblem(status, wrongCode)
            ),
            "status mismatch" to (
                "application/problem+json" to canonicalProblem(if (status == 404) 405 else 404, expectedCode)
            ),
        )
        malformedResponses.forEach { (label, response) ->
            server.enqueue(
                MockResponse.Builder()
                    .code(status)
                    .setHeader("Content-Type", response.first)
                    .body(response.second)
                    .build(),
            )
            val result = repository().fetchPage(OWNER, "malformed-$status", 50, "access-token")
            val failure = assertIs<IncomingTombstoneResult.Failure>(result, label)
            assertEquals(IncomingTombstoneFailure.INVALID_RESPONSE, failure.reason, label)
            assertEquals(status, failure.httpStatus, label)
        }
    }

    private fun canonicalProblem(status: Int, code: String): String =
        """{"type":"about:blank","title":"redacted","status":$status,"code":"$code","detail":"redacted","request_id":"redacted","retryable":false}"""

    private fun repository() = IncomingTombstoneRepository(
        OkHttpClient(),
        ApiBaseUrl.parse(server.url("/").toString()),
    )
}
