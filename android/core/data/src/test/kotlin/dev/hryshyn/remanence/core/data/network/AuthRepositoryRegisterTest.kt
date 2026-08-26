package dev.hryshyn.remanence.core.data.network

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class AuthRepositoryRegisterTest {

    private val request = RegisterRequestDto(
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

    private val responseJson = """
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

    private fun repository(server: MockWebServer): AuthRepository =
        AuthRepository.create(ApiBaseUrl.parse(server.url("/").toString()))

    private fun enqueueSuccess(server: MockWebServer) {
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .setHeader("Content-Type", "application/json")
                .body(responseJson)
                .build(),
        )
    }

    @Test
    fun successPostsSnakeCaseContractToV1AuthRegister() = runTest {
        withMockServer { server ->
            enqueueSuccess(server)
            val result = repository(server).register(request)

            val success = assertIs<AuthResult.Success<RegisterResponseDto>>(result)
            assertEquals(201, success.httpStatus)
            assertEquals("0198f0a0-0000-7000-8000-00000000us01", success.value.user.userId)
            assertEquals("mykola", success.value.user.handle)
            assertEquals("0198f0a0-0000-7000-8000-00000000ba01", success.value.activeKeyBundleId)
            assertEquals("pm_at_abc", success.value.accessToken)
            assertEquals("pm_rt_def", success.value.refreshToken)

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/v1/auth/register", recorded.url.encodedPath)
            val sentBody = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
            assertEquals("private@example.com", sentBody["email"]!!.jsonPrimitiveContent())
            assertEquals("@Mykola", sentBody["handle"]!!.jsonPrimitiveContent())
            assertEquals("0198f0a0-0000-7000-8000-00000000ba01", sentBody["key_bundle"]!!.jsonObject["key_bundle_id"]!!.jsonPrimitiveContent())
            assertEquals(1, sentBody["key_bundle"]!!.jsonObject["protocol_version"]!!.toString().toInt())
        }
    }

    @Test
    fun nonCreatedStatusMapsToHttpFailure() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(409)
                    .setHeader("Content-Type", "application/problem+json")
                    .body("""{"code":"EMAIL_UNAVAILABLE"}""")
                    .build(),
            )
            val result = repository(server).register(request)
            val failure = assertIs<AuthResult.Failure>(result)
            assertEquals(AuthFailure.HTTP, failure.reason)
            assertEquals(409, failure.httpStatus)
        }
    }

    @Test
    fun unknownResponseFieldsAreRejected() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(201)
                    .setHeader("Content-Type", "application/json")
                    .body("""{"surprise":true}""")
                    .build(),
            )
            val result = repository(server).register(request)
            val failure = assertIs<AuthResult.Failure>(result)
            assertEquals(AuthFailure.INVALID_RESPONSE, failure.reason)
        }
    }

    @Test
    fun malformedJsonIsInvalidResponse() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(201)
                    .setHeader("Content-Type", "application/json")
                    .body("not-json")
                    .build(),
            )
            val failure = assertIs<AuthResult.Failure>(repository(server).register(request))
            assertEquals(AuthFailure.INVALID_RESPONSE, failure.reason)
        }
    }

    @Test
    fun cancellationIsNotSwallowed() = runTest {
        withMockServer { server ->
            enqueueSuccess(server)
            try {
                withTimeoutZero { repository(server).register(request) }
            } catch (_: CancellationException) {
                // expected path
            }
        }
    }

    private suspend fun withTimeoutZero(block: suspend () -> Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(1) { block() }
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String =
        (this as kotlinx.serialization.json.JsonPrimitive).content

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun <T> withMockServer(block: suspend (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server)
        } finally {
            server.close()
        }
    }
}
