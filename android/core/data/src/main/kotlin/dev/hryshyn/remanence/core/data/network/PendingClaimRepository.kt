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
 * T186 M7c: typed R10/R11 clients — EXACT frozen T185 `api-contract.md` §8
 * (routes scaffold server-side: implemented from the frozen contract,
 * backend never claimed to work).
 *
 * R10 `POST v2/pending-deliveries/{id}/claim/options`, request `{}`:
 * `{"challenge_id", "expires_at", "hpke_challenge_b64",
 * "claim_challenge_b64"}` (amendment: canonical context bytes included).
 * R11 `POST .../claim`, request `{"claim_intent_b64",
 * "claim_intent_signature_69_b64", "idempotency_key"}`:
 * `{"claim_id", "state": "CLAIMED", "claim_binding_b64",
 * "claim_signature_69_b64", "envelope_b64", "envelope_sha256_hex"}`.
 * Claim/acceptance crypto (challenge open, intent sign, envelope checks)
 * lives in `core:crypto`; this file is transport + strict shapes only.
 */
enum class ClaimFailure {
    NETWORK,
    RATE_LIMITED,
    HTTP,
    INVALID_RESPONSE,
    AUTH_INVALID,
    VALIDATION_FAILED,
    ADDRESS_PROOF_REQUIRED,
    CLAIM_CONFLICT,
    IDEMPOTENCY_CONFLICT,
    RECIPIENT_KEY_STALE,
    PROTOCOL_UNSUPPORTED,
    INTERNAL_ERROR,
}

sealed interface ClaimOptionsResult {
    data class Options(
        val challengeId: String,
        val expiresAt: Long,
        val hpkeChallengeB64: String,
        val claimChallengeB64: String,
    ) : ClaimOptionsResult

    data class Failure(
        val reason: ClaimFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : ClaimOptionsResult
}

sealed interface ClaimSubmitResult {
    data class Claimed(
        val claimId: String,
        val claimBindingB64: String,
        val claimSignature69B64: String,
        val envelopeB64: String,
        val envelopeSha256Hex: String,
    ) : ClaimSubmitResult

    data class Failure(
        val reason: ClaimFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : ClaimSubmitResult
}

@Serializable
private data class ClaimOptionsResponseDto(
    @kotlinx.serialization.SerialName("challenge_id") val challengeId: String,
    @kotlinx.serialization.SerialName("expires_at") val expiresAt: Long,
    @kotlinx.serialization.SerialName("hpke_challenge_b64") val hpkeChallengeB64: String,
    @kotlinx.serialization.SerialName("claim_challenge_b64") val claimChallengeB64: String,
)

@Serializable
private data class ClaimSubmitRequestDto(
    @kotlinx.serialization.SerialName("claim_intent_b64") val claimIntentB64: String,
    @kotlinx.serialization.SerialName("claim_intent_signature_69_b64") val claimIntentSignature69B64: String,
    @kotlinx.serialization.SerialName("idempotency_key") val idempotencyKey: String,
)

@Serializable
private data class ClaimSubmitResponseDto(
    @kotlinx.serialization.SerialName("claim_id") val claimId: String,
    val state: String,
    @kotlinx.serialization.SerialName("claim_binding_b64") val claimBindingB64: String,
    @kotlinx.serialization.SerialName("claim_signature_69_b64") val claimSignature69B64: String,
    @kotlinx.serialization.SerialName("envelope_b64") val envelopeB64: String,
    @kotlinx.serialization.SerialName("envelope_sha256_hex") val envelopeSha256Hex: String,
)

private val CLAIM_PROBLEM_CODES = setOf(
    "AUTH_INVALID",
    "RATE_LIMITED",
    "VALIDATION_FAILED",
    "ADDRESS_PROOF_REQUIRED",
    "CLAIM_CONFLICT",
    "IDEMPOTENCY_CONFLICT",
    "RECIPIENT_KEY_STALE",
    "PROTOCOL_UNSUPPORTED",
    "INTERNAL_ERROR",
)

private fun mapClaimProblem(code: String): ClaimFailure = when (code) {
    "AUTH_INVALID" -> ClaimFailure.AUTH_INVALID
    "RATE_LIMITED" -> ClaimFailure.RATE_LIMITED
    "VALIDATION_FAILED" -> ClaimFailure.VALIDATION_FAILED
    "ADDRESS_PROOF_REQUIRED" -> ClaimFailure.ADDRESS_PROOF_REQUIRED
    "CLAIM_CONFLICT" -> ClaimFailure.CLAIM_CONFLICT
    "IDEMPOTENCY_CONFLICT" -> ClaimFailure.IDEMPOTENCY_CONFLICT
    "RECIPIENT_KEY_STALE" -> ClaimFailure.RECIPIENT_KEY_STALE
    "PROTOCOL_UNSUPPORTED" -> ClaimFailure.PROTOCOL_UNSUPPORTED
    "INTERNAL_ERROR" -> ClaimFailure.INTERNAL_ERROR
    else -> ClaimFailure.HTTP
}

class PendingClaimRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {
    suspend fun options(deliveryId: String, accessToken: String): ClaimOptionsResult {
        if (accessToken.isBlank() || deliveryId.isBlank()) {
            return ClaimOptionsResult.Failure(ClaimFailure.VALIDATION_FAILED, retryable = false)
        }
        val request = Request.Builder()
            .url(baseUrl.resolve("v2/pending-deliveries/$deliveryId/claim/options"))
            .header("Accept", "application/json; charset=utf-8")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $accessToken")
            .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            client.executeResponseWithCallLifetime(request) { response ->
                interpretOptions(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            ClaimOptionsResult.Failure(ClaimFailure.NETWORK, retryable = true)
        }
    }

    suspend fun submit(
        deliveryId: String,
        claimIntentB64: String,
        claimIntentSignature69B64: String,
        idempotencyKey: String,
        accessToken: String,
    ): ClaimSubmitResult {
        if (accessToken.isBlank() || deliveryId.isBlank() || claimIntentB64.isBlank() ||
            claimIntentSignature69B64.isBlank() || idempotencyKey.isBlank()
        ) {
            return ClaimSubmitResult.Failure(ClaimFailure.VALIDATION_FAILED, retryable = false)
        }
        val body = try {
            NetworkJson.encodeToString(
                ClaimSubmitRequestDto.serializer(),
                ClaimSubmitRequestDto(claimIntentB64, claimIntentSignature69B64, idempotencyKey),
            )
        } catch (_: Exception) {
            return ClaimSubmitResult.Failure(ClaimFailure.VALIDATION_FAILED, retryable = false)
        }
        val request = Request.Builder()
            .url(baseUrl.resolve("v2/pending-deliveries/$deliveryId/claim"))
            .header("Accept", "application/json; charset=utf-8")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $accessToken")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            client.executeResponseWithCallLifetime(request) { response ->
                interpretSubmit(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            ClaimSubmitResult.Failure(ClaimFailure.NETWORK, retryable = true)
        }
    }

    private suspend fun interpretOptions(response: Response): ClaimOptionsResult {
        val bytes = readClaimBounded(response.body) ?: return ClaimOptionsResult.Failure(
            ClaimFailure.INVALID_RESPONSE,
            response.code,
            retryable = capsuleHttpFallbackIsRetryable(response.code),
        )
        if (response.code != 200) {
            return claimProblem(response, bytes)
        }
        if (!isClaimJson(response, "json")) {
            return ClaimOptionsResult.Failure(
                ClaimFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        val dto = try {
            NetworkJson.decodeFromString<ClaimOptionsResponseDto>(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return ClaimOptionsResult.Failure(
                ClaimFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        return try {
            require(dto.challengeId.isNotBlank() && dto.hpkeChallengeB64.isNotBlank() &&
                dto.claimChallengeB64.isNotBlank() && dto.expiresAt >= 0)
            ClaimOptionsResult.Options(dto.challengeId, dto.expiresAt, dto.hpkeChallengeB64, dto.claimChallengeB64)
        } catch (_: IllegalArgumentException) {
            ClaimOptionsResult.Failure(
                ClaimFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
    }

    private suspend fun interpretSubmit(response: Response): ClaimSubmitResult {
        val bytes = readClaimBounded(response.body) ?: return ClaimSubmitResult.Failure(
            ClaimFailure.INVALID_RESPONSE,
            response.code,
            retryable = capsuleHttpFallbackIsRetryable(response.code),
        )
        if (response.code != 200) {
            return submitProblem(response, bytes)
        }
        if (!isClaimJson(response, "json")) {
            return ClaimSubmitResult.Failure(
                ClaimFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        val dto = try {
            NetworkJson.decodeFromString<ClaimSubmitResponseDto>(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return ClaimSubmitResult.Failure(
                ClaimFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        return try {
            require(dto.state == "CLAIMED")
            require(dto.claimId.isNotBlank() && dto.claimBindingB64.isNotBlank() &&
                dto.claimSignature69B64.isNotBlank() && dto.envelopeB64.isNotBlank() &&
                dto.envelopeSha256Hex.isNotBlank())
            ClaimSubmitResult.Claimed(
                dto.claimId, dto.claimBindingB64, dto.claimSignature69B64,
                dto.envelopeB64, dto.envelopeSha256Hex,
            )
        } catch (_: IllegalArgumentException) {
            ClaimSubmitResult.Failure(
                ClaimFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
    }

    private suspend fun claimProblem(response: Response, bytes: ByteArray): ClaimOptionsResult {
        if (!isClaimJson(response, "problem+json")) {
            return ClaimOptionsResult.Failure(
                ClaimFailure.INVALID_RESPONSE,
                response.code,
                retryable = capsuleHttpFallbackIsRetryable(response.code),
            )
        }
        val problem = classifyCapsuleProblem(
            bytes.toString(Charsets.UTF_8),
            response.code,
            CLAIM_PROBLEM_CODES,
        )
        // AUTH_INVALID retained retryable (fresh-lease premise, never dropped).
        val reason = problem?.let { mapClaimProblem(it.code) } ?: ClaimFailure.HTTP
        val retryable = (problem?.retryable ?: capsuleHttpFallbackIsRetryable(response.code)) ||
            reason == ClaimFailure.AUTH_INVALID
        return ClaimOptionsResult.Failure(reason, response.code, retryable)
    }

    private suspend fun submitProblem(response: Response, bytes: ByteArray): ClaimSubmitResult {
        if (!isClaimJson(response, "problem+json")) {
            return ClaimSubmitResult.Failure(
                ClaimFailure.INVALID_RESPONSE,
                response.code,
                retryable = capsuleHttpFallbackIsRetryable(response.code),
            )
        }
        val problem = classifyCapsuleProblem(
            bytes.toString(Charsets.UTF_8),
            response.code,
            CLAIM_PROBLEM_CODES,
        )
        val reason = problem?.let { mapClaimProblem(it.code) } ?: ClaimFailure.HTTP
        val retryable = (problem?.retryable ?: capsuleHttpFallbackIsRetryable(response.code)) ||
            reason == ClaimFailure.AUTH_INVALID
        return ClaimSubmitResult.Failure(reason, response.code, retryable)
    }

    private fun isClaimJson(response: Response, expectedSubtype: String): Boolean {
        val contentType = response.body.contentType() ?: return false
        return contentType.type == "application" && contentType.subtype == expectedSubtype
    }

    private suspend fun readClaimBounded(body: ResponseBody): ByteArray? {
        if (body.contentLength() > 256 * 1024L) return null
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
                if (output.size() > 256 * 1024L) return null
            }
        }
        return output.toByteArray()
    }

    companion object {
        /** Public factory for cross-module use (client stays internal). */
        fun create(baseUrl: ApiBaseUrl): PendingClaimRepository =
            PendingClaimRepository(HttpClientFactory.create(), baseUrl)
    }
}
