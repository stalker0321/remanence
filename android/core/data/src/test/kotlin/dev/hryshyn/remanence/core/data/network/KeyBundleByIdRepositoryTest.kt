package dev.hryshyn.remanence.core.data.network

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

class KeyBundleByIdRepositoryTest {

    private val userId = "1f0a1234-5678-4abc-9def-aabbccdd1001"
    private val keyBundleId = "2f0a1234-5678-4abc-9def-aabbccdd2002"

    private fun bundleJson(status: String) = """
        {
          "key_bundle_id": "$keyBundleId",
          "user_id": "$userId",
          "suite": "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
          "protocol_version": 1,
          "encryption_public_keyset": "CIenc",
          "signing_public_keyset": "CJsig",
          "status": "$status",
          "created_at": "2026-08-23T03:00:00Z"
        }
    """.trimIndent()

    private suspend fun <T> withServer(block: suspend (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server)
        } finally {
            server.close()
        }
    }

    private fun repository(server: MockWebServer): KeyBundleByIdRepository =
        KeyBundleByIdRepository.create(ApiBaseUrl.parse(server.url("/").toString()))

    @Test
    fun activeBundleIsFetchableForSignatureVerification() = runTest {
        withServer { server ->
            server.enqueue(json(bundleJson("ACTIVE")))
            val result = repository(server).fetch(keyBundleId, accessToken = "pm_at_live")

            val found = assertIs<KeyBundleByIdResult.Found>(result)
            assertEquals(keyBundleId, found.bundle.keyBundleId.toRestString())
            assertEquals(userId, found.bundle.ownerUserId.toRestString())
            assertEquals("ACTIVE", found.bundle.status)
            assertEquals("CJsig", found.bundle.signingPublicKeysetB64Url)

            val recorded = server.takeRequest()
            assertEquals("/v1/directory/key-bundles/$keyBundleId", recorded.url.encodedPath)
            assertEquals("Bearer pm_at_live", recorded.headers["Authorization"])
        }
    }

    @Test
    fun retiredAndRevokedBundlesRemainAvailableWithStatus() = runTest {
        withServer { server ->
            for (status in listOf("RETIRED", "REVOKED", "ACTIVE")) {
                server.enqueue(json(bundleJson(status)))
                val result = repository(server).fetch(keyBundleId, accessToken = "pm_at_live")
                assertEquals(status, assertIs<KeyBundleByIdResult.Found>(result).bundle.status)
            }
        }
    }

    @Test
    fun unknownBundleMapsToNotFound() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(404)
                    .setHeader("Content-Type", "application/problem+json")
                    .body("""{"code":"KEY_BUNDLE_NOT_FOUND"}""")
                    .build(),
            )
            assertEquals(
                KeyBundleByIdResult.NotFound,
                repository(server).fetch("3f0a1234-5678-4abc-9def-aabbccdd3999", accessToken = "pm_at_live"),
            )
        }
    }

    @Test
    fun unknownStatusFailsClosed() = runTest {
        withServer { server ->
            server.enqueue(json(bundleJson("SUSPENDED")))
            val failure = assertIs<KeyBundleByIdResult.Failure>(repository(server).fetch(keyBundleId, accessToken = "pm_at_live"))
            assertEquals(KeyBundleFailure.INVALID_RESPONSE, failure.reason)
        }
    }

    @Test
    fun unauthorizedIsHttpFailure() = runTest {
        withServer { server ->
            server.enqueue(MockResponse.Builder().code(401).build())
            val failure = assertIs<KeyBundleByIdResult.Failure>(repository(server).fetch(keyBundleId, accessToken = "pm_at_dead"))
            assertEquals(KeyBundleFailure.HTTP, failure.reason)
            assertEquals(401, failure.httpStatus)
        }
    }

    @Test
    fun successfulResponseBodyIsReadOffCallerThread() = runBlocking {
        val callerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "key-bundle-by-id-caller")
        }
        val callerDispatcher = callerExecutor.asCoroutineDispatcher()
        try {
            withServer { server ->
                server.enqueue(json(bundleJson("ACTIVE")))
                val callerThread = AtomicReference<Thread?>()
                val bodyReadThread = AtomicReference<Thread?>()
                val client = HttpClientFactory.create().newBuilder()
                    .addInterceptor { chain ->
                        val response = chain.proceed(chain.request())
                        response.newBuilder()
                            .body(
                                ReadThreadTrackingResponseBody(response.body) { thread ->
                                    bodyReadThread.compareAndSet(null, thread)
                                },
                            )
                            .build()
                    }
                    .build()
                val repository = KeyBundleByIdRepository(
                    client,
                    ApiBaseUrl.parse(server.url("/").toString()),
                )

                val result = withContext(callerDispatcher) {
                    callerThread.set(Thread.currentThread())
                    repository.fetch(keyBundleId, accessToken = "pm_at_live")
                }

                val found = assertIs<KeyBundleByIdResult.Found>(result)
                assertEquals("ACTIVE", found.bundle.status)
                val caller = assertNotNull(callerThread.get())
                // Prefix match: kotlinx-coroutines may decorate the pool
                // thread name with " @coroutine#N" while the block runs.
                assertTrue(
                    caller.name.startsWith("key-bundle-by-id-caller"),
                    "caller ran on unexpected thread: ${caller.name}",
                )
                assertNotEquals(caller, assertNotNull(bodyReadThread.get()))
            }
        } finally {
            callerDispatcher.close()
        }
    }

    private fun json(body: String): MockResponse =
        MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build()

    private class ReadThreadTrackingResponseBody(
        private val delegate: ResponseBody,
        private val onRead: (Thread) -> Unit,
    ) : ResponseBody() {
        override fun contentType() = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        override fun source(): BufferedSource = object : ForwardingSource(delegate.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                onRead(Thread.currentThread())
                return super.read(sink, byteCount)
            }
        }.buffer()
    }
}
