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

/**
 * T186 M8: typed send-route freeze client — EXACT frozen T185
 * `api-contract.md` §8 R1 (routes scaffold server-side: implemented from
 * the frozen contract, backend never claimed to work).
 *
 * Request: `{"capsule_id", "destination": {"kind": "email"|"phone"|"handle",
 * "value": raw-input}, "idempotency_key"}`. The value travels RAW; the
 * server normalizes authoritatively (§6 locator rules do not apply).
 * Response `200`, exactly one frozen shape (never both):
 * - `{"route_id", "mode": "EXISTING_USER_V1", "user_id",
 *   "key_bundle_id", "binding_revision", "expires_at"}` — verified binding:
 *   existing pipeline, zero escrow. A stale bundle is an error here, never
 *   a silent downgrade to escrow.
 * - `{"route_id", "mode": "PENDING_V2", "delivery_target_id",
 *   "normalized_destination", "channel", "claim_deadline",
 *   "service_key_id", "expires_at"}`.
 * Errors: `ADDRESS_UNAVAILABLE` (never silent escrow),
 * `HANDLE_REQUIRED_FOR_SEND`, `RATE_LIMITED`. No fallback between modes
 * after freeze (client enforces via `PendingSenderRoute`).
 */
enum class SendRouteFailure {
    NETWORK,
    RATE_LIMITED,
    HTTP,
    INVALID_RESPONSE,
    AUTH_INVALID,
    VALIDATION_FAILED,
    ADDRESS_UNAVAILABLE,
    HANDLE_REQUIRED_FOR_SEND,
    INTERNAL_ERROR,
}

enum class SendRouteMode { EXISTING_USER_V1, PENDING_V2 }

sealed interface SendRouteResult {
    data class FrozenV1(
        val routeId: String,
        val userId: String,
        val keyBundleId: String,
        /**
         * Authoritative binding revision for verified email/phone (always
         * positive). Null for handle-routed decisions, which carry no
         * revision — never faked to 0.
         */
        val bindingRevision: Long?,
        val expiresAt: Long,
    ) : SendRouteResult

    data class FrozenPending(
        val routeId: String,
        val deliveryTargetId: String,
        val normalizedDestination: String,
        val channel: String,
        val claimDeadline: Long,
        val serviceKeyId: String,
        val expiresAt: Long,
    ) : SendRouteResult

    data class Failure(
        val reason: SendRouteFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : SendRouteResult
}

@Serializable
private data class SendRouteDestinationDto(val kind: String, val value: String)

@Serializable
private data class SendRouteRequestDto(
    @SerialName("capsule_id") val capsuleId: String,
    val destination: SendRouteDestinationDto,
    @SerialName("idempotency_key") val idempotencyKey: String,
)

@Serializable
private data class SendRouteResponseDto(
    @SerialName("route_id") val routeId: String = "",
    val mode: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("key_bundle_id") val keyBundleId: String = "",
    /**
     * Null for handle-routed v1 decisions (no revision issued); positive
     * authoritative revision for verified email/phone. Never defaulted.
     */
    @SerialName("binding_revision") val bindingRevision: Long? = null,
    @SerialName("delivery_target_id") val deliveryTargetId: String = "",
    @SerialName("normalized_destination") val normalizedDestination: String = "",
    val channel: String = "",
    @SerialName("claim_deadline") val claimDeadline: Long = -1,
    @SerialName("service_key_id") val serviceKeyId: String = "",
    @SerialName("expires_at") val expiresAt: Long = -1,
)

class PendingSendRouteRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {
    suspend fun freeze(
        capsuleId: String,
        kind: String,
        rawValue: String,
        idempotencyKey: String,
        accessToken: String,
    ): SendRouteResult {
        if (accessToken.isBlank() || capsuleId.isBlank() || rawValue.isBlank() ||
            idempotencyKey.isBlank() || (kind != "email" && kind != "phone" && kind != "handle")
        ) {
            return SendRouteResult.Failure(SendRouteFailure.VALIDATION_FAILED, retryable = false)
        }
        val body = try {
            NetworkJson.encodeToString(
                SendRouteRequestDto.serializer(),
                SendRouteRequestDto(capsuleId, SendRouteDestinationDto(kind, rawValue), idempotencyKey),
            )
        } catch (_: Exception) {
            return SendRouteResult.Failure(SendRouteFailure.VALIDATION_FAILED, retryable = false)
        }
        val request = Request.Builder()
            .url(baseUrl.resolve("v2/send-routes"))
            .header("Accept", "application/json; charset=utf-8")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $accessToken")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            client.executeResponseWithCallLifetime(request) { response ->
                interpret(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            SendRouteResult.Failure(SendRouteFailure.NETWORK, retryable = true)
        }
    }

    private suspend fun interpret(response: Response): SendRouteResult {
        val bytes = readSendBounded(response.body) ?: return SendRouteResult.Failure(
            SendRouteFailure.INVALID_RESPONSE,
            response.code,
            retryable = capsuleHttpFallbackIsRetryable(response.code),
        )
        if (response.code != 200) {
            if (!isSendJson(response, "problem+json")) {
                return SendRouteResult.Failure(
                    SendRouteFailure.INVALID_RESPONSE,
                    response.code,
                    retryable = capsuleHttpFallbackIsRetryable(response.code),
                )
            }
            val problem = classifyCapsuleProblem(
                bytes.toString(Charsets.UTF_8),
                response.code,
                SEND_ROUTE_PROBLEM_CODES,
            )
            val reason = problem?.let { mapSendProblem(it.code) } ?: SendRouteFailure.HTTP
            val retryable = (problem?.retryable ?: capsuleHttpFallbackIsRetryable(response.code)) ||
                reason == SendRouteFailure.AUTH_INVALID
            return SendRouteResult.Failure(reason, response.code, retryable)
        }
        if (!isSendJson(response, "json")) {
            return SendRouteResult.Failure(
                SendRouteFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        val dto = try {
            NetworkJson.decodeFromString<SendRouteResponseDto>(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return SendRouteResult.Failure(
                SendRouteFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        return try {
            require(dto.routeId.isNotBlank() && dto.expiresAt >= 0)
            when (dto.mode) {
                "EXISTING_USER_V1" -> {
                    require(dto.userId.isNotBlank() && dto.keyBundleId.isNotBlank())
                    // Handle-routed decisions carry no revision (null);
                    // verified email/phone carry a positive authoritative
                    // one. Negative values are never valid.
                    dto.bindingRevision?.let { require(it >= 0) }
                    require(dto.deliveryTargetId.isBlank())
                    SendRouteResult.FrozenV1(
                        dto.routeId, dto.userId, dto.keyBundleId, dto.bindingRevision, dto.expiresAt,
                    )
                }
                "PENDING_V2" -> {
                    require(dto.deliveryTargetId.isNotBlank() && dto.normalizedDestination.isNotBlank() &&
                        dto.channel.isNotBlank() && dto.claimDeadline >= 0 && dto.serviceKeyId.isNotBlank())
                    require(dto.userId.isBlank())
                    SendRouteResult.FrozenPending(
                        dto.routeId, dto.deliveryTargetId, dto.normalizedDestination,
                        dto.channel, dto.claimDeadline, dto.serviceKeyId, dto.expiresAt,
                    )
                }
                else -> throw IllegalArgumentException("unknown mode")
            }
        } catch (_: IllegalArgumentException) {
            SendRouteResult.Failure(
                SendRouteFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
    }

    private fun mapSendProblem(code: String): SendRouteFailure = when (code) {
        "AUTH_INVALID" -> SendRouteFailure.AUTH_INVALID
        "RATE_LIMITED" -> SendRouteFailure.RATE_LIMITED
        "VALIDATION_FAILED" -> SendRouteFailure.VALIDATION_FAILED
        "ADDRESS_UNAVAILABLE" -> SendRouteFailure.ADDRESS_UNAVAILABLE
        "HANDLE_REQUIRED_FOR_SEND" -> SendRouteFailure.HANDLE_REQUIRED_FOR_SEND
        "INTERNAL_ERROR" -> SendRouteFailure.INTERNAL_ERROR
        else -> SendRouteFailure.HTTP
    }

    private fun isSendJson(response: Response, expectedSubtype: String): Boolean {
        val contentType = response.body.contentType() ?: return false
        return contentType.type == "application" && contentType.subtype == expectedSubtype
    }

    private suspend fun readSendBounded(body: ResponseBody): ByteArray? {
        if (body.contentLength() > 64 * 1024L) return null
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
                if (output.size() > 64 * 1024L) return null
            }
        }
        return output.toByteArray()
    }

    companion object {
        private val SEND_ROUTE_PROBLEM_CODES = setOf(
            "AUTH_INVALID",
            "RATE_LIMITED",
            "VALIDATION_FAILED",
            "ADDRESS_UNAVAILABLE",
            "HANDLE_REQUIRED_FOR_SEND",
            "INTERNAL_ERROR",
        )

        /** Public factory for cross-module use (client stays internal). */
        fun create(baseUrl: ApiBaseUrl): PendingSendRouteRepository =
            PendingSendRouteRepository(HttpClientFactory.create(), baseUrl)
    }
}
