package dev.hryshyn.remanence.core.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

/**
 * T186 M6: R6/R8/R9 client tests (frozen §8; routes scaffold, isolated
 * only). R7 has no frozen response DTO — no client, no invented shape.
 */
class PendingGiftVerificationRepositoriesTest {

    private suspend fun <T> withServer(block: suspend (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server)
        } finally {
            server.close()
        }
    }

    private fun lookup(server: MockWebServer): PendingGiftLookupRepository =
        PendingGiftLookupRepository(
            HttpClientFactory.create(),
            ApiBaseUrl.parse(server.url("/").toString()),
        )

    private fun verification(server: MockWebServer): PendingVerificationRepository =
        PendingVerificationRepository(
            HttpClientFactory.create(),
            ApiBaseUrl.parse(server.url("/").toString()),
        )

    private fun problemJson(code: String, status: Int, retryable: Boolean): String =
        """{"type":"https://remanence.invalid/problems/${code.lowercase()}","title":"safe","status":$status,"code":"$code","detail":"private detail","request_id":"0198f0a0-0000-7000-8000-00000000ac01","retryable":$retryable}"""

    @Test
    fun lookupSendsCodeAndParsesHintAnonymous() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body("""{"gift_reference":"0198f0a0-0000-7000-8000-00000000ab01","channel":"email","hint":"a•••@•••"}""")
                    .build(),
            )
            val result = assertIs<GiftLookupResult.Found>(lookup(server).lookup("ab 3-de"))
            assertEquals("0198f0a0-0000-7000-8000-00000000ab01", result.giftReference)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v2/gift-lookups", request.url.encodedPath)
            assertTrue(request.headers["Authorization"] == null)
            assertEquals("no-store", request.headers["Cache-Control"])
            assertTrue(assertNotNull(request.body).utf8().contains("ab 3-de"))
        }
    }

    @Test
    fun retiredCodeIsGenericUnavailable() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(404)
                    .setHeader("Content-Type", "application/problem+json")
                    .body(problemJson("GIFT_UNAVAILABLE", 404, false))
                    .build(),
            )
            val result = assertIs<GiftLookupResult.Failure>(lookup(server).lookup("A3K9Q"))
            // Generic unavailable; no existence oracle beyond the hint path.
            assertEquals(GiftLookupFailure.GIFT_UNAVAILABLE, result.reason)
            assertFalse(result.retryable)
        }
    }

    @Test
    fun verificationRequestParsesAttempt() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body("""{"attempt_id":"0198f0a0-0000-7000-8000-00000000a701","expires_at":1798765432,"resend_cooldown_seconds":60}""")
                    .build(),
            )
            val result = assertIs<VerificationResult.Attempt>(
                verification(server).requestVerification(
                    "0198f0a0-0000-7000-8000-00000000d401",
                    "a42@example.test", null, null,
                    "0198f0a0-0000-7000-8000-00000000c001", "token",
                ),
            )
            assertEquals("0198f0a0-0000-7000-8000-00000000a701", result.attemptId)
            val request = server.takeRequest()
            assertTrue(request.url.encodedPath.endsWith("/verification"))
            assertEquals("Bearer token", request.headers["Authorization"])
            val body = assertNotNull(request.body).utf8()
            assertTrue(body.contains("a42@example.test"))
            assertFalse(body.contains("consent"))
        }
    }

    @Test
    fun googleProofVerifiedWithoutDispatch() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body("""{"attempt_id":"0198f0a0-0000-7000-8000-00000000a701","state":"VERIFIED"}""")
                    .build(),
            )
            val result = assertIs<VerificationResult.Verified>(
                verification(server).requestVerification(
                    "0198f0a0-0000-7000-8000-00000000d401",
                    "a42@example.test", null, null,
                    "0198f0a0-0000-7000-8000-00000000c001", "token",
                ),
            )
            assertEquals("0198f0a0-0000-7000-8000-00000000a701", result.attemptId)
        }
    }

    @Test
    fun codeCheckVerified() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body("""{"attempt_id":"0198f0a0-0000-7000-8000-00000000a701","state":"VERIFIED","expires_at":1798765432}""")
                    .build(),
            )
            val result = assertIs<VerificationResult.Verified>(
                verification(server).checkCode(
                    "0198f0a0-0000-7000-8000-00000000d401",
                    "0198f0a0-0000-7000-8000-00000000a701",
                    "12345678", "token",
                ),
            )
            assertEquals("0198f0a0-0000-7000-8000-00000000a701", result.attemptId)
            val request = server.takeRequest()
            assertTrue(request.url.encodedPath.endsWith("/check"))
        }
    }

    @Test
    fun proofExpiredIsTerminal() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(410)
                    .setHeader("Content-Type", "application/problem+json")
                    .body(problemJson("PROOF_EXPIRED", 410, false))
                    .build(),
            )
            val result = assertIs<VerificationResult.Failure>(
                verification(server).checkCode("d401", "a701", "12345678", "token"),
            )
            assertEquals(GiftLookupFailure.PROOF_EXPIRED, result.reason)
            assertFalse(result.retryable)
        }
    }
}
