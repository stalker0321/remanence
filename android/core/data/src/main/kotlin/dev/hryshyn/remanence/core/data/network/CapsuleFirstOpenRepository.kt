package dev.hryshyn.remanence.core.data.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import dev.hryshyn.remanence.core.model.CapsuleId

enum class CapsuleFirstOpenFailure {
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

data class CapsuleFirstOpen(
    val capsuleId: CapsuleId,
    val firstOpenedAtEpochMs: Long,
    val isReplay: Boolean,
)

sealed interface CapsuleFirstOpenResult {
    data class Success(
        val open: CapsuleFirstOpen,
        val httpStatus: Int,
    ) : CapsuleFirstOpenResult

    data class Failure(
        val reason: CapsuleFirstOpenFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : CapsuleFirstOpenResult
}

/** Narrow authenticated port for the durable recipient first-open claim. */
fun interface CapsuleFirstOpenPort {
    suspend fun claim(
        capsuleId: CapsuleId,
        accessToken: String,
        expectedLease: SessionRequestLease?,
    ): CapsuleFirstOpenResult
}

suspend fun CapsuleFirstOpenPort.claim(
    capsuleId: CapsuleId,
    accessToken: String,
): CapsuleFirstOpenResult = claim(capsuleId, accessToken, null)

@Serializable
private data class CapsuleFirstOpenResponseDto(
    @SerialName("capsule_id") val capsuleId: String,
    val state: String,
    @SerialName("first_opened_at") val firstOpenedAt: String,
    @SerialName("is_replay") val isReplay: Boolean,
)

/** Authenticated recipient first-open claim over the shared lease-bound stack. */
class CapsuleFirstOpenRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
    private val requestLeaseProvider: SessionRequestLeaseProvider? = null,
) : CapsuleFirstOpenPort {

    override suspend fun claim(
        capsuleId: CapsuleId,
        accessToken: String,
        expectedLease: SessionRequestLease?,
    ): CapsuleFirstOpenResult {
        if (accessToken.isBlank()) {
            return CapsuleFirstOpenResult.Failure(
                CapsuleFirstOpenFailure.VALIDATION_FAILED,
                retryable = false,
            )
        }
        if (expectedLease != null && requestLeaseProvider == null) {
            return CapsuleFirstOpenResult.Failure(
                CapsuleFirstOpenFailure.VALIDATION_FAILED,
                retryable = false,
            )
        }
        val requestLease = requestLeaseProvider?.let { provider ->
            expectedLease ?: provider.capture()
        }
        if (requestLeaseProvider != null && requestLease == null) {
            return CapsuleFirstOpenResult.Failure(
                CapsuleFirstOpenFailure.NETWORK,
                retryable = true,
            )
        }
        if (requestLeaseProvider != null &&
            !requestLeaseProvider.isLive(requireNotNull(requestLease))
        ) {
            return CapsuleFirstOpenResult.Failure(
                CapsuleFirstOpenFailure.NETWORK,
                retryable = true,
            )
        }
        val requestBearer = if (requestLeaseProvider != null) {
            requestLeaseProvider.accessTokenFor(requireNotNull(requestLease))
                ?: return CapsuleFirstOpenResult.Failure(
                    CapsuleFirstOpenFailure.NETWORK,
                    retryable = true,
                )
        } else {
            accessToken
        }
        val request = Request.Builder()
            .url(baseUrl.resolve("v1/capsules/${capsuleId.toRestString()}/first-open"))
            .header("Accept", JSON_MEDIA_TYPE)
            .header("Authorization", BEARER_PREFIX + requestBearer)
            .post(ByteArray(0).toRequestBody(null))
            .build()
            .let { requestLeaseProvider?.tag(it, requestLease!!) ?: it }

        return try {
            val result = client.executeResponseWithCallLifetime(request) { response ->
                // Keep headers/body consumption off the caller (often Main)
                // and reject a response that crossed its live lease while
                // the bounded body was being read.
                if (requestLease != null &&
                    requestLeaseProvider != null &&
                    !requestLeaseProvider.isLive(requestLease)
                ) {
                    CapsuleFirstOpenResult.Failure(
                        CapsuleFirstOpenFailure.NETWORK,
                        retryable = true,
                    )
                } else {
                    interpret(response, capsuleId)
                }
            }
            if (requestLease != null &&
                requestLeaseProvider != null &&
                !requestLeaseProvider.isLive(requestLease)
            ) {
                CapsuleFirstOpenResult.Failure(
                    CapsuleFirstOpenFailure.NETWORK,
                    retryable = true,
                )
            } else {
                result
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            CapsuleFirstOpenResult.Failure(
                CapsuleFirstOpenFailure.NETWORK,
                retryable = true,
            )
        }
    }

    private suspend fun interpret(
        response: Response,
        requestedCapsuleId: CapsuleId,
    ): CapsuleFirstOpenResult {
        val bytes = readBounded(response.body) ?: return CapsuleFirstOpenResult.Failure(
            CapsuleFirstOpenFailure.INVALID_RESPONSE,
            response.code,
            retryable = capsuleHttpFallbackIsRetryable(response.code),
        )
        if (response.code != HTTP_OK) {
            if (!isJson(response, PROBLEM_JSON_SUBTYPE)) {
                return CapsuleFirstOpenResult.Failure(
                    CapsuleFirstOpenFailure.INVALID_RESPONSE,
                    response.code,
                    retryable = capsuleHttpFallbackIsRetryable(response.code),
                )
            }
            val problem = classifyCapsuleProblem(
                bytes.toString(Charsets.UTF_8),
                response.code,
                FIRST_OPEN_PROBLEM_CODES,
            )
            return CapsuleFirstOpenResult.Failure(
                reason = problem?.let { mapProblemCode(it.code) } ?: CapsuleFirstOpenFailure.HTTP,
                httpStatus = response.code,
                retryable = problem?.retryable ?: capsuleHttpFallbackIsRetryable(response.code),
            )
        }
        if (!isJson(response, JSON_SUBTYPE)) {
            return CapsuleFirstOpenResult.Failure(
                CapsuleFirstOpenFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        val dto = try {
            NetworkJson.decodeFromString<CapsuleFirstOpenResponseDto>(bytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return CapsuleFirstOpenResult.Failure(
                CapsuleFirstOpenFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        } catch (_: IllegalArgumentException) {
            return CapsuleFirstOpenResult.Failure(
                CapsuleFirstOpenFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        return try {
            require(dto.state == "OPENED")
            val capsuleId = CapsuleId.parseRest(dto.capsuleId)
            require(capsuleId == requestedCapsuleId)
            val firstOpenedAt = OffsetDateTime.parse(dto.firstOpenedAt)
            require(firstOpenedAt.offset == ZoneOffset.UTC)
            val epochMs = firstOpenedAt.toInstant().toEpochMilli()
            require(epochMs >= 0L)
            CapsuleFirstOpenResult.Success(
                CapsuleFirstOpen(
                    capsuleId = capsuleId,
                    firstOpenedAtEpochMs = epochMs,
                    isReplay = dto.isReplay,
                ),
                response.code,
            )
        } catch (_: IllegalArgumentException) {
            CapsuleFirstOpenResult.Failure(
                CapsuleFirstOpenFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        } catch (_: java.time.DateTimeException) {
            CapsuleFirstOpenResult.Failure(
                CapsuleFirstOpenFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        } catch (_: ArithmeticException) {
            CapsuleFirstOpenResult.Failure(
                CapsuleFirstOpenFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
    }

    private fun mapProblemCode(code: String): CapsuleFirstOpenFailure = when (code) {
        "AUTH_INVALID" -> CapsuleFirstOpenFailure.AUTH_INVALID
        "RATE_LIMITED" -> CapsuleFirstOpenFailure.RATE_LIMITED
        "VALIDATION_FAILED" -> CapsuleFirstOpenFailure.VALIDATION_FAILED
        "CAPSULE_NOT_FOUND" -> CapsuleFirstOpenFailure.CAPSULE_NOT_FOUND
        "CAPSULE_STATE_INVALID" -> CapsuleFirstOpenFailure.CAPSULE_STATE_INVALID
        "INTERNAL_ERROR" -> CapsuleFirstOpenFailure.INTERNAL_ERROR
        else -> CapsuleFirstOpenFailure.HTTP
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
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                output.write(buffer, 0, read)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
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

        private val FIRST_OPEN_PROBLEM_CODES = setOf(
            "AUTH_INVALID",
            "RATE_LIMITED",
            "VALIDATION_FAILED",
            "CAPSULE_NOT_FOUND",
            "CAPSULE_STATE_INVALID",
            "INTERNAL_ERROR",
        )

        internal fun create(baseUrl: ApiBaseUrl): CapsuleFirstOpenRepository =
            CapsuleFirstOpenRepository(HttpClientFactory.create(), baseUrl)
    }
}
