package dev.hryshyn.remanence.core.data.network

import dev.hryshyn.remanence.core.model.UserId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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
}
