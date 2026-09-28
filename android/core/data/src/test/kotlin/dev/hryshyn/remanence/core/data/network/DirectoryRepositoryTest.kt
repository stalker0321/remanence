package dev.hryshyn.remanence.core.data.network

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

class DirectoryRepositoryTest {

    private val userId = "1f0a1234-5678-4abc-9def-aabbccdd1001"
    private val keyBundleId = "2f0a1234-5678-4abc-9def-aabbccdd2002"

    private val responseJson = """
        {
          "user": {"user_id": "$userId", "handle": "mykola"},
          "key_bundle": {
            "key_bundle_id": "$keyBundleId",
            "user_id": "$userId",
            "suite": "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
            "protocol_version": 1,
            "encryption_public_keyset": "CIabc123",
            "signing_public_keyset": "CJxyz789",
            "status": "ACTIVE",
            "created_at": "2026-08-23T03:00:00Z"
          },
          "directory_version": "9f1c0d2e7a6b4c5d"
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

    private fun repository(server: MockWebServer, token: String = "pm_at_live"): DirectoryRepository =
        DirectoryRepository(
            client = HttpClientFactory.create().newBuilder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("Authorization", "Bearer $token")
                            .build(),
                    )
                }
                .build(),
            baseUrl = ApiBaseUrl.parse(server.url("/").toString()),
        )

    @Test
    fun foundMapsToDomainIdsAndKeyBundle() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(responseJson).build(),
            )
            val result = repository(server).lookup("mykola")

            val found = assertIs<DirectoryLookupResult.Found>(result)
            assertEquals(userId, found.snapshot.userId.toRestString())
            assertEquals("@mykola", found.snapshot.handle.toDisplayString())
            assertEquals(keyBundleId, found.snapshot.keyBundleId.toRestString())
            assertEquals(1, found.snapshot.protocolVersion)
            assertEquals("CIabc123", found.snapshot.encryptionPublicKeysetB64Url)
            assertEquals("9f1c0d2e7a6b4c5d", found.snapshot.directoryVersion)

            val recorded = server.takeRequest()
            assertEquals("/v1/directory/handles/mykola", recorded.url.encodedPath)
            assertEquals("Bearer pm_at_live", recorded.headers["Authorization"])
        }
    }

    @Test
    fun snapshotNeverCarriesEmail() = runTest {
        withServer { server ->
            // An unexpected extra field (like an email leak) must fail closed.
            val leaked = responseJson.replace(
                "\"directory_version\": \"9f1c0d2e7a6b4c5d\"",
                "\"directory_version\": \"9f1c0d2e7a6b4c5d\", \"email\": \"secret@example.com\"",
            )
            assertTrue(leaked.contains("secret@example.com"))
            server.enqueue(
                MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(leaked).build(),
            )
            val result = repository(server).lookup("mykola")
            assertIs<DirectoryLookupResult.Failure>(result)
            assertFalse(result.toString().contains("secret@example.com"))
        }
    }

    @Test
    fun unknownHandleMapsToNotFound() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(404)
                    .setHeader("Content-Type", "application/problem+json")
                    .body("""{"code":"HANDLE_NOT_FOUND"}""")
                    .build(),
            )
            assertEquals(DirectoryLookupResult.NotFound, repository(server).lookup("nobody"))
        }
    }

    @Test
    fun unauthorizedIsHttpFailureWithStatus() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(401)
                    .setHeader("Content-Type", "application/problem+json")
                    .body("""{"code":"AUTHENTICATION_REQUIRED"}""")
                    .build(),
            )
            val failure = assertIs<DirectoryLookupResult.Failure>(repository(server, token = "pm_at_dead").lookup("mykola"))
            assertEquals(DirectoryFailure.HTTP, failure.reason)
            assertEquals(401, failure.httpStatus)
        }
    }

    @Test
    fun inconsistentIdsBetweenSummaryAndBundleAreRejected() = runTest {
        withServer { server ->
            val root = Json.parseToJsonElement(responseJson).jsonObject.toMutableMap()
            val bundle = root["key_bundle"]!!.jsonObject.toMutableMap()
            bundle["user_id"] = JsonPrimitive("1f0a1234-5678-4abc-9def-aabbccdd1999")
            root["key_bundle"] = JsonObject(bundle)
            val tampered = JsonObject(root).toString()

            server.enqueue(
                MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(tampered).build(),
            )
            val failure = assertIs<DirectoryLookupResult.Failure>(repository(server).lookup("mykola"))
            assertEquals(DirectoryFailure.INVALID_RESPONSE, failure.reason)
        }
    }

    @Test
    fun handlePathSegmentIsPercentEncoded() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder().code(404).setHeader("Content-Type", "application/json").body("{}").build(),
            )
            repository(server).lookup("weird name")
            assertEquals("/v1/directory/handles/weird%20name", server.takeRequest().url.encodedPath)
        }
    }

    @Test
    fun successfulResponseBodyIsReadOffCallerThread() = runBlocking {
        val callerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "directory-lookup-caller")
        }
        val callerDispatcher = callerExecutor.asCoroutineDispatcher()
        try {
            withServer { server ->
                server.enqueue(
                    MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(responseJson).build(),
                )
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
                val repository = DirectoryRepository(
                    client,
                    ApiBaseUrl.parse(server.url("/").toString()),
                )

                val result = withContext(callerDispatcher) {
                    callerThread.set(Thread.currentThread())
                    repository.lookup("mykola")
                }

                val found = assertIs<DirectoryLookupResult.Found>(result)
                assertEquals(userId, found.snapshot.userId.toRestString())
                val caller = assertNotNull(callerThread.get())
                // Prefix match: kotlinx-coroutines may decorate the pool
                // thread name with " @coroutine#N" while the block runs.
                assertTrue(
                    caller.name.startsWith("directory-lookup-caller"),
                    "caller ran on unexpected thread: ${caller.name}",
                )
                assertNotEquals(caller, assertNotNull(bodyReadThread.get()))
            }
        } finally {
            callerDispatcher.close()
        }
    }

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
