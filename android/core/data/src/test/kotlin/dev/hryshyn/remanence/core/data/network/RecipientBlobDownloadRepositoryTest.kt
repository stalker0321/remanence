package dev.hryshyn.remanence.core.data.network

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.ProtocolV1Limits

class RecipientBlobDownloadRepositoryTest {

    private val capsuleId = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000ca11")
    private val blobId = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b011")

    @Test
    fun validResponseStreamsOpaqueBytesWithExactHeaders() = runTest {
        withServer { server ->
            val root = tempRoot()
            try {
                val destination = File(root, "ciphertext.tmp")
                val payload = ByteArray(32 * 1024) { index -> (index * 31).toByte() }
                server.enqueue(success(payload).build())

                val result = repository(server).downloadBlob(
                    request(destination, payload),
                    accessToken = "pm_at_live",
                )

                val success = assertIs<RecipientBlobDownloadResult.Success>(result)
                assertEquals(destination, success.ciphertextFile)
                assertEquals(payload.size.toLong(), success.sizeBytes)
                assertEquals(payload.toList(), destination.readBytes().toList())
                assertFalse(success.toString().contains(destination.path))

                val recorded = server.takeRequest()
                assertEquals("GET", recorded.method)
                assertEquals(
                    "/v1/capsules/${capsuleId.toRestString()}/blobs/${blobId.toRestString()}",
                    recorded.url.encodedPath,
                )
                assertEquals("Bearer pm_at_live", recorded.headers["Authorization"])
                assertEquals("application/octet-stream", recorded.headers["Accept"])
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun successRejectsMissingDuplicateMalformedAndUnexpectedHeaders() = runTest {
        withServer { server ->
            val payload = "opaque-download".toByteArray()
            val cases = listOf<(MockResponse.Builder, Long) -> Unit>(
                { builder, _ -> builder.removeHeader("Content-Type") },
                { builder, _ -> builder.setHeader("Content-Type", "application/octet-stream; charset=utf-8") },
                { builder, _ -> builder.removeHeader("Content-Length") },
                { builder, size -> builder.setHeader("Content-Length", "00$size") },
                { builder, size -> builder.addHeader("Content-Length", size) },
                { builder, _ -> builder.removeHeader("ETag") },
                { builder, _ -> builder.setHeader("ETag", "W/\"weak\"") },
                { builder, _ -> builder.setHeader("Content-Encoding", "gzip") },
                { builder, _ -> builder.setHeader("Transfer-Encoding", "chunked") },
                { builder, _ -> builder.setHeader("Content-Range", "bytes 0-1/2") },
                { builder, _ -> builder.setHeader("Trailer", "Digest") },
            )
            cases.forEach { mutate ->
                val root = tempRoot()
                try {
                    val destination = File(root, "ciphertext.tmp")
                    val response = success(payload)
                    mutate(response, payload.size.toLong())
                    server.enqueue(response.build())
                    val failure = assertIs<RecipientBlobDownloadResult.Failure>(
                        repository(server).downloadBlob(request(destination, payload), ACCESS_TOKEN),
                    )
                    assertEquals(RecipientBlobDownloadFailure.INVALID_RESPONSE, failure.reason)
                    assertFalse(failure.retryable)
                    assertFalse(destination.exists())
                } finally {
                    root.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun truncatedHashMismatchAndOversizeDeclarationsNeverLeaveCiphertext() = runTest {
        withServer { server ->
            val payload = "opaque-download-payload".toByteArray()

            val truncatedRoot = tempRoot()
            try {
                val destination = File(truncatedRoot, "truncated.tmp")
                val truncated = payload.copyOf(payload.size - 1)
                server.enqueue(
                    success(truncated)
                        .setHeader("Content-Length", payload.size)
                        .setHeader("ETag", etag(sha256(payload)))
                        .onResponseEnd(SocketEffect.CloseSocket())
                        .build(),
                )
                val failure = assertIs<RecipientBlobDownloadResult.Failure>(
                    repository(server).downloadBlob(request(destination, payload), ACCESS_TOKEN),
                )
                assertTrue(
                    failure.reason == RecipientBlobDownloadFailure.NETWORK ||
                        failure.reason == RecipientBlobDownloadFailure.INTEGRITY_FAILED,
                )
                assertFalse(destination.exists())
            } finally {
                truncatedRoot.deleteRecursively()
            }

            val hashRoot = tempRoot()
            try {
                val destination = File(hashRoot, "hash.tmp")
                val wrongHash = sha256("different-ciphertext".toByteArray())
                server.enqueue(
                    success(payload)
                        .setHeader("ETag", etag(wrongHash))
                        .build(),
                )
                val failure = assertIs<RecipientBlobDownloadResult.Failure>(
                    repository(server).downloadBlob(
                        request(destination, payload, expectedHash = wrongHash),
                        ACCESS_TOKEN,
                    ),
                )
                assertEquals(RecipientBlobDownloadFailure.INTEGRITY_FAILED, failure.reason)
                assertFalse(destination.exists())
            } finally {
                hashRoot.deleteRecursively()
            }

            val oversizeRoot = tempRoot()
            try {
                val destination = File(oversizeRoot, "oversize.tmp")
                server.enqueue(success(payload).build())
                val failure = assertIs<RecipientBlobDownloadResult.Failure>(
                    repository(server).downloadBlob(
                        request(destination, payload.copyOf(payload.size - 1)),
                        ACCESS_TOKEN,
                    ),
                )
                assertEquals(RecipientBlobDownloadFailure.INVALID_RESPONSE, failure.reason)
                assertFalse(destination.exists())
            } finally {
                oversizeRoot.deleteRecursively()
            }
        }
    }

    @Test
    fun httpFailuresUseExplicitSafeMappingAndNeverRetainProblemDetails() = runTest {
        withServer { server ->
            val cases = listOf(
                Triple(401, "AUTH_INVALID", false) to RecipientBlobDownloadFailure.AUTH_INVALID,
                Triple(404, "CAPSULE_NOT_FOUND", false) to RecipientBlobDownloadFailure.NOT_FOUND,
                Triple(429, "RATE_LIMITED", true) to RecipientBlobDownloadFailure.RATE_LIMITED,
                Triple(503, "INTERNAL_ERROR", true) to RecipientBlobDownloadFailure.INTERNAL_ERROR,
                Triple(500, "INTERNAL_ERROR", false) to RecipientBlobDownloadFailure.INTERNAL_ERROR,
            )
            cases.forEach { (responseCase, expectedReason) ->
                val (status, code, retryable) = responseCase
                val root = tempRoot()
                try {
                    val destination = File(root, "failure.tmp")
                    server.enqueue(problem(status, code, retryable))
                    val failure = assertIs<RecipientBlobDownloadResult.Failure>(
                        repository(server).downloadBlob(request(destination), ACCESS_TOKEN),
                    )
                    assertEquals(expectedReason, failure.reason)
                    assertEquals(status, failure.httpStatus)
                    assertEquals(retryable, failure.retryable)
                    assertFalse(failure.toString().contains(PRIVATE_DETAIL))
                    assertFalse(destination.exists())
                } finally {
                    root.deleteRecursively()
                }
            }

            val malformedRoot = tempRoot()
            try {
                val destination = File(malformedRoot, "malformed.tmp")
                server.enqueue(
                    MockResponse.Builder()
                        .code(503)
                        .setHeader("Content-Type", "application/problem+json")
                        .body("x".repeat(64 * 1024 + 1))
                        .build(),
                )
                val failure = assertIs<RecipientBlobDownloadResult.Failure>(
                    repository(server).downloadBlob(request(destination), ACCESS_TOKEN),
                )
                assertEquals(RecipientBlobDownloadFailure.HTTP, failure.reason)
                assertTrue(failure.retryable)
                assertFalse(failure.toString().contains(PRIVATE_DETAIL))
                assertFalse(destination.exists())
            } finally {
                malformedRoot.deleteRecursively()
            }

            val unknownRoot = tempRoot()
            try {
                val destination = File(unknownRoot, "unknown.tmp")
                server.enqueue(
                    MockResponse.Builder()
                        .code(418)
                        .setHeader("Content-Type", "application/problem+json")
                        .body("not-json")
                        .build(),
                )
                val failure = assertIs<RecipientBlobDownloadResult.Failure>(
                    repository(server).downloadBlob(request(destination), ACCESS_TOKEN),
                )
                assertEquals(RecipientBlobDownloadFailure.INVALID_RESPONSE, failure.reason)
                assertFalse(failure.retryable)
                assertFalse(destination.exists())
            } finally {
                unknownRoot.deleteRecursively()
            }
        }
    }

    @Test
    fun networkAndSuccessfulProblemBodyReadFailuresAreRetryable() = runTest {
        val server = MockWebServer()
        server.start()
        val baseUrl = ApiBaseUrl.parse(server.url("/").toString())
        server.close()
        val root = tempRoot()
        try {
            val failure = assertIs<RecipientBlobDownloadResult.Failure>(
                RecipientBlobDownloadRepository.create(baseUrl).downloadBlob(
                    request(File(root, "network.tmp")),
                    ACCESS_TOKEN,
                ),
            )
            assertEquals(RecipientBlobDownloadFailure.NETWORK, failure.reason)
            assertTrue(failure.retryable)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidInputsAndPreexistingDestinationAreRejectedBeforeNetwork() = runTest {
        withServer { server ->
            val root = tempRoot()
            try {
                val sentinel = File(root, "sentinel.txt").apply { writeText(PRIVATE_DETAIL) }
                val existing = File(root, "existing.tmp").apply { writeText("keep me") }
                val existingFailure = repository(server).downloadBlob(request(existing), ACCESS_TOKEN)
                assertEquals(
                    RecipientBlobDownloadFailure.DESTINATION_NOT_FRESH,
                    assertIs<RecipientBlobDownloadResult.Failure>(existingFailure).reason,
                )
                assertEquals("keep me", existing.readText())
                assertEquals(PRIVATE_DETAIL, sentinel.readText())

                var emptyTokenRejected = false
                try {
                    repository(server).downloadBlob(request(File(root, "empty-token.tmp")), "")
                } catch (_: IllegalArgumentException) {
                    emptyTokenRejected = true
                }
                assertTrue(emptyTokenRejected)
                assertFailsWith<IllegalArgumentException> {
                    RecipientBlobDownloadRequest(
                        capsuleId = capsuleId,
                        blobId = blobId,
                        expectedCiphertextSize = 0,
                        expectedCiphertextSha256 = sha256(PAYLOAD),
                        destination = File(root, "bad-size.tmp"),
                    )
                }
                assertFailsWith<IllegalArgumentException> {
                    RecipientBlobDownloadRequest(
                        capsuleId = capsuleId,
                        blobId = blobId,
                        expectedCiphertextSize = ProtocolV1Limits.ENCRYPTED_PHOTO_MAX_CIPHERTEXT_BYTES + 1L,
                        expectedCiphertextSha256 = sha256(PAYLOAD),
                        destination = File(root, "over-limit.tmp"),
                    )
                }
                assertFailsWith<IllegalArgumentException> {
                    RecipientBlobDownloadRequest(
                        capsuleId = capsuleId,
                        blobId = blobId,
                        expectedCiphertextSize = PAYLOAD.size.toLong(),
                        expectedCiphertextSha256 = ByteArray(31),
                        destination = File(root, "bad-hash.tmp"),
                    )
                }
                assertFalse(server.requestCount > 0)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun writeFailureAndCancellationDeleteOnlyThisInvocationFile() = runTest {
        withServer { server ->
            val payload = PAYLOAD
            val root = tempRoot()
            try {
                val sentinel = File(root, "sentinel.txt").apply { writeText(PRIVATE_DETAIL) }
                val writeDestination = File(root, "write.tmp")
                server.enqueue(success(payload).build())
                val writeFailureRepository = RecipientBlobDownloadRepository(
                    OkHttpClient.Builder().build(),
                    ApiBaseUrl.parse(server.url("/").toString()),
                ) { object : OutputStream() {
                    override fun write(b: Int) = throw IOException("write failed")
                    override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("write failed")
                } }
                val writeFailure = assertIs<RecipientBlobDownloadResult.Failure>(
                    writeFailureRepository.downloadBlob(request(writeDestination, payload), ACCESS_TOKEN),
                )
                assertEquals(RecipientBlobDownloadFailure.LOCAL_STORAGE, writeFailure.reason)
                assertTrue(writeFailure.retryable)
                assertFalse(writeDestination.exists())
                assertEquals(PRIVATE_DETAIL, sentinel.readText())

                val cancellationDestination = File(root, "cancel.tmp")
                server.enqueue(success(payload).build())
                val cancellation = CancellationException("caller cancelled")
                val cancellationRepository = RecipientBlobDownloadRepository(
                    OkHttpClient.Builder().build(),
                    ApiBaseUrl.parse(server.url("/").toString()),
                ) { throw cancellation }
                assertFailsWith<CancellationException> {
                    cancellationRepository.downloadBlob(request(cancellationDestination, payload), ACCESS_TOKEN)
                }
                assertFalse(cancellationDestination.exists())
                assertEquals(PRIVATE_DETAIL, sentinel.readText())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun repository(server: MockWebServer): RecipientBlobDownloadRepository =
        RecipientBlobDownloadRepository.create(ApiBaseUrl.parse(server.url("/").toString()))

    private fun request(
        destination: File,
        payload: ByteArray = PAYLOAD,
        expectedHash: ByteArray = sha256(payload),
    ): RecipientBlobDownloadRequest = RecipientBlobDownloadRequest(
        capsuleId = capsuleId,
        blobId = blobId,
        expectedCiphertextSize = payload.size.toLong(),
        expectedCiphertextSha256 = expectedHash,
        destination = destination,
    )

    private fun success(payload: ByteArray): MockResponse.Builder =
        MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/octet-stream")
            .setHeader("Content-Length", payload.size)
            .setHeader("ETag", etag(sha256(payload)))
            .body(Buffer().write(payload))

    private fun problem(status: Int, code: String, retryable: Boolean): MockResponse =
        MockResponse.Builder()
            .code(status)
            .setHeader("Content-Type", "application/problem+json")
            .body(
                """
                {"type":"https://remanence.invalid/problems/$code","title":"safe","status":$status,"code":"$code","detail":"$PRIVATE_DETAIL","request_id":"0198f0a0-0000-7000-8000-00000000ac11","retryable":$retryable}
                """.trimIndent(),
            )
            .build()

    private suspend fun <T> withServer(block: suspend (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server)
        } finally {
            server.close()
        }
    }

    private fun tempRoot(): File = Files.createTempDirectory("remanence-recipient-blob-").toFile()

    private companion object {
        const val ACCESS_TOKEN = "pm_at_live"
        const val PRIVATE_DETAIL = "private detail must not cross the repository boundary"
        val PAYLOAD = "opaque-recipient-ciphertext".toByteArray()

        fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

        fun etag(value: ByteArray): String =
            "\"" + value.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) } + "\""
    }
}
