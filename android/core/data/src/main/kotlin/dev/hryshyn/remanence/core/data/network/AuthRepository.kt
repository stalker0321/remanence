package dev.hryshyn.remanence.core.data.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
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
import okhttp3.ResponseBody
import okhttp3.coroutines.executeAsync
import okio.Buffer

enum class AuthFailure {
    NETWORK,
    HTTP,
    INVALID_RESPONSE,
}

/** Registration problem codes that are safe for the client to act on. */
enum class RegistrationProblemCode(val wireCode: String) {
    EMAIL_UNAVAILABLE("EMAIL_UNAVAILABLE"),
    HANDLE_UNAVAILABLE("HANDLE_UNAVAILABLE"),
    KEY_BUNDLE_INVALID("KEY_BUNDLE_INVALID"),
    ;

    companion object {
        fun fromWireCode(code: String): RegistrationProblemCode? =
            values().firstOrNull { it.wireCode == code }
    }
}

sealed interface AuthResult<out T> {
    data class Success<T>(
        val value: T,
        val httpStatus: Int,
    ) : AuthResult<T>

    data class Failure(
        val reason: AuthFailure,
        val httpStatus: Int? = null,
        val registrationProblemCode: RegistrationProblemCode? = null,
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
        allowedProblemCodes = REGISTRATION_PROBLEM_CODES,
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
        allowedProblemCodes: Set<RegistrationProblemCode> = emptySet(),
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
                interpret(response, successStatus, allowedProblemCodes, decode)
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
        allowedProblemCodes: Set<RegistrationProblemCode>,
        decode: (String) -> T,
    ): AuthResult<T> {
        if (response.code != successStatus) {
            return AuthResult.Failure(
                reason = AuthFailure.HTTP,
                httpStatus = response.code,
                registrationProblemCode = parseRegistrationProblem(response, allowedProblemCodes),
            )
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

    private fun parseRegistrationProblem(
        response: Response,
        allowedProblemCodes: Set<RegistrationProblemCode>,
    ): RegistrationProblemCode? {
        if (allowedProblemCodes.isEmpty()) return null
        val contentType = response.body.contentType()
        if (contentType?.type != "application" || contentType.subtype != "problem+json") {
            return null
        }
        val body = response.body.readBoundedUtf8() ?: return null
        val classified = classifyCapsuleProblem(
            text = body,
            httpStatus = response.code,
            allowedCodes = allowedProblemCodes.map { it.wireCode }.toSet(),
        ) ?: return null
        return RegistrationProblemCode.fromWireCode(classified.code)
    }

    private fun ResponseBody.readBoundedUtf8(): String? {
        if (contentLength() > MAX_BODY_BYTES) return null
        val source = source()
        val buffer = Buffer()
        var totalBytes = 0L
        while (true) {
            val read = source.read(buffer, MAX_BODY_BYTES + 1L - totalBytes)
            if (read == -1L) break
            totalBytes += read
            if (totalBytes > MAX_BODY_BYTES) return null
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(buffer.readByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    companion object {
        /**
         * Bare client repository: no bearer interceptor and no authenticator.
         * This is the ONLY shape allowed to carry `/v1/auth/refresh` so a
         * rejected refresh can never recurse through [RefreshingAuthenticator].
         */
        fun create(baseUrl: ApiBaseUrl): AuthRepository =
            AuthRepository(HttpClientFactory.create(), baseUrl)

        private val REGISTRATION_PROBLEM_CODES = setOf(
            RegistrationProblemCode.EMAIL_UNAVAILABLE,
            RegistrationProblemCode.HANDLE_UNAVAILABLE,
            RegistrationProblemCode.KEY_BUNDLE_INVALID,
        )
        private const val MAX_BODY_BYTES = 64 * 1024
    }
}
