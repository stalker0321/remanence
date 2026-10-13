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
 * T186 M7c: R10/R11 client tests (frozen §8 incl. the `claim_challenge_b64`
 * amendment; routes scaffold, isolated only).
 */
class PendingClaimRepositoryTest {

    private suspend fun <T> withServer(block: suspend (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server)
        } finally {
            server.close()
        }
    }

    private fun repository(server: MockWebServer): PendingClaimRepository =
        PendingClaimRepository(
            HttpClientFactory.create(),
            ApiBaseUrl.parse(server.url("/").toString()),
        )

    private fun problemJson(code: String, status: Int, retryable: Boolean): String =
        """{"type":"https://remanence.invalid/problems/${code.lowercase()}","title":"safe","status":$status,"code":"$code","detail":"private detail","request_id":"0198f0a0-0000-7000-8000-00000000ac01","retryable":$retryable}"""

    @Test
    fun optionsParsesBothChallengeFields() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        """{"challenge_id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","expires_at":1798765432,"hpke_challenge_b64":"aHBr","claim_challenge_b64":"Y2hhbA=="}""",
                    )
                    .build(),
            )
            val result = assertIs<ClaimOptionsResult.Options>(
                repository(server).options("0198f0a0-0000-7000-8000-00000000d401", "token"),
            )
            // Amendment: BOTH the sealed nonce and the canonical context.
            assertEquals("Y2hhbA==", result.claimChallengeB64)
            assertEquals("aHBr", result.hpkeChallengeB64)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.url.encodedPath.endsWith("/claim/options"))
            assertEquals("Bearer token", request.headers["Authorization"])
            assertEquals("{}", assertNotNull(request.body).utf8())
        }
    }

    @Test
    fun optionsWithoutVerifiedProofIsTerminal() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(400)
                    .setHeader("Content-Type", "application/problem+json")
                    .body(problemJson("ADDRESS_PROOF_REQUIRED", 400, false))
                    .build(),
            )
            val result = assertIs<ClaimOptionsResult.Failure>(
                repository(server).options("0198f0a0-0000-7000-8000-00000000d401", "token"),
            )
            assertEquals(ClaimFailure.ADDRESS_PROOF_REQUIRED, result.reason)
            assertFalse(result.retryable)
        }
    }

    @Test
    fun submitParsesClaimedEnvelopeRefs() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        """{"claim_id":"55555555-6666-7777-8888-999999999999","state":"CLAIMED","claim_binding_b64":"Ug==","claim_signature_69_b64":"Uw==","envelope_b64":"RW52","envelope_sha256_hex":"${"ab".repeat(32)}"}""",
                    )
                    .build(),
            )
            val result = assertIs<ClaimSubmitResult.Claimed>(
                repository(server).submit(
                    "0198f0a0-0000-7000-8000-00000000d401",
                    "SW50ZW50", "U2ln", "0198f0a0-0000-7000-8000-00000000c001", "token",
                ),
            )
            assertEquals("55555555-6666-7777-8888-999999999999", result.claimId)
            assertEquals("RW52", result.envelopeB64)
            val request = server.takeRequest()
            assertTrue(request.url.encodedPath.endsWith("/claim"))
            val body = assertNotNull(request.body).utf8()
            assertTrue(body.contains("SW50ZW50"))
        }
    }

    @Test
    fun claimConflictIsTerminal() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(409)
                    .setHeader("Content-Type", "application/problem+json")
                    .body(problemJson("CLAIM_CONFLICT", 409, false))
                    .build(),
            )
            val result = assertIs<ClaimSubmitResult.Failure>(
                repository(server).submit("d401", "SW50ZW50", "U2ln", "c001", "token"),
            )
            assertEquals(ClaimFailure.CLAIM_CONFLICT, result.reason)
            assertFalse(result.retryable)
        }
    }

    @Test
    fun blankInputsNeverSend() = runTest {
        withServer { server ->
            val result = assertIs<ClaimSubmitResult.Failure>(
                repository(server).submit("d401", "  ", "U2ln", "c001", "token"),
            )
            assertEquals(ClaimFailure.VALIDATION_FAILED, result.reason)
            assertEquals(0, server.requestCount)
        }
    }
}
