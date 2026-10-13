package dev.hryshyn.remanence.core.data.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * T186 M5 prep: typed one-time handle-commit client — EXACT frozen T185
 * `api-contract.md` §8 R17 (routes still scaffold server-side: implemented
 * from the frozen contract, backend never claimed to work).
 *
 * Request: `{"handle": "chosen-handle"}` (valid normalized handle).
 * Response `200`: `{"user_id": "uuid", "handle": "chosen-handle"}`.
 * Identical replay succeeds; a different later value → `HANDLE_IMMUTABLE`.
 * UI wiring (first-send gate commit) lands on the mount-status handoff;
 * MockWebServer tests below verify the client meanwhile.
 */
enum class HandleCommitFailure {
    NETWORK,
    RATE_LIMITED,
    HTTP,
    INVALID_RESPONSE,
    AUTH_INVALID,
    VALIDATION_FAILED,
    HANDLE_IMMUTABLE,
    INTERNAL_ERROR,
}

sealed interface HandleCommitResult {
    data class Committed(
        val userId: String,
        val handle: String,
        val httpStatus: Int,
    ) : HandleCommitResult

    data class Failure(
        val reason: HandleCommitFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : HandleCommitResult
}

fun interface HandleCommitPort {
    suspend fun commit(handle: String, accessToken: String): HandleCommitResult
}

@Serializable
private data class HandleCommitRequestDto(val handle: String)

@Serializable
private data class HandleCommitResponseDto(
    @kotlinx.serialization.SerialName("user_id") val userId: String,
    val handle: String,
)

class PendingHandleCommitRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) : HandleCommitPort {

    override suspend fun commit(handle: String, accessToken: String): HandleCommitResult {
        if (accessToken.isBlank() || handle.isBlank()) {
            return HandleCommitResult.Failure(HandleCommitFailure.VALIDATION_FAILED, retryable = false)
        }
        val body = try {
            NetworkJson.encodeToString(HandleCommitRequestDto.serializer(), HandleCommitRequestDto(handle))
        } catch (_: Exception) {
            return HandleCommitResult.Failure(HandleCommitFailure.VALIDATION_FAILED, retryable = false)
        }
        val request = Request.Builder()
            .url(baseUrl.resolve("v2/me/handle"))
            .header("Accept", JSON_MEDIA_TYPE)
            .header("Authorization", BEARER_PREFIX + accessToken)
            .post(body.toRequestBody(JSON_REQUEST_TYPE))
            .build()
        return try {
            client.executeResponseWithCallLifetime(request) { response ->
                interpret(response, handle)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            HandleCommitResult.Failure(HandleCommitFailure.NETWORK, retryable = true)
        }
    }

    private suspend fun interpret(response: Response, requestedHandle: String): HandleCommitResult {
        val bytes = readBounded(response.body) ?: return HandleCommitResult.Failure(
            HandleCommitFailure.INVALID_RESPONSE,
            response.code,
            retryable = capsuleHttpFallbackIsRetryable(response.code),
        )
        if (response.code != HTTP_OK) {
            if (!isJson(response, PROBLEM_JSON_SUBTYPE)) {
                return HandleCommitResult.Failure(
                    HandleCommitFailure.INVALID_RESPONSE,
                    response.code,
                    retryable = capsuleHttpFallbackIsRetryable(response.code),
                )
            }
            val problem = classifyCapsuleProblem(
                bytes.toString(Charsets.UTF_8),
                response.code,
                COMMIT_PROBLEM_CODES,
            )
            return HandleCommitResult.Failure(
                reason = problem?.let { mapProblemCode(it.code) } ?: HandleCommitFailure.HTTP,
                httpStatus = response.code,
                retryable = problem?.retryable ?: capsuleHttpFallbackIsRetryable(response.code),
            )
        }
        if (!isJson(response, JSON_SUBTYPE)) {
            return HandleCommitResult.Failure(
                HandleCommitFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        val dto = try {
            NetworkJson.decodeFromString<HandleCommitResponseDto>(bytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return HandleCommitResult.Failure(
                HandleCommitFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        } catch (_: IllegalArgumentException) {
            return HandleCommitResult.Failure(
                HandleCommitFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        return try {
            require(dto.handle == requestedHandle)
            require(dto.userId.isNotBlank())
            HandleCommitResult.Committed(dto.userId, dto.handle, response.code)
        } catch (_: IllegalArgumentException) {
            HandleCommitResult.Failure(
                HandleCommitFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
    }

    private fun mapProblemCode(code: String): HandleCommitFailure = when (code) {
        "AUTH_INVALID" -> HandleCommitFailure.AUTH_INVALID
        "RATE_LIMITED" -> HandleCommitFailure.RATE_LIMITED
        "VALIDATION_FAILED" -> HandleCommitFailure.VALIDATION_FAILED
        "HANDLE_IMMUTABLE" -> HandleCommitFailure.HANDLE_IMMUTABLE
        "INTERNAL_ERROR" -> HandleCommitFailure.INTERNAL_ERROR
        else -> HandleCommitFailure.HTTP
    }

    private fun isJson(response: Response, expectedSubtype: String): Boolean {
        val contentType = response.body.contentType() ?: return false
        return contentType.type == "application" && contentType.subtype == expectedSubtype
    }

    private suspend fun readBounded(body: ResponseBody): ByteArray? {
        if (body.contentLength() > MAX_RESPONSE_BYTES) return null
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        body.byteStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                currentCoroutineContext().ensureActive()
                output.write(buffer, 0, read)
                currentCoroutineContext().ensureActive()
                if (output.size() > MAX_RESPONSE_BYTES) return null
            }
        }
        return output.toByteArray()
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
        private const val JSON_SUBTYPE = "json"
        private const val PROBLEM_JSON_SUBTYPE = "problem+json"
        private const val HTTP_OK = 200
        private const val MAX_RESPONSE_BYTES = 64 * 1024L

        private val JSON_REQUEST_TYPE = "application/json; charset=utf-8".toMediaType()

        private val COMMIT_PROBLEM_CODES = setOf(
            "AUTH_INVALID",
            "RATE_LIMITED",
            "VALIDATION_FAILED",
            "HANDLE_IMMUTABLE",
            "INTERNAL_ERROR",
        )

        /** Public factory for cross-module use (client stays internal). */
        fun create(baseUrl: ApiBaseUrl): PendingHandleCommitRepository =
            PendingHandleCommitRepository(HttpClientFactory.create(), baseUrl)
    }
}
