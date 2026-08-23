package postmark.core.data.network

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer

class HealthRepositoryTest {
    @Test
    fun availableExactJsonRecordsGetHealthzAndAccept() = runTest {
        withServer { server ->
            server.enqueueJson("""{"status":"ok"}""")
            val repository = repository(server)
            assertEquals(HealthCheckResult.Available, repository.check())
            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertEquals("/healthz", recorded.url.encodedPath)
            assertEquals("application/json", recorded.headers["Accept"])
            assertEquals(0L, recorded.bodySize)
        }
    }

    @Test
    fun baseUrlPathPrefixResolvesApiHealthz() = runTest {
        withServer { server ->
            server.enqueueJson("""{"status":"ok"}""")
            val repository = repository(server, "/api/")
            assertEquals(HealthCheckResult.Available, repository.check())
            assertEquals("/api/healthz", server.takeRequest().url.encodedPath)
        }
    }

    @Test
    fun http503ReturnsHttpStatusAndIgnoresMalformedBody() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(503)
                    .setHeader("Content-Type", "application/json")
                    .body("not-json")
                    .build(),
            )
            val result = repository(server).check()
            assertEquals(HealthCheckResult.Unavailable(HealthFailure.HTTP, 503), result)
        }
    }

    @Test
    fun statusNotOkIsUnhealthy() = runTest {
        withServer { server ->
            server.enqueueJson("""{"status":"degraded"}""")
            assertEquals(
                HealthCheckResult.Unavailable(HealthFailure.UNHEALTHY),
                repository(server).check(),
            )
        }
    }

    @Test
    fun missingContentTypeIsInvalid() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("""{"status":"ok"}""")
                    .build(),
            )
            assertInvalid(repository(server).check())
        }
    }

    @Test
    fun wrongContentTypeIsInvalid() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "text/plain")
                    .body("""{"status":"ok"}""")
                    .build(),
            )
            assertInvalid(repository(server).check())
        }
    }

    @Test
    fun unknownMissingWrongTypeAndMalformedJsonAreInvalid() = runTest {
        withServer { server ->
            server.enqueueJson("""{"status":"ok","extra":true}""")
            assertInvalid(repository(server).check())
            server.enqueueJson("""{}""")
            assertInvalid(repository(server).check())
            server.enqueueJson("""{"status":1}""")
            assertInvalid(repository(server).check())
            server.enqueueJson("""{"status":"ok"""")
            assertInvalid(repository(server).check())
        }
    }

    @Test
    fun invalidUtf8IsInvalidResponse() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(Buffer().write(byteArrayOf(0xC3.toByte(), 0x28)))
                    .build(),
            )
            assertInvalid(repository(server).check())
        }
    }

    @Test
    fun knownOversizedBodyIsInvalid() = runTest {
        withServer { server ->
            server.enqueueJson("x".repeat(1025))
            assertInvalid(repository(server).check())
        }
    }

    @Test
    fun chunkedOversizedBodyIsInvalid() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .chunkedBody("x".repeat(1025), 64)
                    .build(),
            )
            assertInvalid(repository(server).check())
        }
    }

    @Test
    fun connectionFailureIsNetwork() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getLoopbackAddress(), 0)
        val base = ApiBaseUrl.parse(server.url("/").toString())
        server.close()
        val result = HealthRepository(HttpClientFactory.create(), base).check()
        assertEquals(HealthCheckResult.Unavailable(HealthFailure.NETWORK), result)
    }

    private fun MockWebServer.enqueueJson(body: String) {
        enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body(body)
                .build(),
        )
    }

    private fun repository(server: MockWebServer, path: String = "/"): HealthRepository =
        HealthRepository(
            HttpClientFactory.create(),
            ApiBaseUrl.parse(server.url(path).toString()),
        )

    private fun assertInvalid(result: HealthCheckResult) {
        val unavailable = assertIs<HealthCheckResult.Unavailable>(result)
        assertEquals(HealthFailure.INVALID_RESPONSE, unavailable.reason)
        assertNull(unavailable.httpStatus)
    }

    private suspend fun withServer(block: suspend (MockWebServer) -> Unit) {
        MockWebServer().use { server ->
            server.start(InetAddress.getLoopbackAddress(), 0)
            block(server)
        }
    }
}
