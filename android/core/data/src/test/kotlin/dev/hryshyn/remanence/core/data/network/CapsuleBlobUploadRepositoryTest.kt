package dev.hryshyn.remanence.core.data.network

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId

class CapsuleBlobUploadRepositoryTest {

    private val capsuleId = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000ca01")
    private val blobId = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b001")
    private val idempotencyKey = UUID.fromString("0198f0a0-0000-7000-8000-00000000ad01")

    private suspend fun <T> withServer(block: suspend (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server)
        } finally {
            server.close()
        }
    }

    private fun request(ciphertext: ByteArray = CIPHERTEXT): CapsuleBlobUploadRequest =
        CapsuleBlobUploadRequest(
            capsuleId = capsuleId,
            blobId = blobId,
            ciphertext = ciphertext,
            ciphertextSha256 = sha256(ciphertext),
            idempotencyKey = idempotencyKey,
        )

    @Test
    fun successfulUploadEmitsExactAuthenticatedWireContract() = runTest {
        withServer { server ->
            server.enqueue(MockResponse.Builder().code(204).build())

            val result = repository(server).uploadBlob(request(), accessToken = "pm_at_live")

            assertEquals(CapsuleBlobUploadResult.Success(204), result)
            val recorded = server.takeRequest()
            assertEquals("PUT", recorded.method)
            assertEquals(
                "/v1/capsules/${capsuleId.toRestString()}/blobs/${blobId.toRestString()}",
                recorded.url.encodedPath,
            )
            assertEquals("Bearer pm_at_live", recorded.headers["Authorization"])
            assertEquals("application/octet-stream", recorded.headers["Content-Type"])
            assertEquals(CIPHERTEXT.size.toString(), recorded.headers["Content-Length"])
            assertEquals(base64url(sha256(CIPHERTEXT)), recorded.headers["X-Remanence-Ciphertext-SHA256"])
            assertEquals(idempotencyKey.toString(), recorded.headers["Idempotency-Key"])
            assertEquals(CIPHERTEXT.toList(), recorded.body!!.toByteArray().toList())
        }
    }

    @Test
    fun exactReplayIsTheSameSuccessfulEmptyResultAndRetainsIdempotencyKey() = runTest {
        withServer { server ->
            server.enqueue(MockResponse.Builder().code(204).build())
            server.enqueue(MockResponse.Builder().code(204).build())

            val first = repository(server).uploadBlob(request(), accessToken = "pm_at_live")
            val replay = repository(server).uploadBlob(request(), accessToken = "pm_at_live")

            assertEquals(CapsuleBlobUploadResult.Success(204), first)
            assertEquals(CapsuleBlobUploadResult.Success(204), replay)
            assertEquals(idempotencyKey.toString(), server.takeRequest().headers["Idempotency-Key"])
            assertEquals(idempotencyKey.toString(), server.takeRequest().headers["Idempotency-Key"])
        }
    }

    @Test
    fun stableBlobProblemCodesMapWithoutRetainingDetails() = runTest {
        withServer { server ->
            val cases = listOf(
                "AUTH_INVALID" to CapsuleBlobUploadFailure.AUTH_INVALID,
                "VALIDATION_FAILED" to CapsuleBlobUploadFailure.VALIDATION_FAILED,
                "CAPSULE_NOT_FOUND" to CapsuleBlobUploadFailure.CAPSULE_NOT_FOUND,
                "CAPSULE_STATE_INVALID" to CapsuleBlobUploadFailure.CAPSULE_STATE_INVALID,
                "DRAFT_EXPIRED" to CapsuleBlobUploadFailure.DRAFT_EXPIRED,
                "BLOB_NOT_DECLARED" to CapsuleBlobUploadFailure.BLOB_NOT_DECLARED,
                "BLOB_SIZE_INVALID" to CapsuleBlobUploadFailure.BLOB_SIZE_INVALID,
                "BLOB_HASH_MISMATCH" to CapsuleBlobUploadFailure.BLOB_HASH_MISMATCH,
                "BLOB_CONFLICT" to CapsuleBlobUploadFailure.BLOB_CONFLICT,
                "INTERNAL_UNAVAILABLE" to CapsuleBlobUploadFailure.INTERNAL_UNAVAILABLE,
                "INTERNAL_ERROR" to CapsuleBlobUploadFailure.INTERNAL_ERROR,
            )
            cases.forEach { (code, expected) ->
                server.enqueue(
                    MockResponse.Builder()
                        .code(if (code == "AUTH_INVALID") 401 else if (code == "INTERNAL_UNAVAILABLE") 503 else 409)
                        .setHeader("Content-Type", "application/problem+json")
                        .body(problemJson(code))
                        .build(),
                )
                val failure = assertIs<CapsuleBlobUploadResult.Failure>(
                    repository(server).uploadBlob(request(), accessToken = "pm_at_live"),
                )
                assertEquals(expected, failure.reason)
                assertFalse(failure.toString().contains(SECRET_DETAIL))
            }
        }
    }

    @Test
    fun malformedOrUnexpectedResponsesFailClosedAndReadingIsBounded() = runTest {
        withServer { server ->
            server.enqueue(MockResponse.Builder().code(200).body("unexpected").build())
            val nonemptySuccess = assertIs<CapsuleBlobUploadResult.Failure>(
                repository(server).uploadBlob(request(), accessToken = "pm_at_live"),
            )
            assertEquals(CapsuleBlobUploadFailure.INVALID_RESPONSE, nonemptySuccess.reason)

            server.enqueue(
                MockResponse.Builder()
                    .code(500)
                    .setHeader("Content-Type", "application/problem+json")
                    .body("x".repeat(64 * 1024 + 1))
                    .build(),
            )
            val oversized = assertIs<CapsuleBlobUploadResult.Failure>(
                repository(server).uploadBlob(request(), accessToken = "pm_at_live"),
            )
            assertEquals(CapsuleBlobUploadFailure.INVALID_RESPONSE, oversized.reason)

            server.enqueue(
                MockResponse.Builder()
                    .code(409)
                    .setHeader("Content-Type", "application/problem+json")
                    .body("not-json")
                    .build(),
            )
            val malformed = assertIs<CapsuleBlobUploadResult.Failure>(
                repository(server).uploadBlob(request(), accessToken = "pm_at_live"),
            )
            assertEquals(CapsuleBlobUploadFailure.HTTP, malformed.reason)
        }
    }

    @Test
    fun clientRejectsHashBodyMismatchBeforeNetworkAndRedactsCiphertext() = runTest {
        withServer { server ->
            val sensitive = "plaintext-marker-must-not-be-logged".toByteArray()
            val badRequest = assertFailsWith<IllegalArgumentException> {
                CapsuleBlobUploadRequest(
                    capsuleId = capsuleId,
                    blobId = blobId,
                    ciphertext = sensitive,
                    ciphertextSha256 = ByteArray(32),
                    idempotencyKey = idempotencyKey,
                )
            }
            assertFalse(badRequest.toString().contains(String(sensitive)))
            assertFalse(
                CapsuleBlobUploadRequest(
                    capsuleId = capsuleId,
                    blobId = blobId,
                    ciphertext = sensitive,
                    ciphertextSha256 = sha256(sensitive),
                    idempotencyKey = idempotencyKey,
                ).toString().contains(String(sensitive)),
            )
        }
    }

    private fun repository(server: MockWebServer): CapsuleBlobUploadRepository =
        CapsuleBlobUploadRepository.create(ApiBaseUrl.parse(server.url("/").toString()))

    private fun problemJson(code: String): String =
        """
        {"type":"https://remanence.invalid/problems/$code","title":"safe","status":409,"code":"$code","detail":"$SECRET_DETAIL","request_id":"0198f0a0-0000-7000-8000-00000000ac01","retryable":false}
        """.trimIndent()

    private companion object {
        val CIPHERTEXT = "opaque-ciphertext".toByteArray()
        const val SECRET_DETAIL = "private detail must not cross the repository boundary"

        fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

        fun base64url(value: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }
}
