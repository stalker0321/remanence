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

/**
 * T186 M13: receiver-owned v2 clients (feed/control/redownload/
 * manifest/blob) — frozen §8 exact shapes; gated, isolated only.
 */
class PendingReceiverClientsTest {

    private suspend fun <T> withServer(block: suspend (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server)
        } finally {
            server.close()
        }
    }

    private fun feed(server: MockWebServer): PendingIncomingRepository =
        PendingIncomingRepository(
            HttpClientFactory.create(),
            ApiBaseUrl.parse(server.url("/").toString()),
        )

    private fun material(server: MockWebServer): PendingOwnedMaterialRepository =
        PendingOwnedMaterialRepository(
            HttpClientFactory.create(),
            ApiBaseUrl.parse(server.url("/").toString()),
        )

    private fun problemJson(code: String, status: Int, retryable: Boolean): String =
        """{"type":"https://remanence.invalid/problems/${code.lowercase()}","title":"safe","status":$status,"code":"$code","detail":"private detail","request_id":"0198f0a0-0000-7000-8000-00000000ac01","retryable":$retryable}"""

    private fun minimalJson(code: String, status: Int): String =
        """{"type":"about:blank","title":"$code","status":$status,"code":"$code"}"""

    private fun feedItem(completion: String = "null"): String =
        """{"capsule_id":"11111111-2222-3333-4444-555555555555","delivery_id":"0198f0a0-0000-7000-8000-00000000d401","delivery_target_id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","claim_id":"55555555-6666-7777-8888-999999999999","available_at":1798765432,"envelope_ref":"/v2/pending-deliveries/0198f0a0-0000-7000-8000-00000000d401/claim","manifest_ref":"/v2/pending-deliveries/0198f0a0-0000-7000-8000-00000000d401/manifest","blob_refs":[],"completion":$completion}"""

    @Test
    fun feedParsesExactItemsAndCursor() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        """{"items":[${feedItem()},${feedItem("""{"completed_at":1798765500}""")}],"next_cursor":"opaque-cursor-1"}""",
                    )
                    .build(),
            )
            val page = assertIs<PendingFeedResult.Page>(
                feed(server).page(cursor = null, limit = 50, accessToken = "token"),
            )
            assertEquals(2, page.items.size)
            assertNull(page.items[0].completion)
            assertEquals(1798765500L, page.items[1].completion?.completedAt)
            assertEquals("opaque-cursor-1", page.nextCursor)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertTrue(request.url.encodedPath.endsWith("/v2/pending-incoming"))
            assertEquals("50", request.url.queryParameter("limit"))
            assertNull(request.url.queryParameter("cursor"))
            assertEquals("Bearer token", request.headers["Authorization"])
        }
    }

    @Test
    fun feedCursorRoundTrips() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body("""{"items":[],"next_cursor":""}""")
                    .build(),
            )
            val page = assertIs<PendingFeedResult.Page>(
                feed(server).page(cursor = "opaque-cursor-1", limit = 200, accessToken = "token"),
            )
            assertTrue(page.items.isEmpty())
            assertEquals("", page.nextCursor)
            val request = server.takeRequest()
            assertEquals("opaque-cursor-1", request.url.queryParameter("cursor"))
        }
    }

    @Test
    fun feedLimitBoundsRejected() = runTest {
        withServer { server ->
            val tooBig = assertIs<PendingFeedResult.Failure>(
                feed(server).page(cursor = null, limit = 201, accessToken = "token"),
            )
            assertEquals(PendingFeedFailure.VALIDATION_FAILED, tooBig.reason)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun feedMinimalUnavailableMaps() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(404)
                    .setHeader("Content-Type", "application/json")
                    .body(minimalJson("GIFT_UNAVAILABLE", 404))
                    .build(),
            )
            // Feed surface has no GIFT_UNAVAILABLE tuple: generic HTTP.
            val result = assertIs<PendingFeedResult.Failure>(
                feed(server).page(cursor = null, limit = 50, accessToken = "token"),
            )
            assertEquals(PendingFeedFailure.HTTP, result.reason)
            assertFalse(result.retryable)
        }
    }

    @Test
    fun controlReturnsExactBytes() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        """{"capsule_id":"11111111-2222-3333-4444-555555555555","delivery_target_id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","delivery_id":"0198f0a0-0000-7000-8000-00000000d401","claim_id":"55555555-6666-7777-8888-999999999999","publish_statement_b64":"Uw==","publish_signature_69_b64":"Ux==","publish_statement_sha256_hex":"${"ab".repeat(32)}"}""",
                    )
                    .build(),
            )
            val control = assertIs<PendingControlResult.Control>(
                material(server).control("0198f0a0-0000-7000-8000-00000000d401", "token"),
            )
            assertEquals("Uw==", control.publishStatementB64)
            assertEquals("ab".repeat(32), control.publishStatementSha256Hex)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertTrue(request.url.encodedPath.endsWith("/control"))
        }
    }

    @Test
    fun controlForeignOwnerDenied() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(404)
                    .setHeader("Content-Type", "application/json")
                    .body(minimalJson("GIFT_UNAVAILABLE", 404))
                    .build(),
            )
            val result = assertIs<PendingControlResult.Failure>(
                material(server).control("0198f0a0-0000-7000-8000-00000000d401", "token"),
            )
            assertEquals(PendingMaterialFailure.NOT_FOUND, result.reason)
            assertFalse(result.retryable)
        }
    }

    @Test
    fun redownloadReturnsSameEnvelopeShape() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        """{"claim_id":"55555555-6666-7777-8888-999999999999","claim_binding_b64":"Ug==","claim_signature_69_b64":"Uw==","envelope_b64":"RW52","envelope_sha256_hex":"${"ab".repeat(32)}"}""",
                    )
                    .build(),
            )
            val redownloaded = assertIs<PendingRedownloadResult.Redownloaded>(
                material(server).redownload("0198f0a0-0000-7000-8000-00000000d401", "token"),
            )
            assertEquals("55555555-6666-7777-8888-999999999999", redownloaded.claimId)
            assertTrue(redownloaded.claimSignature69B64.isNotBlank())
        }
    }

    @Test
    fun manifestListsArtifacts() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        """{"delivery_id":"0198f0a0-0000-7000-8000-00000000d401","artifacts":[{"blob_id":"cccccccc-dddd-eeee-ffff-000000000001","kind":3,"ordinal":0,"ciphertext_size":505,"ciphertext_sha256_hex":"${"55".repeat(32)}"}]}""",
                    )
                    .build(),
            )
            val manifest = assertIs<PendingManifestResult.Manifest>(
                material(server).manifest("0198f0a0-0000-7000-8000-00000000d401", "token"),
            )
            assertEquals(1, manifest.artifacts.size)
            assertEquals(3, manifest.artifacts.single().kind)
            assertEquals(505L, manifest.artifacts.single().ciphertextSize)
        }
    }

    @Test
    fun blobBytesAreRaw() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/octet-stream")
                    .body("raw-ciphertext-bytes")
                    .build(),
            )
            val bytes = assertIs<PendingBlobBytesResult.Bytes>(
                material(server).blobBytes(
                    "0198f0a0-0000-7000-8000-00000000d401",
                    "cccccccc-dddd-eeee-ffff-000000000001",
                    "token",
                ),
            )
            assertEquals("raw-ciphertext-bytes", bytes.bytes.toString(Charsets.UTF_8))
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertTrue(request.url.encodedPath.endsWith("/blobs/cccccccc-dddd-eeee-ffff-000000000001"))
        }
    }

    @Test
    fun blobJsonBodyRejected() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body("""{"blob_id":"x","size":1}""")
                    .build(),
            )
            val result = assertIs<PendingBlobBytesResult.Failure>(
                material(server).blobBytes("d", "b", "token"),
            )
            assertEquals(PendingMaterialFailure.INVALID_RESPONSE, result.reason)
        }
    }
}
