package dev.hryshyn.remanence.core.data.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import dev.hryshyn.remanence.core.model.CapsuleId

/**
 * T186 M4: typed locator-open completion client — EXACT frozen T185
 * `api-contract.md` §8 R15 (routes still scaffold server-side: this client
 * is implemented from the frozen contract, but scaffold means the backend
 * is not claimed to work).
 *
 * Request: `{"publication_sha256_hex": "64-hex", "operation_id": "uuid"}`.
 * Both fields mandatory; the hash must be 64 lowercase hex. The caller
 * derives it from exact published bytes of trusted local material (H =
 * `SHA256(S)` for v2; see `PendingPublishVerifier.statementHash`).
 * Response `200`: `{"capsule_id": "uuid", "completion": true,
 * "locator_state": "RETIRED_OPEN"}` — all three required exact.
 * Replay semantics (same hash regardless of operation id; changed hash →
 * conflict) are server-owned. Failures map to retryable/terminal with the
 * shared capsule problem classifier; backend failure never affects content
 * display — the outbox retains and retries.
 */
enum class LocatorRetireFailure {
    NETWORK,
    RATE_LIMITED,
    HTTP,
    INVALID_RESPONSE,
    AUTH_INVALID,
    VALIDATION_FAILED,
    CAPSULE_NOT_FOUND,
    CAPSULE_STATE_INVALID,
    INTERNAL_ERROR,
}

sealed interface LocatorRetireResult {
    data class Completed(
        val capsuleId: CapsuleId,
        val httpStatus: Int,
    ) : LocatorRetireResult

    data class Failure(
        val reason: LocatorRetireFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : LocatorRetireResult
}

/** Narrow authenticated port for the durable locator-open completion. */
fun interface LocatorRetirePort {
    suspend fun complete(
        capsuleId: CapsuleId,
        publicationSha256Hex: String,
        operationId: String,
        accessToken: String,
    ): LocatorRetireResult
}

@Serializable
private data class LocatorRetireRequestDto(
    @SerialName("publication_sha256_hex") val publicationSha256Hex: String,
    @SerialName("operation_id") val operationId: String,
)

@Serializable
private data class LocatorRetireResponseDto(
    @SerialName("capsule_id") val capsuleId: String,
    val completion: Boolean,
    @SerialName("locator_state") val locatorState: String,
)

/** Authenticated locator-open completion over plain bearer (lease via caller). */
class PendingLocatorRetireRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) : LocatorRetirePort {

    override suspend fun complete(
        capsuleId: CapsuleId,
        publicationSha256Hex: String,
        operationId: String,
        accessToken: String,
    ): LocatorRetireResult {
        if (accessToken.isBlank() || operationId.isBlank() || !isHex64(publicationSha256Hex)) {
            return LocatorRetireResult.Failure(
                LocatorRetireFailure.VALIDATION_FAILED,
                retryable = false,
            )
        }
        val body = try {
            NetworkJson.encodeToString(
                LocatorRetireRequestDto.serializer(),
                LocatorRetireRequestDto(
                    publicationSha256Hex = publicationSha256Hex.lowercase(),
                    operationId = operationId,
                ),
            )
        } catch (_: Exception) {
            return LocatorRetireResult.Failure(
                LocatorRetireFailure.VALIDATION_FAILED,
                retryable = false,
            )
        }
        val request = Request.Builder()
            .url(baseUrl.resolve("v2/capsules/${capsuleId.toRestString()}/locator-open-completion"))
            .header("Accept", JSON_MEDIA_TYPE)
            .header("Authorization", BEARER_PREFIX + accessToken)
            .post(body.toRequestBody(JSON_REQUEST_TYPE))
            .build()
        return try {
            client.executeResponseWithCallLifetime(request) { response ->
                interpret(response, capsuleId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            LocatorRetireResult.Failure(LocatorRetireFailure.NETWORK, retryable = true)
        }
    }

    private fun isHex64(raw: String): Boolean =
        raw.length == 64 && raw.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private suspend fun interpret(
        response: Response,
        requestedCapsuleId: CapsuleId,
    ): LocatorRetireResult {
        val bytes = readBounded(response.body) ?: return LocatorRetireResult.Failure(
            LocatorRetireFailure.INVALID_RESPONSE,
            response.code,
            retryable = capsuleHttpFallbackIsRetryable(response.code),
        )
        if (response.code != HTTP_OK) {
            if (!isJson(response, PROBLEM_JSON_SUBTYPE)) {
                return LocatorRetireResult.Failure(
                    LocatorRetireFailure.INVALID_RESPONSE,
                    response.code,
                    retryable = capsuleHttpFallbackIsRetryable(response.code),
                )
            }
            val problem = classifyCapsuleProblem(
                bytes.toString(Charsets.UTF_8),
                response.code,
                RETIRE_PROBLEM_CODES,
            )
            return LocatorRetireResult.Failure(
                reason = problem?.let { mapProblemCode(it.code) } ?: LocatorRetireFailure.HTTP,
                httpStatus = response.code,
                retryable = problem?.retryable ?: capsuleHttpFallbackIsRetryable(response.code),
            )
        }
        if (!isJson(response, JSON_SUBTYPE)) {
            return LocatorRetireResult.Failure(
                LocatorRetireFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        val dto = try {
            NetworkJson.decodeFromString<LocatorRetireResponseDto>(bytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return LocatorRetireResult.Failure(
                LocatorRetireFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        } catch (_: IllegalArgumentException) {
            return LocatorRetireResult.Failure(
                LocatorRetireFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        return try {
            require(dto.completion)
            require(dto.locatorState == RETIRED_OPEN_STATE)
            val capsuleId = CapsuleId.parseRest(dto.capsuleId)
            require(capsuleId == requestedCapsuleId)
            LocatorRetireResult.Completed(capsuleId, response.code)
        } catch (_: IllegalArgumentException) {
            LocatorRetireResult.Failure(
                LocatorRetireFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
    }

    private fun mapProblemCode(code: String): LocatorRetireFailure = when (code) {
        "AUTH_INVALID" -> LocatorRetireFailure.AUTH_INVALID
        "RATE_LIMITED" -> LocatorRetireFailure.RATE_LIMITED
        "VALIDATION_FAILED" -> LocatorRetireFailure.VALIDATION_FAILED
        "CAPSULE_NOT_FOUND" -> LocatorRetireFailure.CAPSULE_NOT_FOUND
        "CAPSULE_STATE_INVALID" -> LocatorRetireFailure.CAPSULE_STATE_INVALID
        "INTERNAL_ERROR" -> LocatorRetireFailure.INTERNAL_ERROR
        else -> LocatorRetireFailure.HTTP
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
        private const val RETIRED_OPEN_STATE = "RETIRED_OPEN"

        private val JSON_REQUEST_TYPE = "application/json; charset=utf-8".toMediaType()

        private val RETIRE_PROBLEM_CODES = setOf(
            "AUTH_INVALID",
            "RATE_LIMITED",
            "VALIDATION_FAILED",
            "CAPSULE_NOT_FOUND",
            "CAPSULE_STATE_INVALID",
            "INTERNAL_ERROR",
        )

        /** Public factory for cross-module use (client stays internal). */
        fun create(baseUrl: ApiBaseUrl): PendingLocatorRetireRepository =
            PendingLocatorRetireRepository(HttpClientFactory.create(), baseUrl)
    }
}
