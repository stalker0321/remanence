package dev.hryshyn.remanence.core.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class MusicSearchRepositoryTest {

    @Test
    fun searchUsesCanonicalPathQueryAndAuthenticatedHeaders() = runTest {
        withServer { server ->
            server.enqueue(json(200, successJson()))

            val result = repository(server).search("arctic monkeys 505", limit = 10, offset = 0, ACCESS_TOKEN)

            val hits = assertIs<MusicSearchResult.Hits>(result)
            assertEquals(2, hits.page.total)
            assertEquals(0, hits.page.offset)
            assertEquals(2, hits.page.hits.size)
            assertEquals("505", hits.page.hits[0].title)
            assertEquals(listOf("Arctic Monkeys"), hits.page.hits[0].artists)
            assertEquals("Favourite Worst Nightmare", hits.page.hits[0].release)
            assertEquals(2007, hits.page.hits[0].year)
            assertEquals(253000L, hits.page.hits[0].durationMs)
            assertTrue(hits.page.hits[0].artworkAvailable)
            assertNull(hits.page.hits[0].version)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/music/v1/search", request.url.encodedPath)
            assertEquals("arctic monkeys 505", request.url.queryParameter("q"))
            assertEquals("10", request.url.queryParameter("limit"))
            assertEquals("0", request.url.queryParameter("offset"))
            assertEquals("Bearer $ACCESS_TOKEN", request.headers["Authorization"])
            assertEquals("application/json", request.headers["Accept"])
        }
    }

    @Test
    fun cyrillicQueryReachesWireIntact() = runTest {
        withServer { server ->
            server.enqueue(json(200, successJson(title = "Группа крови", artistsJson = "[\"Кино\"]")))

            val result = repository(server).search("Кино группа крови", accessToken = ACCESS_TOKEN)

            val hits = assertIs<MusicSearchResult.Hits>(result)
            assertEquals("Группа крови", hits.page.hits[0].title)
            val request = server.takeRequest()
            assertEquals("Кино группа крови", request.url.queryParameter("q"))
        }
    }

    @Test
    fun blankOrOversizedInputFailsClosedWithoutNetworkCall() = runTest {
        withServer { server ->
            for (query in listOf("", "   ", "x".repeat(101))) {
                val result = repository(server).search(query, accessToken = ACCESS_TOKEN)
                val failure = assertIs<MusicSearchResult.Failure>(result)
                assertEquals(MusicSearchFailure.VALIDATION_FAILED, failure.reason)
                assertFalse(failure.retryable)
                assertNull(failure.httpStatus)
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun outOfRangeLimitAndOffsetFailClosedWithoutNetworkCall() = runTest {
        withServer { server ->
            for ((limit, offset) in listOf(0 to 0, 21 to 0, 10 to -1, 10 to 201)) {
                val result = repository(server).search("505", limit = limit, offset = offset, accessToken = ACCESS_TOKEN)
                val failure = assertIs<MusicSearchResult.Failure>(result)
                assertEquals(MusicSearchFailure.VALIDATION_FAILED, failure.reason)
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun authInvalidMapsToTypedFailure() = runTest {
        withServer { server ->
            server.enqueue(problem(401, "AUTH_INVALID", retryable = false))

            val failure = assertIs<MusicSearchResult.Failure>(
                repository(server).search("505", accessToken = ACCESS_TOKEN),
            )
            assertEquals(MusicSearchFailure.AUTH_INVALID, failure.reason)
            assertEquals(401, failure.httpStatus)
            assertFalse(failure.retryable)
        }
    }

    @Test
    fun rateLimitedMapsToRetryableFailure() = runTest {
        withServer { server ->
            server.enqueue(problem(429, "RATE_LIMITED", retryable = true))

            val failure = assertIs<MusicSearchResult.Failure>(
                repository(server).search("505", accessToken = ACCESS_TOKEN),
            )
            assertEquals(MusicSearchFailure.RATE_LIMITED, failure.reason)
            assertEquals(429, failure.httpStatus)
            assertTrue(failure.retryable)
        }
    }

    @Test
    fun unwiredBackend503MapsToUnavailableRetryable() = runTest {
        withServer { server ->
            server.enqueue(problem(503, "INTERNAL_ERROR", retryable = true))

            val failure = assertIs<MusicSearchResult.Failure>(
                repository(server).search("505", accessToken = ACCESS_TOKEN),
            )
            assertEquals(MusicSearchFailure.UNAVAILABLE, failure.reason)
            assertEquals(503, failure.httpStatus)
            assertTrue(failure.retryable)
        }
    }

    @Test
    fun validationFailed422MapsToTypedFailure() = runTest {
        withServer { server ->
            server.enqueue(problem(422, "VALIDATION_FAILED", retryable = false))

            val failure = assertIs<MusicSearchResult.Failure>(
                repository(server).search("505", accessToken = ACCESS_TOKEN),
            )
            assertEquals(MusicSearchFailure.VALIDATION_FAILED, failure.reason)
            assertEquals(422, failure.httpStatus)
        }
    }

    @Test
    fun malformedBodyFailsClosed() = runTest {
        withServer { server ->
            server.enqueue(json(200, """{"results": "not-a-list", "total": 1, "offset": 0}"""))
            val failure = assertIs<MusicSearchResult.Failure>(
                repository(server).search("505", accessToken = ACCESS_TOKEN),
            )
            assertEquals(MusicSearchFailure.INVALID_RESPONSE, failure.reason)
            assertFalse(failure.retryable)
        }
    }

    @Test
    fun offsetEchoMismatchAndOverLimitPageFailClosed() = runTest {
        withServer { server ->
            server.enqueue(json(200, successJson(offset = 5)))
            val echoMismatch = assertIs<MusicSearchResult.Failure>(
                repository(server).search("505", limit = 10, offset = 0, accessToken = ACCESS_TOKEN),
            )
            assertEquals(MusicSearchFailure.INVALID_RESPONSE, echoMismatch.reason)

            server.enqueue(json(200, successJson()))
            val overLimit = assertIs<MusicSearchResult.Failure>(
                repository(server).search("505", limit = 1, offset = 0, accessToken = ACCESS_TOKEN),
            )
            assertEquals(MusicSearchFailure.INVALID_RESPONSE, overLimit.reason)
        }
    }

    @Test
    fun failureBodiesNeverExposePrivateDetails() = runTest {
        withServer { server ->
            server.enqueue(problem(429, "RATE_LIMITED", retryable = true, detail = PRIVATE_DETAIL))
            val result = repository(server).search("505", accessToken = ACCESS_TOKEN)
            assertFalse(result.toString().contains(PRIVATE_DETAIL))
        }
    }

    private fun repository(server: MockWebServer): MusicSearchRepository =
        MusicSearchRepository(
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

    private fun problem(status: Int, code: String, retryable: Boolean, detail: String = "safe problem detail"): MockResponse =
        MockResponse.Builder()
            .code(status)
            .setHeader("Content-Type", "application/problem+json")
            .body(
                """
                {
                  "type": "https://example.invalid/problems/$code",
                  "title": "private title",
                  "status": $status,
                  "code": "$code",
                  "detail": "$detail",
                  "request_id": "private-request-id",
                  "retryable": $retryable
                }
                """.trimIndent(),
            )
            .build()

    private fun successJson(
        title: String = "505",
        artistsJson: String = "[\"Arctic Monkeys\"]",
        offset: Int = 0,
    ): String =
        """
        {
          "results": [
            {
              "id": "be30e36b-1111-4111-8111-000000000001",
              "title": "$title",
              "artists": $artistsJson,
              "version": null,
              "release": "Favourite Worst Nightmare",
              "year": 2007,
              "durationMs": 253000,
              "artworkAvailable": true
            },
            {
              "id": "be30e36b-2222-4222-8222-000000000002",
              "title": "$title",
              "artists": $artistsJson,
              "version": "Live at the Apollo",
              "release": "Live at the Apollo",
              "year": 2008,
              "durationMs": 261000,
              "artworkAvailable": true
            }
          ],
          "total": 2,
          "offset": $offset
        }
        """.trimIndent()

    private companion object {
        const val ACCESS_TOKEN = "pm_at_live"
        const val PRIVATE_DETAIL = "private-request-detail-secret"
    }
}
