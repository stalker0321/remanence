package dev.hryshyn.remanence.core.data.network

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/**
 * T020: register/login response-body consumption and the logout response
 * close must not run on the thread that called the repository.
 *
 * Each test invokes the repository from a named single-thread caller,
 * records the actual Okio read/close threads through an interceptor-wrapped
 * body, and compares thread identity (never names, so kotlinx-coroutines
 * ` @coroutine#N` decoration cannot flake the assertion). A missing record
 * fails loudly through `assertNotNull`, so "nothing happened" can never
 * satisfy the assertions. No sleeps, clocks or virtual time.
 */
class AuthRepositoryResponseThreadTest {

    private val registerRequest = RegisterRequestDto(
        email = "private@example.com",
        password = "correct horse battery staple",
        handle = "@Mykola",
        keyBundle = RegisterKeyBundleDto(
            keyBundleId = "0198f0a0-0000-7000-8000-00000000ba01",
            suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
            protocolVersion = 1,
            encryptionPublicKeyset = "CIabc123",
            signingPublicKeyset = "CJxyz789",
        ),
    )

    private val registerResponseJson = """
        {
          "user": {
            "user_id": "0198f0a0-0000-7000-8000-00000000us01",
            "email": "private@example.com",
            "handle": "mykola",
            "created_at": "2026-08-23T03:00:00Z"
          },
          "active_key_bundle_id": "0198f0a0-0000-7000-8000-00000000ba01",
          "access_token": "pm_at_abc",
          "access_expires_at": "2026-08-23T03:15:00Z",
          "refresh_token": "pm_rt_def",
          "refresh_expires_at": "2026-09-22T03:00:00Z"
        }
    """.trimIndent()

    private val loginResponseJson = """
        {
          "user": {
            "user_id": "0198f0a0-0000-7000-8000-00000000us01",
            "email": "private@example.com",
            "handle": "mykola",
            "created_at": "2026-08-23T03:00:00Z"
          },
          "active_key_bundle": {
            "key_bundle_id": "0198f0a0-0000-7000-8000-00000000ba01",
            "suite": "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
            "protocol_version": 1,
            "status": "ACTIVE"
          },
          "session_id": "0198f0a0-0000-7000-8000-00000000se01",
          "access_token": "pm_at_abc",
          "access_expires_at": "2026-08-23T03:15:00Z",
          "refresh_token": "pm_rt_def",
          "refresh_expires_at": "2026-09-22T03:00:00Z"
        }
    """.trimIndent()

    @Test
    fun registerSuccessfulBodyReadRunsOffCallerThread() = runBlocking {
        withServer { server ->
            server.enqueue(jsonResponse(201, registerResponseJson))
            val callerThread = AtomicReference<Thread?>()
            val bodyReadThread = AtomicReference<Thread?>()

            val result = onNamedCaller("auth-register-caller", callerThread) {
                repository(server, bodyReadThread, AtomicReference()).register(registerRequest)
            }

            assertIs<AuthResult.Success<RegisterResponseDto>>(result)
            val caller = assertNotNull(callerThread.get())
            assertTrue(
                caller.name.startsWith("auth-register-caller"),
                "caller ran on unexpected thread: ${caller.name}",
            )
            assertNotEquals(caller, assertNotNull(bodyReadThread.get()))
        }
    }

    @Test
    fun loginSuccessfulBodyReadRunsOffCallerThread() = runBlocking {
        withServer { server ->
            server.enqueue(jsonResponse(200, loginResponseJson))
            val callerThread = AtomicReference<Thread?>()
            val bodyReadThread = AtomicReference<Thread?>()

            val result = onNamedCaller("auth-login-caller", callerThread) {
                repository(server, bodyReadThread, AtomicReference())
                    .login(LoginRequestDto("private@example.com", "secret-password"))
            }

            assertIs<AuthResult.Success<LoginResponseDto>>(result)
            val caller = assertNotNull(callerThread.get())
            assertTrue(
                caller.name.startsWith("auth-login-caller"),
                "caller ran on unexpected thread: ${caller.name}",
            )
            assertNotEquals(caller, assertNotNull(bodyReadThread.get()))
        }
    }

    @Test
    fun logoutResponseCloseRunsOffCallerThread() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse.Builder().code(204).build())
            val callerThread = AtomicReference<Thread?>()
            val closeThread = AtomicReference<Thread?>()

            val result = onNamedCaller("auth-logout-caller", callerThread) {
                repository(server, AtomicReference(), closeThread).logout("pm_at_live")
            }

            assertIs<AuthResult.Success<Unit>>(result)
            val caller = assertNotNull(callerThread.get())
            assertTrue(
                caller.name.startsWith("auth-logout-caller"),
                "caller ran on unexpected thread: ${caller.name}",
            )
            // Response.close() reaches the wrapped body; a missing record
            // fails here instead of passing vacuously.
            assertNotEquals(caller, assertNotNull(closeThread.get()))
        }
    }

    private fun repository(
        server: MockWebServer,
        onRead: AtomicReference<Thread?>,
        onClose: AtomicReference<Thread?>,
    ): AuthRepository =
        AuthRepository(
            trackingClient(onRead, onClose),
            ApiBaseUrl.parse(server.url("/").toString()),
        )

    private fun trackingClient(
        onRead: AtomicReference<Thread?>,
        onClose: AtomicReference<Thread?>,
    ): OkHttpClient =
        HttpClientFactory.create().newBuilder()
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                response.newBuilder()
                    .body(
                        ThreadTrackingResponseBody(response.body) { readThread, closeThread ->
                            readThread?.let { onRead.compareAndSet(null, it) }
                            closeThread?.let { onClose.compareAndSet(null, it) }
                        },
                    )
                    .build()
            }
            .build()

    private suspend fun <T> onNamedCaller(
        name: String,
        callerThread: AtomicReference<Thread?>,
        block: suspend () -> T,
    ): T {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name) }
        val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
        try {
            return withContext(dispatcher) {
                callerThread.set(Thread.currentThread())
                block()
            }
        } finally {
            dispatcher.close()
        }
    }

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse.Builder()
            .code(code)
            .setHeader("Content-Type", "application/json")
            .body(body)
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

    private class ThreadTrackingResponseBody(
        private val delegate: ResponseBody,
        private val onThread: (read: Thread?, close: Thread?) -> Unit,
    ) : ResponseBody() {
        override fun contentType() = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        override fun close() {
            onThread(null, Thread.currentThread())
            super.close()
        }

        override fun source(): BufferedSource = object : ForwardingSource(delegate.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                onThread(Thread.currentThread(), null)
                return super.read(sink, byteCount)
            }

            override fun close() {
                onThread(null, Thread.currentThread())
                super.close()
            }
        }.buffer()
    }
}
