package dev.hryshyn.remanence.core.data.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Test
import kotlin.test.*

class GoogleAuthRepositoryTest {
    @Test fun googleProofHasNoRemanenceBearerAndPreservesPrefix(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json").body("{\"status\":\"NEEDS_HANDLE\"}").build())
            val api = GoogleAuthRepository(ApiBaseUrl.parse(server.url("/isolated/").toString()))
            assertEquals("NEEDS_HANDLE", api.request("verify", buildJsonObject { put("id_token", "synthetic") })["status"]!!.jsonPrimitive.content)
            val request = server.takeRequest()
            assertEquals("/isolated/v1/auth/google/verify", request.url.encodedPath)
            assertNull(request.headers["Authorization"])
        } finally { server.close() }
    }

    @Test fun oversizedChunkedWrongMimeAndRedirectFailClosed(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val api = GoogleAuthRepository(ApiBaseUrl.parse(server.url("/").toString()))
            server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json").chunkedBody(" ".repeat(100 * 1024), 1024).build())
            assertFails { api.request("options", JsonObject(emptyMap())) }
            server.enqueue(MockResponse.Builder().setHeader("Content-Type", "text/html").body("{}").build())
            assertFails { api.request("options", JsonObject(emptyMap())) }
            server.enqueue(MockResponse.Builder().code(302).setHeader("Location", server.url("/other")).build())
            assertFails { api.request("options", JsonObject(emptyMap())) }
            assertEquals(3, server.requestCount)
            assertFails { api.request("../../other", JsonObject(emptyMap())) }
        } finally { server.close() }
    }
}
