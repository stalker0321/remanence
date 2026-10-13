package dev.hryshyn.remanence.core.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

/**
 * T186 M7c: R7 continuation tests (frozen §8; routes scaffold, isolated
 * only). Reference alone authorizes nothing — bearer required.
 */
class PendingContinueRepositoryTest {

    private suspend fun <T> withServer(block: suspend (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server)
        } finally {
            server.close()
        }
    }

    private fun repository(server: MockWebServer): PendingContinueRepository =
        PendingContinueRepository(
            HttpClientFactory.create(),
            ApiBaseUrl.parse(server.url("/").toString()),
        )

    @Test
    fun syncBranchNeedsNoProof() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body("""{"next":"sync","capsule_id":"0198f0a0-0000-7000-8000-00000000ca01"}""")
                    .build(),
            )
            val result = assertIs<ContinueResult.Sync>(
                repository(server).continueFrom("0198f0a0-0000-7000-8000-00000000ab01", "token"),
            )
            assertEquals("0198f0a0-0000-7000-8000-00000000ca01", result.capsuleId)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.url.encodedPath.endsWith("/continue"))
            assertEquals("Bearer token", request.headers["Authorization"])
        }
    }

    @Test
    fun proofBranchCarriesDeliveryAndSnapshot() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        """{"next":"proof","gift_reference":"0198f0a0-0000-7000-8000-00000000ab01",isotopic":"x","delivery_id":"0198f0a0-0000-7000-8000-00000000d401","capsule_id":"0198f0a0-0000-7000-8000-00000000ca01","delivery_target_id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","channel":"email","claim_expires_at":1798765432,"publish_statement_b64":"UQ==","publish_signature_69_b64":"U0k="}""",
                    )
                    .build(),
            )
            // Strict decode (ignoreUnknownKeys=false) must REJECT the
            // unknown "isotopic" field — never silently accept it.
            val result = assertIs<ContinueResult.Failure>(
                repository(server).continueFrom("0198f0a0-0000-7000-8000-00000000ab01", "token"),
            )
            assertEquals(GiftLookupFailure.INVALID_RESPONSE, result.reason)
            assertFalse(result.retryable)
        }
    }

    @Test
    fun proofBranchExactShapeAccepted() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        """{"next":"proof","gift_reference":"0198f0a0-0000-7000-8000-00000000ab01","delivery_id":"0198f0a0-0000-7000-8000-00000000d401","capsule_id":"0198f0a0-0000-7000-8000-00000000ca01","delivery_target_id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","channel":"email","claim_expires_at":1798765432,"publish_statement_b64":"UQ==","publish_signature_69_b64":"U0k="}""",
                    )
                    .build(),
            )
            val result = assertIs<ContinueResult.Proof>(
                repository(server).continueFrom("0198f0a0-0000-7000-8000-00000000ab01", "token"),
            )
            assertEquals("0198f0a0-0000-7000-8000-00000000d401", result.deliveryId)
            assertEquals("UQ==", result.publishStatementB64)
        }
    }

    @Test
    fun foreignGiftIsGenericUnavailable() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(404)
                    .setHeader("Content-Type", "application/problem+json")
                    .body(
                        """{"type":"https://remanence.invalid/problems/gift_unavailable","title":"safe","status":404,"code":"GIFT_UNAVAILABLE","detail":"x","request_id":"0198f0a0-0000-7000-8000-00000000ac01","retryable":false}""",
                    )
                    .build(),
            )
            val result = assertIs<ContinueResult.Failure>(
                repository(server).continueFrom("0198f0a0-0000-7000-8000-00000000ab01", "token"),
            )
            // Generic unavailable — never a proof branch for someone else.
            assertEquals(GiftLookupFailure.GIFT_UNAVAILABLE, result.reason)
            assertFalse(result.retryable)
        }
    }

    @Test
    fun blankInputsNeverSend() = runTest {
        withServer { server ->
            val result = assertIs<ContinueResult.Failure>(
                repository(server).continueFrom("  ", "token"),
            )
            assertEquals(GiftLookupFailure.VALIDATION_FAILED, result.reason)
            assertEquals(0, server.requestCount)
        }
    }
}
