package dev.hryshyn.remanence.core.data.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.coroutines.executeAsync
import dev.hryshyn.remanence.core.model.CapsuleId

enum class CapsuleRevokeState {
    REVOKED,
}

data class CapsuleRevoke(
    val capsuleId: CapsuleId,
    val state: CapsuleRevokeState,
    val isReplay: Boolean,
)

enum class CapsuleRevokeFailure {
    NETWORK,
    RATE_LIMITED,
    HTTP,
    INVALID_RESPONSE,
    AUTH_INVALID,
    VALIDATION_FAILED,
    CAPSULE_NOT_FOUND,
    CAPSULE_STATE_INVALID,
    WINDOW_EXPIRED,
    INTERNAL_ERROR,
}

sealed interface CapsuleRevokeResult {
    data class Success(
        val revoke: CapsuleRevoke,
        val httpStatus: Int,
    ) : CapsuleRevokeResult

    data class Failure(
        val reason: CapsuleRevokeFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : CapsuleRevokeResult
}

/** Minimal client port so the Create flow can be tested without HTTP. */
fun interface CapsuleRevokePort {
    suspend fun revoke(capsuleId: CapsuleId, accessToken: String): CapsuleRevokeResult
}

@Serializable
private data class CapsuleRevokeResponseDto(
    @SerialName("capsule_id") val capsuleId: String,
    val state: String,
    @SerialName("is_replay") val isReplay: Boolean,
)

/** Authenticated sender revoke call for one published capsule. */
class CapsuleRevokeRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
    private val requestLeaseProvider: SessionRequestLeaseProvider? = null,
) : CapsuleRevokePort {

    override suspend fun revoke(
        capsuleId: CapsuleId,
        accessToken: String,
    ): CapsuleRevokeResult {
        val requestLease = requestLeaseProvider?.capture()
        if (requestLeaseProvider != null && requestLease == null) {
            return CapsuleRevokeResult.Failure(CapsuleRevokeFailure.NETWORK, retryable = true)
        }
        val httpRequest = Request.Builder()
            .url(baseUrl.resolve("v1/capsules/${capsuleId.toRestString()}/revoke"))
            .header("Accept", JSON_MEDIA_TYPE)
            .header("Authorization", BEARER_PREFIX + accessToken)
            .post(ByteArray(0).toRequestBody(null))
            .build()
            .let { requestLeaseProvider?.tag(it, requestLease!!) ?: it }

        return try {
            client.newCall(httpRequest).executeAsync().use { response ->
                interpret(response, capsuleId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            CapsuleRevokeResult.Failure(CapsuleRevokeFailure.NETWORK, retryable = true)
        }
    }

    private fun interpret(
        response: Response,
        requestedCapsuleId: CapsuleId,
    ): CapsuleRevokeResult {
        val bytes = readBounded(response.body)
            ?: return CapsuleRevokeResult.Failure(
                CapsuleRevokeFailure.INVALID_RESPONSE,
                response.code,
                retryable = capsuleHttpFallbackIsRetryable(response.code),
            )
        if (response.code != HTTP_OK) {
            if (!isJson(response, PROBLEM_JSON_SUBTYPE)) {
                return CapsuleRevokeResult.Failure(
                    CapsuleRevokeFailure.INVALID_RESPONSE,
                    response.code,
                    retryable = capsuleHttpFallbackIsRetryable(response.code),
                )
            }
            val problem = classifyCapsuleProblem(
                bytes.toString(Charsets.UTF_8),
                response.code,
                REVOKE_PROBLEM_CODES,
            )
            return CapsuleRevokeResult.Failure(
                reason = problem?.let { mapProblemCode(it.code) } ?: CapsuleRevokeFailure.HTTP,
                httpStatus = response.code,
                retryable = problem?.retryable ?: capsuleHttpFallbackIsRetryable(response.code),
            )
        }
        if (!isJson(response, JSON_SUBTYPE)) {
            return CapsuleRevokeResult.Failure(
                CapsuleRevokeFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        val dto = try {
            NetworkJson.decodeFromString<CapsuleRevokeResponseDto>(bytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return CapsuleRevokeResult.Failure(CapsuleRevokeFailure.INVALID_RESPONSE, response.code, retryable = false)
        } catch (_: IllegalArgumentException) {
            return CapsuleRevokeResult.Failure(CapsuleRevokeFailure.INVALID_RESPONSE, response.code, retryable = false)
        }
        return try {
            CapsuleRevokeResult.Success(mapResponse(dto, requestedCapsuleId), response.code)
        } catch (_: IllegalArgumentException) {
            CapsuleRevokeResult.Failure(CapsuleRevokeFailure.INVALID_RESPONSE, response.code, retryable = false)
        }
    }

    private fun mapResponse(
        dto: CapsuleRevokeResponseDto,
        requestedCapsuleId: CapsuleId,
    ): CapsuleRevoke {
        require(dto.state == CapsuleRevokeState.REVOKED.name)
        val capsuleId = CapsuleId.parseRest(dto.capsuleId)
        require(capsuleId == requestedCapsuleId)
        return CapsuleRevoke(capsuleId, CapsuleRevokeState.REVOKED, dto.isReplay)
    }

    private fun mapProblemCode(code: String): CapsuleRevokeFailure = when (code) {
        "AUTH_INVALID" -> CapsuleRevokeFailure.AUTH_INVALID
        "RATE_LIMITED" -> CapsuleRevokeFailure.RATE_LIMITED
        "VALIDATION_FAILED" -> CapsuleRevokeFailure.VALIDATION_FAILED
        "CAPSULE_NOT_FOUND" -> CapsuleRevokeFailure.CAPSULE_NOT_FOUND
        "CAPSULE_STATE_INVALID" -> CapsuleRevokeFailure.CAPSULE_STATE_INVALID
        "WINDOW_EXPIRED" -> CapsuleRevokeFailure.WINDOW_EXPIRED
        "INTERNAL_ERROR" -> CapsuleRevokeFailure.INTERNAL_ERROR
        else -> CapsuleRevokeFailure.HTTP
    }

    private fun isJson(response: Response, expectedSubtype: String): Boolean {
        val contentType = response.body.contentType() ?: return false
        return contentType.type == "application" && contentType.subtype == expectedSubtype
    }

    private fun readBounded(body: ResponseBody): ByteArray? {
        if (body.contentLength() > MAX_RESPONSE_BYTES) return null
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        body.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
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

        private val REVOKE_PROBLEM_CODES = setOf(
            "AUTH_INVALID",
            "RATE_LIMITED",
            "VALIDATION_FAILED",
            "CAPSULE_NOT_FOUND",
            "CAPSULE_STATE_INVALID",
            "WINDOW_EXPIRED",
            "INTERNAL_ERROR",
        )

        internal fun create(baseUrl: ApiBaseUrl): CapsuleRevokeRepository =
            CapsuleRevokeRepository(HttpClientFactory.create(), baseUrl)
    }
}
