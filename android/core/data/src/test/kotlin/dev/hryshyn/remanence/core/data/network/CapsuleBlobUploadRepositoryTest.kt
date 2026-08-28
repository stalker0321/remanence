package dev.hryshyn.remanence.core.data.network

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
                Triple("AUTH_INVALID", CapsuleBlobUploadFailure.AUTH_INVALID, 401 to false),
                Triple("VALIDATION_FAILED", CapsuleBlobUploadFailure.VALIDATION_FAILED, 422 to false),
                Triple("CAPSULE_NOT_FOUND", CapsuleBlobUploadFailure.CAPSULE_NOT_FOUND, 404 to false),
                Triple("CAPSULE_STATE_INVALID", CapsuleBlobUploadFailure.CAPSULE_STATE_INVALID, 409 to false),
                Triple("DRAFT_EXPIRED", CapsuleBlobUploadFailure.DRAFT_EXPIRED, 409 to false),
                Triple("BLOB_NOT_DECLARED", CapsuleBlobUploadFailure.BLOB_NOT_DECLARED, 404 to false),
                Triple("BLOB_SIZE_INVALID", CapsuleBlobUploadFailure.BLOB_SIZE_INVALID, 422 to false),
                Triple("BLOB_HASH_MISMATCH", CapsuleBlobUploadFailure.BLOB_HASH_MISMATCH, 422 to false),
                Triple("BLOB_CONFLICT", CapsuleBlobUploadFailure.BLOB_CONFLICT, 409 to false),
                Triple("RATE_LIMITED", CapsuleBlobUploadFailure.RATE_LIMITED, 429 to true),
                Triple("INTERNAL_ERROR", CapsuleBlobUploadFailure.INTERNAL_ERROR, 503 to true),
                Triple("INTERNAL_ERROR", CapsuleBlobUploadFailure.INTERNAL_ERROR, 500 to false),
            )
            cases.forEach { (code, expected, statusAndRetryable) ->
                val (status, retryable) = statusAndRetryable
                server.enqueue(
                    MockResponse.Builder()
                        .code(status)
                        .setHeader("Content-Type", "application/problem+json")
                        .body(problemJson(code, status, retryable))
                        .build(),
                )
                val failure = assertIs<CapsuleBlobUploadResult.Failure>(
                    repository(server).uploadBlob(request(), accessToken = "pm_at_live"),
                )
                assertEquals(expected, failure.reason)
                assertEquals(retryable, failure.retryable)
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
                    .code(503)
                    .setHeader("Content-Type", "application/problem+json")
                    .body("x".repeat(64 * 1024 + 1))
                    .build(),
            )
            val oversized = assertIs<CapsuleBlobUploadResult.Failure>(
                repository(server).uploadBlob(request(), accessToken = "pm_at_live"),
            )
            assertEquals(CapsuleBlobUploadFailure.INVALID_RESPONSE, oversized.reason)
            assertTrue(oversized.retryable)

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
            assertFalse(malformed.retryable)
        }
    }

    @Test
    fun malformedAndProxyResponsesUseHttpRetryabilityFallback() = runTest {
        withServer { server ->
            fun enqueue(status: Int, contentType: String?, body: String) {
                server.enqueue(
                    MockResponse.Builder()
                        .code(status)
                        .apply { if (contentType != null) setHeader("Content-Type", contentType) }
                        .body(body)
                        .build(),
                )
            }

            enqueue(503, "application/problem+json", "not-json")
            assertTrue(assertIs<CapsuleBlobUploadResult.Failure>(repository(server).uploadBlob(request(), "pm_at_live")).retryable)

            enqueue(503, "text/plain", "proxy failure")
            assertTrue(assertIs<CapsuleBlobUploadResult.Failure>(repository(server).uploadBlob(request(), "pm_at_live")).retryable)

            enqueue(429, "application/problem+json", "not-json")
            assertTrue(assertIs<CapsuleBlobUploadResult.Failure>(repository(server).uploadBlob(request(), "pm_at_live")).retryable)

            enqueue(429, "text/plain", "rate limited")
            assertTrue(assertIs<CapsuleBlobUploadResult.Failure>(repository(server).uploadBlob(request(), "pm_at_live")).retryable)

            enqueue(429, "application/problem+json", "x".repeat(64 * 1024 + 1))
            assertTrue(assertIs<CapsuleBlobUploadResult.Failure>(repository(server).uploadBlob(request(), "pm_at_live")).retryable)

            enqueue(418, "application/problem+json", "not-json")
            assertFalse(assertIs<CapsuleBlobUploadResult.Failure>(repository(server).uploadBlob(request(), "pm_at_live")).retryable)

            enqueue(201, "application/json", "not-json")
            assertFalse(assertIs<CapsuleBlobUploadResult.Failure>(repository(server).uploadBlob(request(), "pm_at_live")).retryable)
        }
    }

    @Test
    fun networkFailureIsRetryable() = runTest {
        val server = MockWebServer()
        server.start()
        val baseUrl = ApiBaseUrl.parse(server.url("/").toString())
        server.close()

        val failure = assertIs<CapsuleBlobUploadResult.Failure>(
            CapsuleBlobUploadRepository.create(baseUrl).uploadBlob(request(), "pm_at_live"),
        )
        assertEquals(CapsuleBlobUploadFailure.NETWORK, failure.reason)
        assertTrue(failure.retryable)
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

    @Test
    fun uploadUsesFrozenCiphertextAndHashAfterCallerMutatesInputsAndGetters() = runTest {
        withServer { server ->
            val originalCiphertext = "frozen-opaque-ciphertext".toByteArray()
            val originalSha256 = sha256(originalCiphertext)
            val expectedCiphertext = originalCiphertext.copyOf()
            val expectedSha256 = originalSha256.copyOf()
            val upload = CapsuleBlobUploadRequest(
                capsuleId = capsuleId,
                blobId = blobId,
                ciphertext = originalCiphertext,
                ciphertextSha256 = originalSha256,
                idempotencyKey = idempotencyKey,
            )
            originalCiphertext[0] = 0
            originalSha256[0] = 0
            upload.ciphertext[0] = 0
            upload.ciphertextSha256[0] = 0
            server.enqueue(MockResponse.Builder().code(204).build())

            val result = repository(server).uploadBlob(upload, accessToken = "pm_at_live")

            assertEquals(CapsuleBlobUploadResult.Success(204), result)
            val recorded = server.takeRequest()
            assertEquals(expectedCiphertext.toList(), recorded.body!!.toByteArray().toList())
            assertEquals(base64url(expectedSha256), recorded.headers["X-Remanence-Ciphertext-SHA256"])
            assertEquals(expectedCiphertext.size.toString(), recorded.headers["Content-Length"])
        }
    }

    private fun repository(server: MockWebServer): CapsuleBlobUploadRepository =
        CapsuleBlobUploadRepository.create(ApiBaseUrl.parse(server.url("/").toString()))

    private fun problemJson(code: String, status: Int, retryable: Boolean): String =
        """
        {"type":"https://remanence.invalid/problems/$code","title":"safe","status":$status,"code":"$code","detail":"$SECRET_DETAIL","request_id":"0198f0a0-0000-7000-8000-00000000ac01","retryable":$retryable}
        """.trimIndent()

    private companion object {
        val CIPHERTEXT = "opaque-ciphertext".toByteArray()
        const val SECRET_DETAIL = "private detail must not cross the repository boundary"

        fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

        fun base64url(value: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }
}
