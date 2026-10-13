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
 * T186 M6: typed gift-lookup + verification clients — EXACT frozen T185
 * `api-contract.md` §8 shapes (routes scaffold server-side: implemented
 * from the frozen contract, backend never claimed to work).
 *
 * - R6 `POST v2/gift-lookups` (ANONYMOUS, budgeted): `{"code": "..."}`
 *   (any §6 normalization accepted — the server normalizes authoritatively;
 *   the client sends the trimmed input and never invents canonical bytes).
 *   `200` while ACTIVE: `{"gift_reference": "uuid", "channel": "...",
 *   "hint": "a•••@•••"}` (identical shape for v1/pending/claimed).
 *   Unknown/retired/terminal → generic `GIFT_UNAVAILABLE` (no existence
 *   oracle beyond the server's minimal hint). `Cache-Control: no-store`,
 *   no positive edge cache.
 * - R8 `POST v2/pending-deliveries/{id}/verification`: `{"confirm_-
 *   destination": "...", "consent": {"sms_text_version": "v1",
 *   "accepted_at": N}?, "idempotency_key": "uuid"}` (`consent` required
 *   for phone, omitted for email). `200`: `{"attempt_id": "uuid",
 *   "expires_at": N, "resend_cooldown_seconds": N}` — or, for eligible
 *   fresh Google proof, `{"attempt_id": "uuid", "state": "VERIFIED"}`
 *   without dispatch. The client value is compared server-side, never
 *   trusted for dispatch.
 * - R9 `POST .../verification/{attempt}/check`: `{"code": "..."}` →
 *   `{"attempt_id": "uuid", "state": "VERIFIED", "expires_at": ...}` or
 *   generic rejection. No session/key issuance.
 *
 * R7 has no frozen response DTO in the contract yet (behavior row only):
 * NO R7 client is implemented here — inventing its shape would break
 * interop. Recorded for handoff like the R10 amendment.
 */
enum class GiftLookupFailure {
    NETWORK,
    RATE_LIMITED,
    HTTP,
    INVALID_RESPONSE,
    VALIDATION_FAILED,
    GIFT_UNAVAILABLE,
    AUTH_INVALID,
    CAPSULE_NOT_FOUND,
    CAPSULE_STATE_INVALID,
    ADDRESS_PROOF_REQUIRED,
    PROOF_EXPIRED,
    SESSION_CHANGED,
    RECOVERY_REQUIRED,
    RECIPIENT_KEY_STALE,
    PROVIDER_UNAVAILABLE,
    INTERNAL_ERROR,
}

sealed interface GiftLookupResult {
    data class Found(
        val giftReference: String,
        val channel: String,
        val hint: String,
    ) : GiftLookupResult

    data class Failure(
        val reason: GiftLookupFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : GiftLookupResult
}

sealed interface VerificationResult {
    data class Attempt(
        val attemptId: String,
        val expiresAt: Long?,
        val resendCooldownSeconds: Long?,
    ) : VerificationResult

    data class Verified(val attemptId: String) : VerificationResult

    data class Failure(
        val reason: GiftLookupFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : VerificationResult
}

@Serializable
private data class GiftLookupRequestDto(val code: String)

@Serializable
private data class GiftLookupResponseDto(
    @kotlinx.serialization.SerialName("gift_reference") val giftReference: String,
    val channel: String,
    val hint: String,
)

@Serializable
private data class VerificationConsentDto(
    @kotlinx.serialization.SerialName("sms_text_version") val smsTextVersion: String,
    @kotlinx.serialization.SerialName("accepted_at") val acceptedAt: Long,
)

@Serializable
private data class VerificationRequestDto(
    @kotlinx.serialization.SerialName("confirm_destination") val confirmDestination: String,
    val consent: VerificationConsentDto? = null,
    @kotlinx.serialization.SerialName("idempotency_key") val idempotencyKey: String,
)

@Serializable
private data class VerificationResponseDto(
    @kotlinx.serialization.SerialName("attempt_id") val attemptId: String,
    @kotlinx.serialization.SerialName("expires_at") val expiresAt: Long? = null,
    @kotlinx.serialization.SerialName("resend_cooldown_seconds") val resendCooldownSeconds: Long? = null,
    val state: String? = null,
)

@Serializable
private data class VerificationCheckRequestDto(val code: String)

/**
 * Tolerant problem shape for pending codes whose exact HTTP bindings are
 * still landing server-side (T185 implementing HTTP now). The CODE set is
 * frozen (§8/error list); only the status binding is tolerated. Strict
 * canonical tuples still win when they match; unknown codes stay generic.
 */
@Serializable
private data class TolerantLookupProblemDto(
    val code: String = "",
    val status: Int = 0,
    val retryable: Boolean = false,
)

private val tolerantLookupJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/**
 * R8 request encoder: `explicitNulls = false` so `consent` is OMitted for
 * email (frozen §8) instead of sent as explicit null. Decoding stays on
 * the strict shared [NetworkJson].
 */
private val verificationRequestJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = false
    isLenient = false
    explicitNulls = false
    encodeDefaults = true
}

internal fun lookupProblemOutcome(text: String, httpStatus: Int): Pair<GiftLookupFailure, Boolean> {
    val strict = classifyCapsuleProblem(text, httpStatus, LOOKUP_PROBLEM_CODES)
    if (strict != null) {
        val reason = mapLookupProblem(strict.code)
        // AUTH_INVALID retained retryable (never dropped): the bearer was
        // just leased live; see R15. Overrides the retry bit only.
        return reason to (strict.retryable || reason == GiftLookupFailure.AUTH_INVALID)
    }
    val tolerant = try {
        tolerantLookupJson.decodeFromString<TolerantLookupProblemDto>(text)
    } catch (_: Exception) {
        null
    }
    if (tolerant != null && tolerant.code in LOOKUP_PROBLEM_CODES) {
        val reason = mapLookupProblem(tolerant.code)
        return reason to (
            capsuleHttpFallbackIsRetryable(httpStatus) || reason == GiftLookupFailure.AUTH_INVALID
            )
    }
    return GiftLookupFailure.HTTP to capsuleHttpFallbackIsRetryable(httpStatus)
}

private const val GIFT_LOOKUP_MEDIA = "application/json; charset=utf-8"
private const val GIFT_LOOKUP_SUBTYPE = "json"
private const val GIFT_LOOKUP_PROBLEM_SUBTYPE = "problem+json"
private const val GIFT_LOOKUP_OK = 200
private const val GIFT_LOOKUP_MAX_BYTES = 64 * 1024L
private val GIFT_LOOKUP_REQUEST_TYPE = GIFT_LOOKUP_MEDIA.toMediaType()

private val LOOKUP_PROBLEM_CODES = setOf(
    "GIFT_UNAVAILABLE",
    "RATE_LIMITED",
    "VALIDATION_FAILED",
    "AUTH_INVALID",
    "CAPSULE_NOT_FOUND",
    "CAPSULE_STATE_INVALID",
    "ADDRESS_PROOF_REQUIRED",
    "PROOF_EXPIRED",
    "SESSION_CHANGED",
    "RECOVERY_REQUIRED",
    "RECIPIENT_KEY_STALE",
    "PROVIDER_UNAVAILABLE",
    "INTERNAL_ERROR",
)

private fun mapLookupProblem(code: String): GiftLookupFailure = when (code) {    "GIFT_UNAVAILABLE" -> GiftLookupFailure.GIFT_UNAVAILABLE
    "RATE_LIMITED" -> GiftLookupFailure.RATE_LIMITED
    "VALIDATION_FAILED" -> GiftLookupFailure.VALIDATION_FAILED
    "AUTH_INVALID" -> GiftLookupFailure.AUTH_INVALID
    "CAPSULE_NOT_FOUND" -> GiftLookupFailure.CAPSULE_NOT_FOUND
    "CAPSULE_STATE_INVALID" -> GiftLookupFailure.CAPSULE_STATE_INVALID
    "ADDRESS_PROOF_REQUIRED" -> GiftLookupFailure.ADDRESS_PROOF_REQUIRED
    "PROOF_EXPIRED" -> GiftLookupFailure.PROOF_EXPIRED
    "SESSION_CHANGED" -> GiftLookupFailure.SESSION_CHANGED
    "RECOVERY_REQUIRED" -> GiftLookupFailure.RECOVERY_REQUIRED
    "RECIPIENT_KEY_STALE" -> GiftLookupFailure.RECIPIENT_KEY_STALE
    "PROVIDER_UNAVAILABLE" -> GiftLookupFailure.PROVIDER_UNAVAILABLE
    "INTERNAL_ERROR" -> GiftLookupFailure.INTERNAL_ERROR
    else -> GiftLookupFailure.HTTP
}

private fun isLookupJson(response: Response, expectedSubtype: String): Boolean {
    val contentType = response.body.contentType() ?: return false
    return contentType.type == "application" && contentType.subtype == expectedSubtype
}

private suspend fun readLookupBounded(body: ResponseBody): ByteArray? {
    if (body.contentLength() > GIFT_LOOKUP_MAX_BYTES) return null
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
            if (output.size() > GIFT_LOOKUP_MAX_BYTES) return null
        }
    }
    return output.toByteArray()
}

/** Anonymous budgeted gift lookup (R6): no bearer by contract. */
class PendingGiftLookupRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {
    suspend fun lookup(code: String): GiftLookupResult {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) {
            return GiftLookupResult.Failure(GiftLookupFailure.VALIDATION_FAILED, retryable = false)
        }
        val body = try {
            NetworkJson.encodeToString(GiftLookupRequestDto.serializer(), GiftLookupRequestDto(trimmed))
        } catch (_: Exception) {
            return GiftLookupResult.Failure(GiftLookupFailure.VALIDATION_FAILED, retryable = false)
        }
        val request = Request.Builder()
            .url(baseUrl.resolve("v2/gift-lookups"))
            .header("Accept", GIFT_LOOKUP_MEDIA)
            .header("Cache-Control", "no-store")
            .post(body.toRequestBody(GIFT_LOOKUP_REQUEST_TYPE))
            .build()
        return try {
            client.executeResponseWithCallLifetime(request) { response ->
                interpretLookup(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            GiftLookupResult.Failure(GiftLookupFailure.NETWORK, retryable = true)
        }
    }

    private suspend fun interpretLookup(response: Response): GiftLookupResult {
        val bytes = readLookupBounded(response.body) ?: return GiftLookupResult.Failure(
            GiftLookupFailure.INVALID_RESPONSE,
            response.code,
            retryable = capsuleHttpFallbackIsRetryable(response.code),
        )
        if (response.code != GIFT_LOOKUP_OK) {
            if (!isLookupJson(response, GIFT_LOOKUP_PROBLEM_SUBTYPE)) {
                return GiftLookupResult.Failure(
                    GiftLookupFailure.INVALID_RESPONSE,
                    response.code,
                    retryable = capsuleHttpFallbackIsRetryable(response.code),
                )
            }
            val (reason, retryable) = lookupProblemOutcome(bytes.toString(Charsets.UTF_8), response.code)
            // Unlisted codes stay generic-unavailable-shaped: never an
            // existence oracle here.
            return GiftLookupResult.Failure(
                reason = if (reason == GiftLookupFailure.HTTP && response.code == 404) {
                    GiftLookupFailure.GIFT_UNAVAILABLE
                } else {
                    reason
                },
                httpStatus = response.code,
                retryable = retryable,
            )
        }
        if (!isLookupJson(response, GIFT_LOOKUP_SUBTYPE)) {
            return GiftLookupResult.Failure(
                GiftLookupFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        val dto = try {
            NetworkJson.decodeFromString<GiftLookupResponseDto>(bytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return GiftLookupResult.Failure(
                GiftLookupFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        } catch (_: IllegalArgumentException) {
            return GiftLookupResult.Failure(
                GiftLookupFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        return try {
            require(dto.giftReference.isNotBlank() && dto.channel.isNotBlank())
            GiftLookupResult.Found(dto.giftReference, dto.channel, dto.hint)
        } catch (_: IllegalArgumentException) {
            GiftLookupResult.Failure(
                GiftLookupFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
    }

    companion object {
        /** Public factory for cross-module use (client stays internal). */
        fun create(baseUrl: ApiBaseUrl): PendingGiftLookupRepository =
            PendingGiftLookupRepository(HttpClientFactory.create(), baseUrl)
    }
}

/** Authenticated verification request + code check (R8/R9, bearer via caller). */
class PendingVerificationRepository internal constructor(    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {
    suspend fun requestVerification(
        deliveryId: String,
        confirmDestination: String,
        smsConsentVersion: String?,
        smsAcceptedAtEpochSeconds: Long?,
        idempotencyKey: String,
        accessToken: String,
    ): VerificationResult {
        if (accessToken.isBlank() || deliveryId.isBlank() ||
            confirmDestination.isBlank() || idempotencyKey.isBlank()
        ) {
            return VerificationResult.Failure(GiftLookupFailure.VALIDATION_FAILED, retryable = false)
        }
        val consent = if (smsConsentVersion != null && smsAcceptedAtEpochSeconds != null) {
            VerificationConsentDto(smsConsentVersion, smsAcceptedAtEpochSeconds)
        } else {
            null
        }
        val body = try {
            verificationRequestJson.encodeToString(
                VerificationRequestDto.serializer(),
                VerificationRequestDto(confirmDestination, consent, idempotencyKey),
            )
        } catch (_: Exception) {
            return VerificationResult.Failure(GiftLookupFailure.VALIDATION_FAILED, retryable = false)
        }
        val request = Request.Builder()
            .url(baseUrl.resolve("v2/pending-deliveries/$deliveryId/verification"))
            .header("Accept", GIFT_LOOKUP_MEDIA)
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $accessToken")
            .post(body.toRequestBody(GIFT_LOOKUP_REQUEST_TYPE))
            .build()
        return try {
            client.executeResponseWithCallLifetime(request) { response ->
                interpretVerification(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            VerificationResult.Failure(GiftLookupFailure.NETWORK, retryable = true)
        }
    }

    suspend fun checkCode(
        deliveryId: String,
        attemptId: String,
        code: String,
        accessToken: String,
    ): VerificationResult {
        if (accessToken.isBlank() || deliveryId.isBlank() || attemptId.isBlank() || code.isBlank()) {
            return VerificationResult.Failure(GiftLookupFailure.VALIDATION_FAILED, retryable = false)
        }
        val body = try {
            NetworkJson.encodeToString(VerificationCheckRequestDto.serializer(), VerificationCheckRequestDto(code))
        } catch (_: Exception) {
            return VerificationResult.Failure(GiftLookupFailure.VALIDATION_FAILED, retryable = false)
        }
        val request = Request.Builder()
            .url(baseUrl.resolve("v2/pending-deliveries/$deliveryId/verification/$attemptId/check"))
            .header("Accept", GIFT_LOOKUP_MEDIA)
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $accessToken")
            .post(body.toRequestBody(GIFT_LOOKUP_REQUEST_TYPE))
            .build()
        return try {
            client.executeResponseWithCallLifetime(request) { response ->
                interpretVerification(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            VerificationResult.Failure(GiftLookupFailure.NETWORK, retryable = true)
        }
    }

    private suspend fun interpretVerification(response: Response): VerificationResult {
        val bytes = readLookupBounded(response.body) ?: return VerificationResult.Failure(
            GiftLookupFailure.INVALID_RESPONSE,
            response.code,
            retryable = capsuleHttpFallbackIsRetryable(response.code),
        )
        if (response.code != GIFT_LOOKUP_OK) {
            if (!isLookupJson(response, GIFT_LOOKUP_PROBLEM_SUBTYPE)) {
                return VerificationResult.Failure(
                    GiftLookupFailure.INVALID_RESPONSE,
                    response.code,
                    retryable = capsuleHttpFallbackIsRetryable(response.code),
                )
            }
            val (reason, retryable) = lookupProblemOutcome(bytes.toString(Charsets.UTF_8), response.code)
            return VerificationResult.Failure(
                reason = reason,
                httpStatus = response.code,
                retryable = retryable,
            )
        }
        if (!isLookupJson(response, GIFT_LOOKUP_SUBTYPE)) {
            return VerificationResult.Failure(
                GiftLookupFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        val dto = try {
            NetworkJson.decodeFromString<VerificationResponseDto>(bytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return VerificationResult.Failure(
                GiftLookupFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        } catch (_: IllegalArgumentException) {
            return VerificationResult.Failure(
                GiftLookupFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        return try {
            require(dto.attemptId.isNotBlank())
            if (dto.state == "VERIFIED") {
                VerificationResult.Verified(dto.attemptId)
            } else {
                VerificationResult.Attempt(dto.attemptId, dto.expiresAt, dto.resendCooldownSeconds)
            }
        } catch (_: IllegalArgumentException) {
            VerificationResult.Failure(
                GiftLookupFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
    }

    companion object {
        /** Public factory for cross-module use (client stays internal). */
        fun create(baseUrl: ApiBaseUrl): PendingVerificationRepository =
            PendingVerificationRepository(HttpClientFactory.create(), baseUrl)
    }
}
