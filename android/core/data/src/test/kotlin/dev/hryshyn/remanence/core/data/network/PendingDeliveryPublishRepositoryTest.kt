package dev.hryshyn.remanence.core.data.network

import java.security.MessageDigest
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
 * T186 M11: R3/R4/R5 publication client tests (frozen §8 exact shapes;
 * routes gated, isolated only).
 */
class PendingDeliveryPublishRepositoryTest {

    private suspend fun <T> withServer(block: suspend (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server)
        } finally {
            server.close()
        }
    }

    private fun repository(server: MockWebServer): PendingDeliveryPublishRepository =
        PendingDeliveryPublishRepository(
            HttpClientFactory.create(),
            ApiBaseUrl.parse(server.url("/").toString()),
        )

    private fun problemJson(code: String, status: Int, retryable: Boolean): String =
        """{"type":"https://remanence.invalid/problems/${code.lowercase()}","title":"safe","status":$status,"code":"$code","detail":"private detail","request_id":"0198f0a0-0000-7000-8000-00000000ac01","retryable":$retryable}"""

    private val artifacts = listOf(
        PendingArtifactDeclaration(
            blobId = "cccccccc-dddd-eeee-ffff-000000000001",
            kind = 3,
            ordinal = 0,
            ciphertextSize = 4L,
            ciphertextSha256Hex = MessageDigest.getInstance("SHA-256").digest("body".toByteArray()).joinToString("") { "%02x".format(it) },
        ),
    )

    @Test
    fun draftParsesFrozenShape() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        """{"delivery_id":"0198f0a0-0000-7000-8000-00000000d401","state":"DRAFT","address_commitment_d_hex":"${"0f".repeat(32)}","expires_at":1798765432}""",
                    )
                    .build(),
            )
            val result = assertIs<PendingDraftResult.Draft>(
                repository(server).draft(
                    routeId = "0198f0a0-0000-7000-8000-00000000b301",
                    capsuleId = "11111111-2222-3333-4444-555555555555",
                    deliveryTargetId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                    channel = "email",
                    destination = "a42@example.test",
                    salt32 = ByteArray(32),
                    claimExpiresAt = 1814317432L,
                    artifacts = artifacts,
                    idempotencyKey = "b2c3d4e5-f6a7-48b9-c0d1-e2f3a4b5c6d7",
                    accessToken = "token",
                ),
            )
            assertEquals("0198f0a0-0000-7000-8000-00000000d401", result.deliveryId)
            assertEquals("0f".repeat(32), result.addressCommitmentHex)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.url.encodedPath.endsWith("/v2/pending-deliveries"))
            assertEquals("Bearer token", request.headers["Authorization"])
            assertTrue(assertNotNull(request.body).utf8().contains("\"claim_policy_version\":1"))
        }
    }

    @Test
    fun draftRouteStaleIsTerminal() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(409)
                    .setHeader("Content-Type", "application/problem+json")
                    .body(problemJson("ROUTE_STALE", 409, false))
                    .build(),
            )
            val result = assertIs<PendingDraftResult.Failure>(
                repository(server).draft(
                    routeId = "r", capsuleId = "11111111-2222-3333-4444-555555555555",
                    deliveryTargetId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                    channel = "email", destination = "a42@example.test",
                    salt32 = ByteArray(32), claimExpiresAt = 1L,
                    artifacts = emptyList(),
                    idempotencyKey = "b2c3d4e5-f6a7-48b9-c0d1-e2f3a4b5c6d7",
                    accessToken = "token",
                ),
            )
            assertEquals(PendingPublishFailure.ROUTE_STALE, result.reason)
            assertFalse(result.retryable)
        }
    }

    @Test
    fun blobUploadBindsDeclarationBeforeSend() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body("""{"blob_id":"cccccccc-dddd-eeee-ffff-000000000001","size":4}""")
                    .build(),
            )
            val result = assertIs<PendingBlobResult.Uploaded>(
                repository(server).uploadBlob(
                    deliveryId = "0198f0a0-0000-7000-8000-00000000d401",
                    blobId = "cccccccc-dddd-eeee-ffff-000000000001",
                    ciphertext = "body".toByteArray(),
                    declaredSize = 4L,
                    declaredSha256Hex = artifacts.single().ciphertextSha256Hex,
                    accessToken = "token",
                ),
            )
            assertEquals(4L, result.size)
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertTrue(request.url.encodedPath.endsWith("/blobs/cccccccc-dddd-eeee-ffff-000000000001"))
        }
    }

    @Test
    fun blobHashMismatchNeverSends() = runTest {
        withServer { server ->
            val result = assertIs<PendingBlobResult.Failure>(
                repository(server).uploadBlob(
                    deliveryId = "d",
                    blobId = "cccccccc-dddd-eeee-ffff-000000000001",
                    ciphertext = "body".toByteArray(),
                    declaredSize = 4L,
                    declaredSha256Hex = "00".repeat(32),
                    accessToken = "token",
                ),
            )
            assertEquals(PendingPublishFailure.VALIDATION_FAILED, result.reason)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun finalizeReturnsLocatorCode() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        """{"delivery_id":"0198f0a0-0000-7000-8000-00000000d401","state":"PENDING","locator_code":"AB3DE","claim_expires_at":1814317432,"hint":"a•••@•••"}""",
                    )
                    .build(),
            )
            val result = assertIs<PendingFinalizeResult.Finalized>(
                repository(server).finalize(
                    deliveryId = "0198f0a0-0000-7000-8000-00000000d401",
                    publishStatementB64 = "Uw==",
                    publishSignature69B64 = "Ux==",
                    escrowPayloadB64 = "RXNj",
                    idempotencyKey = "b2c3d4e5-f6a7-48b9-c0d1-e2f3a4b5c6d7",
                    accessToken = "token",
                ),
            )
            assertEquals("AB3DE", result.locatorCode)
            assertEquals("a•••@•••", result.hint)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.url.encodedPath.endsWith("/finalize"))
        }
    }

    @Test
    fun finalizeConflictIsTerminal() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(409)
                    .setHeader("Content-Type", "application/problem+json")
                    .body(problemJson("IDEMPOTENCY_CONFLICT", 409, false))
                    .build(),
            )
            val result = assertIs<PendingFinalizeResult.Failure>(
                repository(server).finalize(
                    deliveryId = "d", publishStatementB64 = "Uw==",
                    publishSignature69B64 = "Ux==", escrowPayloadB64 = "RXNj",
                    idempotencyKey = "k", accessToken = "token",
                ),
            )
            assertEquals(PendingPublishFailure.IDEMPOTENCY_CONFLICT, result.reason)
            assertFalse(result.retryable)
        }
    }
}
