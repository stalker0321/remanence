package dev.hryshyn.remanence.core.data.network

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.RecipientTarget
import dev.hryshyn.remanence.core.model.UserId

class ProductionApiStackCapsuleRepositoryTest {

    private val capsuleId = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000ca01")
    private val blobId = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b001")
    private val senderBundleId = KeyBundleId.parseRest("0198f0a0-0000-7000-8000-00000000ba01")
    private val recipientId = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a502")
    private val recipientBundleId = KeyBundleId.parseRest("0198f0a0-0000-7000-8000-00000000ba02")
    private val idempotencyKey = UUID.fromString("0198f0a0-0000-7000-8000-00000000ad01")

    @Test
    fun draftRepositoryUsesAuthenticatedStackAndRetriesOnceAfterRefresh() = runTest {
        val (result, trace) = withAuthenticatedStack(
            path = "/v1/capsules",
            success = MockResponse.Builder()
                .code(201)
                .setHeader("Content-Type", "application/json")
                .body(draftSuccessJson())
                .build(),
        ) { stack ->
            stack.capsuleDraftRepository.createDraft(draftRequest(), OLD_ACCESS)
        }

        assertIs<CapsuleDraftResult.Success>(result)
        assertEquals(201, result.httpStatus)
        assertRefreshAndRetry(trace)
    }

    @Test
    fun blobRepositoryUsesAuthenticatedStackAndRetriesOnceAfterRefresh() = runTest {
        val (result, trace) = withAuthenticatedStack(
            path = "/v1/capsules/${capsuleId.toRestString()}/blobs/${blobId.toRestString()}",
            success = MockResponse.Builder().code(204).build(),
        ) { stack ->
            stack.capsuleBlobUploadRepository.uploadBlob(blobRequest(), OLD_ACCESS)
        }

        assertIs<CapsuleBlobUploadResult.Success>(result)
        assertEquals(204, result.httpStatus)
        assertRefreshAndRetry(trace)
    }

    @Test
    fun recipientBlobDownloadUsesAuthenticatedStackAndRetriesOnceAfterRefresh() = runTest {
        val payload = "opaque-recipient-download".toByteArray()
        val root = Files.createTempDirectory("recipient-blob-stack-").toFile()
        try {
            val destination = File(root, "ciphertext.tmp")
            val (result, trace) = withAuthenticatedStack(
                path = "/v1/capsules/${capsuleId.toRestString()}/blobs/${blobId.toRestString()}",
                success = MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setHeader("Content-Length", payload.size)
                    .setHeader("ETag", "\"${sha256(payload).joinToString("") { "%02x".format(it.toInt() and 0xff) }}\"")
                    .body(Buffer().write(payload))
                    .build(),
            ) { stack ->
                stack.recipientBlobDownloadRepository.downloadBlob(
                    RecipientBlobDownloadRequest(
                        capsuleId = capsuleId,
                        blobId = blobId,
                        expectedCiphertextSize = payload.size.toLong(),
                        expectedCiphertextSha256 = sha256(payload),
                        destination = destination,
                    ),
                    OLD_ACCESS,
                )
            }

            assertIs<RecipientBlobDownloadResult.Success>(result)
            assertEquals(payload.toList(), destination.readBytes().toList())
            assertRefreshAndRetry(trace)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recipientMaterialSyncedUsesAuthenticatedStackAndRetriesOnceAfterRefresh() = runTest {
        val (result, trace) = withAuthenticatedStack(
            path = "/v1/capsules/${capsuleId.toRestString()}/material-synced",
            success = MockResponse.Builder().code(204).build(),
        ) { stack ->
            stack.recipientMaterialSyncedRepository.markMaterialSynced(capsuleId, OLD_ACCESS)
        }

        assertEquals(RecipientMaterialSyncedResult.Success(204), result)
        assertRefreshAndRetry(trace)
    }

    @Test
    fun finalizeRepositoryUsesAuthenticatedStackAndRetriesOnceAfterRefresh() = runTest {
        val (result, trace) = withAuthenticatedStack(
            path = "/v1/capsules/${capsuleId.toRestString()}/finalize",
            success = MockResponse.Builder()
                .code(201)
                .setHeader("Content-Type", "application/json")
                .body(finalizeSuccessJson())
                .build(),
        ) { stack ->
            stack.capsuleFinalizeRepository.finalize(finalizeRequest(), OLD_ACCESS)
        }

        assertIs<CapsuleFinalizeResult.Success>(result)
        assertEquals(201, result.httpStatus)
        assertRefreshAndRetry(trace)
    }

    private suspend fun <T> withAuthenticatedStack(
        path: String,
        success: MockResponse,
        call: suspend (ProductionApiStack) -> T,
    ): Pair<T, AuthTrace> {
        val server = MockWebServer()
        val trace = AuthTrace(path, success)
        server.dispatcher = trace
        server.start()
        try {
            val tokens = AuthTokenHolder(OLD_ACCESS, OLD_REFRESH)
            val sink = RecordingRotationSink()
            val stack = ProductionApiStack.create(
                baseUrl = ApiBaseUrl.parse(server.url("/").toString()),
                tokens = tokens,
                refreshTokenReader = RefreshTokenReader { tokens.refreshToken },
                rotationSink = sink,
            )
            val result = call(stack)
            trace.tokens = tokens
            trace.sink = sink
            return result to trace
        } finally {
            server.close()
        }
    }

    private fun assertRefreshAndRetry(trace: AuthTrace) {
        assertEquals(1, trace.refreshCount.get())
        assertEquals(2, trace.protectedCount.get())
        assertEquals(
            listOf("Bearer $OLD_ACCESS", "Bearer $NEW_ACCESS"),
            trace.protectedAuthorization.toList(),
        )
        assertEquals(listOf<String?>(null), trace.refreshAuthorization.toList())
        assertEquals(NEW_ACCESS, trace.tokens.accessToken)
        assertEquals(NEW_REFRESH, trace.tokens.refreshToken)
        assertEquals(listOf(NEW_ACCESS to NEW_REFRESH), trace.sink.rotations)
    }

    private fun draftRequest(): CapsuleDraftRequest = CapsuleDraftRequest(
        capsuleId = capsuleId,
        senderKeyBundleId = senderBundleId,
        recipientTarget = RecipientTarget.ExistingUser(recipientId, recipientBundleId),
        idempotencyKey = idempotencyKey,
        blobs = listOf(
            CapsuleDraftBlobDeclaration(
                blobId = blobId,
                kind = CapsuleArtifactKind.RECOGNITION_MANIFEST,
                ordinal = null,
                ciphertextSize = 1,
                ciphertextSha256 = ByteArray(32) { it.toByte() },
            ),
        ),
    )

    private fun blobRequest(): CapsuleBlobUploadRequest = CapsuleBlobUploadRequest(
        capsuleId = capsuleId,
        blobId = blobId,
        ciphertext = byteArrayOf(1, 2, 3),
        ciphertextSha256 = sha256(byteArrayOf(1, 2, 3)),
        idempotencyKey = idempotencyKey,
    )

    private fun finalizeRequest(): CapsuleFinalizeRequest = CapsuleFinalizeRequest(
        capsuleId = capsuleId,
        statement = byteArrayOf(1, 2, 3),
        signature = ByteArray(69) { 4 },
        senderKeyBundleId = senderBundleId,
        recipientKeyBundleId = recipientBundleId,
        recipientEnvelopeCiphertext = byteArrayOf(5, 6, 7),
        recipientEnvelopeCiphertextSha256 = sha256(byteArrayOf(5, 6, 7)),
    )

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private class RecordingRotationSink : SessionRotationSink {
        val rotations = mutableListOf<Pair<String, String>>()

        override fun rotate(accessToken: String, refreshToken: String) {
            rotations += accessToken to refreshToken
        }

        override fun clear() = Unit
    }

    private class AuthTrace(
        private val protectedPath: String,
        private val success: MockResponse,
    ) : Dispatcher() {
        val refreshCount = AtomicInteger()
        val protectedCount = AtomicInteger()
        val protectedAuthorization = CopyOnWriteArrayList<String?>()
        val refreshAuthorization = CopyOnWriteArrayList<String?>()
        lateinit var tokens: AuthTokenHolder
        lateinit var sink: RecordingRotationSink

        override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
            "/v1/auth/refresh" -> {
                refreshCount.incrementAndGet()
                refreshAuthorization += request.headers["Authorization"]
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(REFRESH_RESPONSE)
                    .build()
            }
            protectedPath -> {
                protectedCount.incrementAndGet()
                val authorization = request.headers["Authorization"]
                protectedAuthorization += authorization
                if (authorization == "Bearer $OLD_ACCESS") {
                    MockResponse.Builder().code(401).build()
                } else if (authorization == "Bearer $NEW_ACCESS") {
                    success
                } else {
                    MockResponse.Builder().code(401).build()
                }
            }
            else -> MockResponse.Builder().code(404).build()
        }
    }

    private fun draftSuccessJson(): String =
        """
        {
          "capsule_id": "${capsuleId.toRestString()}",
          "state": "DRAFT",
          "draft_expires_at": "2026-08-30T03:00:00Z",
          "blobs": [{"blob_id": "${blobId.toRestString()}", "state": "DECLARED"}]
        }
        """.trimIndent()

    private fun finalizeSuccessJson(): String =
        """
        {
          "capsule_id": "${capsuleId.toRestString()}",
          "state": "READY",
          "ready_at": "2026-08-30T03:00:00Z"
        }
        """.trimIndent()

    private companion object {
        const val OLD_ACCESS = "pm_at_old"
        const val OLD_REFRESH = "pm_rt_old"
        const val NEW_ACCESS = "pm_at_new"
        const val NEW_REFRESH = "pm_rt_new"
        const val REFRESH_RESPONSE =
            """
            {
              "session_id": "session-1",
              "access_token": "pm_at_new",
              "access_expires_at": "2026-08-23T04:15:00Z",
              "refresh_token": "pm_rt_new",
              "refresh_expires_at": "2026-09-22T04:00:00Z"
            }
            """
    }
}
