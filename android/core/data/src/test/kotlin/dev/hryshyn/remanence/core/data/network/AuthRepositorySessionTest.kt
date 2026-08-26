package dev.hryshyn.remanence.core.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class AuthRepositorySessionTest {

    private fun repository(server: MockWebServer): AuthRepository =
        AuthRepository.create(ApiBaseUrl.parse(server.url("/").toString()))

    private suspend fun <T> withServer(block: suspend (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server)
        } finally {
            server.close()
        }
    }

    private fun jsonResponse(body: String, code: Int = 200): MockResponse =
        MockResponse.Builder()
            .code(code)
            .setHeader("Content-Type", "application/json")
            .body(body)
            .build()

    @Test
    fun loginPostsEmailAndPasswordAndDecodesBundleMetadata() = runTest {
        withServer { server ->
            server.enqueue(
                jsonResponse(
                    """
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
                    """.trimIndent(),
                ),
            )
            val result = repository(server).login(LoginRequestDto("private@example.com", "secret-password"))

            val success = assertIs<AuthResult.Success<LoginResponseDto>>(result)
            assertEquals(200, success.httpStatus)
            assertEquals("mykola", success.value.user.handle)
            assertEquals("ACTIVE", success.value.activeKeyBundle.status)
            assertEquals("0198f0a0-0000-7000-8000-00000000se01", success.value.sessionId)

            val recorded = server.takeRequest()
            assertEquals("/v1/auth/login", recorded.url.encodedPath)
            val sentBody = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
            assertEquals("private@example.com", (sentBody["email"] as kotlinx.serialization.json.JsonPrimitive).content)
            assertTruePasswordPresent(sentBody)
        }
    }

    @Test
    fun refreshPostsOnlyRefreshTokenAndRotatesBothTokens() = runTest {
        withServer { server ->
            server.enqueue(
                jsonResponse(
                    """
                    {
                      "session_id": "0198f0a0-0000-7000-8000-00000000se01",
                      "access_token": "pm_at_rotated",
                      "access_expires_at": "2026-08-23T04:15:00Z",
                      "refresh_token": "pm_rt_rotated",
                      "refresh_expires_at": "2026-09-22T04:00:00Z"
                    }
                    """.trimIndent(),
                ),
            )
            val result = repository(server).refresh(RefreshRequestDto("pm_rt_old"))

            val success = assertIs<AuthResult.Success<RefreshResponseDto>>(result)
            assertEquals("pm_rt_rotated", success.value.refreshToken)
            assertEquals("pm_at_rotated", success.value.accessToken)

            val recorded = server.takeRequest()
            assertEquals("/v1/auth/refresh", recorded.url.encodedPath)
            val sentBody = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
            assertEquals(setOf("refresh_token"), sentBody.keys, "refresh request must carry only the refresh token")
        }
    }

    @Test
    fun replayedRefreshMapsToHttpFailureWithStatus() = runTest {
        withServer { server ->
            server.enqueue(jsonResponse("""{"code":"SESSION_REPLAYED"}""", code = 409))
            val failure = assertIs<AuthResult.Failure>(repository(server).refresh(RefreshRequestDto("pm_rt_replayed")))
            assertEquals(AuthFailure.HTTP, failure.reason)
            assertEquals(409, failure.httpStatus)
        }
    }

    @Test
    fun logoutSendsBearerAndAccepts204() = runTest {
        withServer { server ->
            server.enqueue(MockResponse.Builder().code(204).build())
            val result = repository(server).logout("pm_at_live")

            val success = assertIs<AuthResult.Success<Unit>>(result)
            assertEquals(204, success.httpStatus)

            val recorded = server.takeRequest()
            assertEquals("/v1/auth/logout", recorded.url.encodedPath)
            assertEquals("Bearer pm_at_live", recorded.headers["Authorization"])
            assertEquals(0L, recorded.bodySize)
        }
    }

    @Test
    fun logoutNon204IsHttpFailure() = runTest {
        withServer { server ->
            server.enqueue(jsonResponse("""{"code":"AUTH_EXPIRED"}""", code = 401))
            val failure = assertIs<AuthResult.Failure>(repository(server).logout("pm_at_stale"))
            assertEquals(AuthFailure.HTTP, failure.reason)
            assertEquals(401, failure.httpStatus)
        }
    }

    private fun assertTruePasswordPresent(sentBody: kotlinx.serialization.json.JsonObject) {
        val password = sentBody["password"] as? kotlinx.serialization.json.JsonPrimitive
        checkNotNull(password) { "password field missing" }
        check(password.content.isNotEmpty()) { "password must not be empty" }
    }
}
