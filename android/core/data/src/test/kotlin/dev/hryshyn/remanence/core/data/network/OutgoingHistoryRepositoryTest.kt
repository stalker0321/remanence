package dev.hryshyn.remanence.core.data.network

import dev.hryshyn.remanence.core.model.UserId
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.Dispatcher
import mockwebserver3.RecordedRequest
import kotlin.test.*

class OutgoingHistoryRepositoryTest {
    private val owner = UserId.parseRest("10000000-0000-4000-8000-000000000001")
    private val capsule = "20000000-0000-4000-8000-000000000001"
    private val recipient = "30000000-0000-4000-8000-000000000001"
    private val delivery = "40000000-0000-4000-8000-000000000001"

    private suspend fun fixture(block: suspend (MockWebServer, ProductionApiStack) -> Unit) {
        val server = MockWebServer()
        server.start()
        val tokens = AuthTokenHolder("synthetic-access", "synthetic-refresh")
        val stack = ProductionApiStack.create(ApiBaseUrl.parse(server.url("/").toString()), tokens,
            RefreshTokenReader { null }, object : SessionRotationSink {
                override fun rotate(accessToken: String, refreshToken: String, ownerUserId: UserId) = Unit
                override fun clear() = Unit
            })
        stack.sessionRefreshCoordinator.install(owner)
        try { block(server, stack) } finally { server.close() }
    }
    private fun MockWebServer.respond(body: String, code: Int = 200) = enqueue(MockResponse.Builder()
        .code(code).setHeader("Content-Type", "application/json").setHeader("Cache-Control", "private, no-store").body(body).build())
    private fun item(protocol: Int = 1, status: String = "READY", locator: String = "ACTIVE",
                     code: String = "\"AB12Z\"", action: String = "\"REVOKE_V1\""): String = """
        {"capsule_id":"$capsule","protocol_version":$protocol,"delivery_id":${if (protocol == 2) "\"$delivery\"" else "null"},
        "status":"$status","created_at":"2026-10-20T10:00:00.123+00:00",
        "published_at":${if (protocol == 1) "\"2026-10-20T10:01:00Z\"" else "null"},
        "expires_at":null,"first_opened_at":null,"revoked_at":null,
        "recipient":{"user_id":${if (protocol == 1) "\"$recipient\"" else "null"},
        "handle":${if (protocol == 1) "\"friend_qa\"" else "null"},"hint":"r•••@•••","is_self":${if (protocol == 1) "false" else "null"}},
        "locator":{"status":"$locator","code":$code},"cancel_action":$action,
        "cancel_deadline":${if (protocol == 1) "\"2026-10-21T10:01:00Z\"" else "null"}}
    """.trimIndent()
    private fun page(item: String = item(), cursor: String? = null) =
        """{"items":[$item],"has_more":${cursor != null},"next_cursor":${cursor?.let { "\"$it\"" } ?: "null"}}"""

    @Test fun realStackMapsV1AndUsesOnlyPrincipalScopedNoStoreGet() = runTest { fixture { server, stack ->
        server.respond(page(cursor = "opaque+cursor=="))
        val result = assertIs<HistoryResult.Loaded<OutgoingPage>>(stack.outgoingHistoryRepository.outgoing(owner))
        val row = result.value.items.single()
        assertEquals(OutgoingStatus.READY, row.status)
        assertEquals(HistoryCancelAction.REVOKE_V1, row.cancelAction)
        assertEquals("AB12Z", row.code)
        assertEquals("opaque+cursor==", result.value.nextCursor)
        val request = server.takeRequest()
        assertEquals("/v1/capsules/outgoing?limit=20", request.url.encodedPath + "?" + request.url.encodedQuery)
        assertEquals("GET", request.method)
        assertEquals("Bearer synthetic-access", request.headers["Authorization"])
        assertEquals("no-store", request.headers["Cache-Control"])
        server.respond("""{"items":[],"has_more":false,"next_cursor":null}""")
        assertTrue(assertIs<HistoryResult.Loaded<OutgoingPage>>(
            stack.outgoingHistoryRepository.outgoing(owner, result.value.nextCursor)).value.items.isEmpty())
        assertEquals("opaque+cursor==", server.takeRequest().url.queryParameter("cursor"))
    } }

    @Test fun pendingAndRetiredLocatorsNeverRevealClaimedIdentityOrRetiredCode() = runTest { fixture { server, stack ->
        for (locator in listOf("RETIRED_OPEN", "RETIRED_TERMINAL", "UNAVAILABLE")) {
            server.respond(page(item(2, "CLAIMED", locator, action = "null")))
            val row = assertIs<HistoryResult.Loaded<OutgoingPage>>(stack.outgoingHistoryRepository.outgoing(owner)).value.items.single()
            assertNull(row.code); assertNull(row.recipientHandle); assertNull(row.isSelf)
            assertNull(row.publishedAt); assertNull(row.cancelAction)
            assertEquals(OutgoingStatus.CLAIMED, row.status)
        }
        server.respond(page(item(2, "PENDING", action = "\"CANCEL_PENDING_V2\"")))
        val row = assertIs<HistoryResult.Loaded<OutgoingPage>>(stack.outgoingHistoryRepository.outgoing(owner)).value.items.single()
        assertEquals(HistoryCancelAction.CANCEL_PENDING_V2, row.cancelAction)
        assertEquals("AB12Z", row.code)
    } }

    @Test fun unknownStatusActionOrLocatorIsUnavailableAndCannotCopyOrCancel() = runTest { fixture { server, stack ->
        for (body in listOf(item(status = "FUTURE"), item(action = "\"FUTURE\""), item(locator = "FUTURE"))) {
            server.respond(page(body))
            val row = assertIs<HistoryResult.Loaded<OutgoingPage>>(stack.outgoingHistoryRepository.outgoing(owner)).value.items.single()
            assertFalse(row.detailsAvailable); assertNull(row.code); assertNull(row.cancelAction)
            assertEquals(OutgoingStatus.UNKNOWN, row.status)
            assertEquals(HistoryCancelResult.UNAVAILABLE, stack.outgoingHistoryRepository.cancel(owner, row))
        }
        assertEquals(3, server.requestCount)
    } }

    @Test fun unpublishedOrTerminalRowsCannotExposeStrayActiveCodeOrRevoke() = runTest { fixture { server, stack ->
        for (status in listOf("DRAFT", "ABORTED", "OPENED", "REVOKED")) {
            server.respond(page(item(status = status)))
            val row = assertIs<HistoryResult.Loaded<OutgoingPage>>(stack.outgoingHistoryRepository.outgoing(owner)).value.items.single()
            assertNull(row.code); assertNull(row.cancelAction)
        }
    } }

    @Test fun unsupportedOfflineAndInvalidResponsesAreNeverFakeEmpty() = runTest { fixture { server, stack ->
        for (status in listOf(404, 503, 500, 422)) {
            server.respond("{}", status)
            assertIs<HistoryResult.Failed>(stack.outgoingHistoryRepository.outgoing(owner))
        }
        for (body in listOf("{}", "not-json", page(item(code = "\"ILOU0\"")),
            page(item().replace("\"protocol_version\":1", "\"protocol_version\":2")),
            page(item().replace("2026-10-21T10:01:00Z", "yesterday")),
            """{"items":[],"has_more":true,"next_cursor":null}""",
            page(item() + "," + item()), " ".repeat(512 * 1024 + 1))) {
            server.respond(body)
            assertEquals(HistoryFailure.INVALID_RESPONSE,
                assertIs<HistoryResult.Failed>(stack.outgoingHistoryRepository.outgoing(owner)).reason)
        }
    } }

    @Test fun recentUsesFreshPublicSuggestionsAndRejectsSelfDuplicateOrBadHandle() = runTest { fixture { server, stack ->
        fun recent(id: String = recipient, handle: String = "friend_qa") =
            """{"user_id":"$id","handle":"$handle","last_sent_at":"2026-10-20T10:01:00Z"}"""
        server.respond("""{"items":[${recent()}]}""")
        assertEquals("friend_qa", assertIs<HistoryResult.Loaded<List<RecentRecipient>>>(
            stack.outgoingHistoryRepository.recent(owner)).value.single().handle)
        val request = server.takeRequest()
        assertEquals("/v1/recipients/recent", request.url.encodedPath)
        assertEquals("8", request.url.queryParameter("limit"))
        for (items in listOf(recent(owner.toRestString()), recent(handle = "someone@example.com"), recent() + "," + recent())) {
            server.respond("""{"items":[$items]}""")
            assertIs<HistoryResult.Failed>(stack.outgoingHistoryRepository.recent(owner))
        }
    } }

    @Test fun ownerMismatchAndRetiredLeaseFailClosedBeforeOrAfterHttp() = runTest { fixture { server, stack ->
        assertEquals(HistoryFailure.SIGN_IN, assertIs<HistoryResult.Failed>(
            stack.outgoingHistoryRepository.outgoing(UserId.parseRest(recipient))).reason)
        assertEquals(0, server.requestCount)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                stack.sessionRefreshCoordinator.invalidate()
                return MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(page()).build()
            }
        }
        assertEquals(HistoryFailure.SIGN_IN, assertIs<HistoryResult.Failed>(
            stack.outgoingHistoryRepository.outgoing(owner)).reason)
    } }

    @Test fun cancellationReusesExactExistingV1AndPendingPostsAndHonestOutcomes() = runTest { fixture { server, stack ->
        server.respond(page())
        val v1 = assertIs<HistoryResult.Loaded<OutgoingPage>>(stack.outgoingHistoryRepository.outgoing(owner)).value.items.single()
        server.takeRequest()
        server.respond("""{"capsule_id":"$capsule","state":"REVOKED","is_replay":false}""")
        assertEquals(HistoryCancelResult.SUCCEEDED, stack.outgoingHistoryRepository.cancel(owner, v1))
        val post = server.takeRequest()
        assertEquals("/v1/capsules/$capsule/revoke", post.url.encodedPath)
        assertEquals("POST", post.method); assertEquals(0L, post.body?.size ?: 0L)
        server.enqueue(MockResponse.Builder().code(409).setHeader("Content-Type", "application/problem+json")
            .body("""{"type":"https://remanence.invalid/problems/window_expired","title":"safe","status":409,"code":"WINDOW_EXPIRED","detail":"safe","request_id":"$capsule","retryable":false}""").build())
        assertEquals(HistoryCancelResult.CONFLICT, stack.outgoingHistoryRepository.cancel(owner, v1))
        server.takeRequest()
        server.respond(page(item(2, "PENDING", action = "\"CANCEL_PENDING_V2\"")))
        val v2 = assertIs<HistoryResult.Loaded<OutgoingPage>>(stack.outgoingHistoryRepository.outgoing(owner)).value.items.single()
        server.takeRequest()
        for ((status, body, outcome) in listOf(
            Triple(200, """{"delivery_id":"$delivery","state":"CANCELLED"}""", HistoryCancelResult.SUCCEEDED),
            Triple(409, "{}", HistoryCancelResult.CONFLICT),
            Triple(404, "{}", HistoryCancelResult.UNAVAILABLE),
            Triple(200, "{}", HistoryCancelResult.UNKNOWN),
        )) {
            server.respond(body, status)
            assertEquals(outcome, stack.outgoingHistoryRepository.cancel(owner, v2))
            val request = server.takeRequest()
            assertEquals("/v2/pending-deliveries/$delivery/cancel", request.url.encodedPath)
            assertEquals("POST", request.method); assertEquals(0L, request.body?.size ?: 0L)
        }
    } }
}
