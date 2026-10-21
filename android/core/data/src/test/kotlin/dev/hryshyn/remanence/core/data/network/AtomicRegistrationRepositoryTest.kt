package dev.hryshyn.remanence.core.data.network

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Actual repository/OkHttp tests; native provider/device acceptance is separate. */
class AtomicRegistrationRepositoryTest {
    private val reservation = "11111111-1111-4111-8111-111111111111"
    private val token = "rgs_" + "A".repeat(43)
    private val epoch = buildJsonObject { put("epoch", 7) }
    private fun reply(code: Int, body: String) = MockResponse.Builder().code(code)
        .setHeader("Content-Type", "application/json").setHeader("Cache-Control", "no-store").body(body).build()

    @Test fun closedGoogleSetupRoutesHaveSeparateAuthorityAndPreservePrefix(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val api = AtomicRegistrationRepository(ApiBaseUrl.parse(server.url("/isolated/").toString()))
            server.enqueue(reply(200, "{}"))
            api.request(AtomicAction.GOOGLE_VERIFY, null, null, buildJsonObject { put("onboarding_version", 1) })
            val google = server.takeRequest()
            assertEquals("/isolated/v1/auth/google/verify", google.url.encodedPath)
            assertNull(google.headers["Authorization"]); assertNull(google.headers["Cookie"])
            server.enqueue(reply(200, "{}"))
            api.request(AtomicAction.STATUS, reservation, token, null)
            val status = server.takeRequest()
            assertEquals("GET", status.method); assertEquals("Bearer $token", status.headers["Authorization"])
            assertEquals("/isolated/v2/registration/reservations/$reservation", status.url.encodedPath)
            assertEquals("no-store", status.headers["Cache-Control"])
            assertNull(status.headers["Idempotency-Key"]); assertNull(status.url.query)
        } finally { server.close() }
    }

    @Test fun exact410ReasonAndFinalize201SurviveRealBoundedDecoder(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val api = AtomicRegistrationRepository(ApiBaseUrl.parse(server.url("/").toString()))
            server.enqueue(reply(410, "{\"reason\":\"RESERVATION_TERMINAL\"}"))
            val error = assertFailsWith<AtomicRegistrationRejected> { api.request(AtomicAction.CANCEL, reservation, token, epoch) }
            assertEquals(410, error.status); assertEquals("RESERVATION_TERMINAL", error.reason)
            val cancel = server.takeRequest()
            assertEquals("POST", cancel.method); assertEquals("{\"epoch\":7}", cancel.body!!.utf8())
            assertEquals("/v2/registration/reservations/$reservation/cancel", cancel.url.encodedPath)
            server.enqueue(reply(201, "{\"status\":\"ACTIVE\"}"))
            assertEquals(JsonPrimitive("ACTIVE"), api.request(AtomicAction.FINALIZE, reservation, token, epoch)["status"])
            server.enqueue(reply(201, "{}"))
            assertFailsWith<AtomicRegistrationRejected> { api.request(AtomicAction.CANCEL, reservation, token, epoch) }
        } finally { server.close() }
    }

    @Test fun malformedMimeUtf8OversizeRedirectAndSecretDetailFailClosed(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val api = AtomicRegistrationRepository(ApiBaseUrl.parse(server.url("/").toString()))
            server.enqueue(MockResponse.Builder().setHeader("Content-Type", "text/html").body("{}").build())
            assertFails { api.request(AtomicAction.STATUS, reservation, token, null) }
            server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json").chunkedBody(" ".repeat(262145), 1024).build())
            assertFails { api.request(AtomicAction.STATUS, reservation, token, null) }
            server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json").body(okio.Buffer().write(byteArrayOf(0xc3.toByte(), 0x28))).build())
            assertFails { api.request(AtomicAction.STATUS, reservation, token, null) }
            server.enqueue(MockResponse.Builder().code(302).setHeader("Location", server.url("/elsewhere")).body("").build())
            assertFails { api.request(AtomicAction.STATUS, reservation, token, null) }
            server.enqueue(reply(503, "{\"detail\":\"private server data\"}"))
            val error = assertFailsWith<AtomicRegistrationRejected> { api.request(AtomicAction.CANCEL, reservation, token, epoch) }
            assertEquals("SETUP_REJECTED", error.reason); assertFalse(error.toString().contains("private server data"))
            assertEquals(5, server.requestCount)
        } finally { server.close() }
    }

    @Test fun invalidAuthorityRouteAndOversizeRequestNeverDispatch(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val api = AtomicRegistrationRepository(ApiBaseUrl.parse(server.url("/").toString()))
            for (bad in listOf("../other", "AAAAAAAA-1111-4111-8111-111111111111", "$reservation?x=1")) {
                assertFails { api.request(AtomicAction.CANCEL, bad, token, epoch) }
            }
            assertFails { api.request(AtomicAction.CANCEL, reservation, "pm_at_ordinary", epoch) }
            assertFails { api.request(AtomicAction.GOOGLE_OPTIONS, reservation, token, epoch) }
            assertFails { api.request(AtomicAction.STATUS, reservation, token, epoch) }
            assertFails { api.request(AtomicAction.FINALIZE, reservation, token, buildJsonObject { put("huge", "x".repeat(262145)) }) }
            assertEquals(0, server.requestCount)
        } finally { server.close() }
    }

    @Test fun coroutineCancellationOwnsThePendingHttpBody(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val api = AtomicRegistrationRepository(ApiBaseUrl.parse(server.url("/").toString()))
            server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json")
                .body("{}").bodyDelay(20, TimeUnit.SECONDS).build())
            val request = async { api.request(AtomicAction.STATUS, reservation, token, null) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            request.cancel()
            withTimeout(2000) { request.join() }
            assertTrue(request.isCancelled); assertEquals(1, server.requestCount)
        } finally { server.close() }
    }

    @Test fun cancellationAfterHeadersCancelsExactCallBeforeBodyTimeout(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        val entered = CompletableDeferred<Unit>()
        val callRef = java.util.concurrent.atomic.AtomicReference<okhttp3.Call>()
        val client = HttpClientFactory.create().newBuilder().eventListener(object : okhttp3.EventListener() {
            override fun callStart(call: okhttp3.Call) { callRef.set(call) }
        }).build()
        try {
            server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json")
                .body("{}").bodyDelay(20, TimeUnit.SECONDS).build())
            val request = async {
                client.executeResponseWithCallLifetime(okhttp3.Request.Builder().url(server.url("/body")).build()) { response ->
                    assertEquals(200, response.code)
                    entered.complete(Unit) // Actual response headers received; body not yet available.
                    response.body.string()
                }
            }
            withTimeout(5000) { entered.await() }
            request.cancel()
            withTimeout(2000) { request.join() }
            assertTrue(request.isCancelled); assertTrue(callRef.get().isCanceled())
            assertEquals(1, server.requestCount)
        } finally { server.close() }
    }

    @Test fun strictScalarsRejectAbsentNullWrongTypesAndNoncanonicalCapabilities() {
        for (value in listOf("{}", "{\"epoch\":null}", "{\"epoch\":true}", "{\"epoch\":\"1\"}", "{\"epoch\":1.0}")) {
            assertFails { AtomicWire.integer(NetworkJson.parseToJsonElement(value).jsonObject, "epoch") }
        }
        assertEquals(1, AtomicWire.integer(buildJsonObject { put("epoch", 1) }, "epoch"))
        assertFails { AtomicWire.text(buildJsonObject { put("id", 12) }, "id") }
        assertFails { AtomicWire.capability("rgs_" + "B".repeat(43), "rgs_") }
    }
}
