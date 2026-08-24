package postmark.core.data.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.coroutines.executeAsync

enum class AuthFailure {
    NETWORK,
    HTTP,
    INVALID_RESPONSE,
}

sealed interface AuthResult<out T> {
    data class Success<T>(
        val value: T,
        val httpStatus: Int,
    ) : AuthResult<T>

    data class Failure(
        val reason: AuthFailure,
        val httpStatus: Int? = null,
    ) : AuthResult<Nothing>
}

/**
 * Typed client for the authentication endpoints (protocol.md section 5).
 * One method per endpoint; request bodies are serialized with the strict
 * shared [NetworkJson] instance and never logged.
 */
class AuthRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {
    suspend fun register(request: RegisterRequestDto): AuthResult<RegisterResponseDto> = postJson(
        path = "v1/auth/register",
        body = NetworkJson.encodeToString(request),
        successStatus = 201,
        decode = { text -> NetworkJson.decodeFromString<RegisterResponseDto>(text) },
    )

    suspend fun login(request: LoginRequestDto): AuthResult<LoginResponseDto> = postJson(
        path = "v1/auth/login",
        body = NetworkJson.encodeToString(request),
        successStatus = 200,
        decode = { text -> NetworkJson.decodeFromString<LoginResponseDto>(text) },
    )

    suspend fun refresh(request: RefreshRequestDto): AuthResult<RefreshResponseDto> = postJson(
        path = "v1/auth/refresh",
        body = NetworkJson.encodeToString(request),
        successStatus = 200,
        decode = { text -> NetworkJson.decodeFromString<RefreshResponseDto>(text) },
    )

    /** Revokes the authenticated session; the server is idempotent and answers 204. */
    suspend fun logout(accessToken: String): AuthResult<Unit> {
        val request = Request.Builder()
            .url(baseUrl.resolve("v1/auth/logout"))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .post(ByteArray(0).toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        return try {
            client.newCall(request).executeAsync().use { response ->
                if (response.code == 204) {
                    AuthResult.Success(Unit, 204)
                } else {
                    AuthResult.Failure(AuthFailure.HTTP, response.code)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            AuthResult.Failure(AuthFailure.NETWORK)
        }
    }

    private suspend fun <T> postJson(
        path: String,
        body: String,
        successStatus: Int,
        decode: (String) -> T,
    ): AuthResult<T> {
        val requestBody = body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(baseUrl.resolve(path))
            .header("Accept", "application/json")
            .post(requestBody)
            .build()
        return try {
            client.newCall(request).executeAsync().use { response ->
                interpret(response, successStatus, decode)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            AuthResult.Failure(AuthFailure.NETWORK)
        }
    }

    private fun <T> interpret(
        response: Response,
        successStatus: Int,
        decode: (String) -> T,
    ): AuthResult<T> {
        if (response.code != successStatus) {
            return AuthResult.Failure(AuthFailure.HTTP, response.code)
        }
        val contentType = response.body.contentType()
        if (contentType == null || contentType.type != "application" || contentType.subtype != "json") {
            return AuthResult.Failure(AuthFailure.INVALID_RESPONSE)
        }
        val bytes = response.body.bytes()
        if (bytes.size > MAX_BODY_BYTES) {
            return AuthResult.Failure(AuthFailure.INVALID_RESPONSE)
        }
        val dto = try {
            decode(bytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return AuthResult.Failure(AuthFailure.INVALID_RESPONSE)
        } catch (_: IllegalArgumentException) {
            return AuthResult.Failure(AuthFailure.INVALID_RESPONSE)
        }
        return AuthResult.Success(dto, response.code)
    }

    companion object {
        /**
         * Bare client repository: no bearer interceptor and no authenticator.
         * This is the ONLY shape allowed to carry `/v1/auth/refresh` so a
         * rejected refresh can never recurse through [RefreshingAuthenticator].
         */
        fun create(baseUrl: ApiBaseUrl): AuthRepository =
            AuthRepository(HttpClientFactory.create(), baseUrl)

        private const val MAX_BODY_BYTES = 64 * 1024
    }
}
