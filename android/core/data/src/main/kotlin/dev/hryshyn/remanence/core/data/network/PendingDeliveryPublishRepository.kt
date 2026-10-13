package dev.hryshyn.remanence.core.data.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * T186 M11: typed R3/R4/R5 publication clients — EXACT frozen T185
 * `api-contract.md` §8 (routes gated server-side: implemented from the
 * frozen contract, backend never claimed to work).
 *
 * R3 `POST v2/pending-deliveries`: frozen pending route + capsule/target
 * UUID + channel + destination + salt + policy + blob declarations →
 * `{delivery_id, state: DRAFT, address_commitment_d_hex, expires_at}`.
 * No SMS/email is sent by this route.
 * R4 `PUT v2/pending-deliveries/{id}/blobs/{blob}`: raw bytes; sender +
 * DRAFT only; the blob must match the R3 declaration (size + SHA-256,
 * 16 MiB cap enforced client-side before send) → `{blob_id, size}`.
 * R5 `POST v2/pending-deliveries/{id}/finalize`: secret K/opening travel
 * ONLY through this dedicated handler (never idempotency storage/logs)
 * → `{delivery_id, state: PENDING, locator_code, claim_expires_at,
 * hint}` (real masked hint, same code on canonical replay).
 *
 * Transport + strict shapes only; S/escrow crypto lives in
 * `core:crypto` (`PendingPublishCrypto`).
 */
enum class PendingPublishFailure {
    NETWORK,
    RATE_LIMITED,
    HTTP,
    INVALID_RESPONSE,
    AUTH_INVALID,
    VALIDATION_FAILED,
    ROUTE_STALE,
    PROTOCOL_UNSUPPORTED,
    IDEMPOTENCY_CONFLICT,
    INTERNAL_ERROR,
}

sealed interface PendingDraftResult {
    data class Draft(
        val deliveryId: String,
        val addressCommitmentHex: String,
        val expiresAt: Long,
    ) : PendingDraftResult

    data class Failure(
        val reason: PendingPublishFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : PendingDraftResult
}

sealed interface PendingBlobResult {
    data class Uploaded(
        val blobId: String,
        val size: Long,
    ) : PendingBlobResult

    data class Failure(
        val reason: PendingPublishFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : PendingBlobResult
}

sealed interface PendingFinalizeResult {
    data class Finalized(
        val deliveryId: String,
        val locatorCode: String,
        val claimExpiresAt: Long,
        val hint: String,
    ) : PendingFinalizeResult

    data class Failure(
        val reason: PendingPublishFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : PendingFinalizeResult
}

/** One artifact declaration bound to S (ciphertext already sealed). */
data class PendingArtifactDeclaration(
    val blobId: String,
    val kind: Int,
    val ordinal: Int,
    val ciphertextSize: Long,
    val ciphertextSha256Hex: String,
)

@Serializable
private data class PendingArtifactDto(
    @SerialName("blob_id") val blobId: String,
    val kind: Int,
    val ordinal: Int,
    @SerialName("ciphertext_size") val ciphertextSize: Long,
    @SerialName("ciphertext_sha256_hex") val ciphertextSha256Hex: String,
)

@Serializable
private data class PendingDraftRequestDto(
    @SerialName("route_id") val routeId: String,
    @SerialName("capsule_id") val capsuleId: String,
    @SerialName("delivery_target_id") val deliveryTargetId: String,
    val channel: String,
    val destination: String,
    @SerialName("salt_b64") val saltB64: String,
    @SerialName("claim_expires_at") val claimExpiresAt: Long,
    @SerialName("claim_policy_version") val claimPolicyVersion: Int,
    val artifacts: List<PendingArtifactDto>,
    @SerialName("idempotency_key") val idempotencyKey: String,
)

@Serializable
private data class PendingDraftResponseDto(
    @SerialName("delivery_id") val deliveryId: String = "",
    val state: String = "",
    @SerialName("address_commitment_d_hex") val addressCommitmentHex: String = "",
    @SerialName("expires_at") val expiresAt: Long = -1,
)

@Serializable
private data class PendingBlobResponseDto(
    @SerialName("blob_id") val blobId: String = "",
    val size: Long = -1,
)

@Serializable
private data class PendingFinalizeRequestDto(
    @SerialName("publish_statement_b64") val publishStatementB64: String,
    @SerialName("publish_signature_69_b64") val publishSignature69B64: String,
    @SerialName("escrow_payload_b64") val escrowPayloadB64: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
)

@Serializable
private data class PendingFinalizeResponseDto(
    @SerialName("delivery_id") val deliveryId: String = "",
    val state: String = "",
    @SerialName("locator_code") val locatorCode: String = "",
    @SerialName("claim_expires_at") val claimExpiresAt: Long = -1,
    val hint: String = "",
)

private val PENDING_PUBLISH_PROBLEM_CODES = setOf(
    "AUTH_INVALID",
    "RATE_LIMITED",
    "VALIDATION_FAILED",
    "ROUTE_STALE",
    "PROTOCOL_UNSUPPORTED",
    "IDEMPOTENCY_CONFLICT",
    "INTERNAL_ERROR",
)

/**
 * Strict endpoint-scoped terminal tuples for the pending publication
 * surface (verified against `server/src/remanence/pending/api.py`):
 * R3 sends `ROUTE_STALE` at 409; R3/R5 send `PROTOCOL_UNSUPPORTED` at
 * 404. The shared canonical map must NOT absorb these — legacy v1 pins
 * `PROTOCOL_UNSUPPORTED` at 422 and that tuple stays intact.
 */
private val PENDING_PUBLISH_ENDPOINT_STATUSES = mapOf(
    "ROUTE_STALE" to 409,
    "PROTOCOL_UNSUPPORTED" to 404,
)

/**
 * Endpoint-exclusive codes: on this surface ONLY the endpoint tuple
 * is canonical. In particular a minimal `PROTOCOL_UNSUPPORTED` at the
 * legacy v1 422 is rejected (the shared tuple stays valid for v1 full
 * bodies and for the strict path — this exclusivity binds the minimal
 * handler only).
 */
private val PENDING_PUBLISH_ENDPOINT_EXCLUSIVE = PENDING_PUBLISH_ENDPOINT_STATUSES.keys

/**
 * Exact minimal pending `_problem` shape
 * (`server/src/remanence/pending/api.py`): `{type, title, status,
 * code}` — no `detail`/`request_id`/`retryable`. The shared strict
 * classifier requires the full v1 fields, so minimal bodies arrive
 * here. `retryable` is optional: omitted ⇒ derived canonically;
 * explicit but wrong ⇒ rejected.
 */
@Serializable
private data class MinimalPublishProblemDto(
    val code: String = "",
    val status: Int = 0,
    val retryable: Boolean? = null,
)

private val minimalPublishJson = Json { ignoreUnknownKeys = true }

private fun mapPublishProblem(code: String): PendingPublishFailure = when (code) {
    "AUTH_INVALID" -> PendingPublishFailure.AUTH_INVALID
    "RATE_LIMITED" -> PendingPublishFailure.RATE_LIMITED
    "VALIDATION_FAILED" -> PendingPublishFailure.VALIDATION_FAILED
    "ROUTE_STALE" -> PendingPublishFailure.ROUTE_STALE
    "PROTOCOL_UNSUPPORTED" -> PendingPublishFailure.PROTOCOL_UNSUPPORTED
    "IDEMPOTENCY_CONFLICT" -> PendingPublishFailure.IDEMPOTENCY_CONFLICT
    "INTERNAL_ERROR" -> PendingPublishFailure.INTERNAL_ERROR
    else -> PendingPublishFailure.HTTP
}

class PendingDeliveryPublishRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {
    suspend fun draft(
        routeId: String,
        capsuleId: String,
        deliveryTargetId: String,
        channel: String,
        destination: String,
        salt32: ByteArray,
        claimExpiresAt: Long,
        artifacts: List<PendingArtifactDeclaration>,
        idempotencyKey: String,
        accessToken: String,
    ): PendingDraftResult {
        if (accessToken.isBlank() || routeId.isBlank() || capsuleId.isBlank() ||
            deliveryTargetId.isBlank() || destination.isBlank() ||
            salt32.size != 32 || claimExpiresAt < 0 || idempotencyKey.isBlank() ||
            (channel != "email" && channel != "sms")
        ) {
            return PendingDraftResult.Failure(PendingPublishFailure.VALIDATION_FAILED, retryable = false)
        }
        for (artifact in artifacts) {
            if (artifact.blobId.isBlank() || artifact.kind !in 1..3 ||
                artifact.ciphertextSize < 0 || artifact.ciphertextSize > MAX_PENDING_BLOB_BYTES ||
                artifact.ciphertextSha256Hex.length != 64
            ) {
                return PendingDraftResult.Failure(PendingPublishFailure.VALIDATION_FAILED, retryable = false)
            }
        }
        val body = try {
            NetworkJson.encodeToString(
                PendingDraftRequestDto.serializer(),
                PendingDraftRequestDto(
                    routeId, capsuleId, deliveryTargetId, channel, destination,
                    Base64.getEncoder().encodeToString(salt32),
                    claimExpiresAt, 1,
                    artifacts.map {
                        PendingArtifactDto(
                            it.blobId, it.kind, it.ordinal,
                            it.ciphertextSize, it.ciphertextSha256Hex,
                        )
                    },
                    idempotencyKey,
                ),
            )
        } catch (_: Exception) {
            return PendingDraftResult.Failure(PendingPublishFailure.VALIDATION_FAILED, retryable = false)
        }
        val request = Request.Builder()
            .url(baseUrl.resolve("v2/pending-deliveries"))
            .header("Accept", "application/json; charset=utf-8")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $accessToken")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            client.executeResponseWithCallLifetime(request) { response ->
                interpretDraft(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            PendingDraftResult.Failure(PendingPublishFailure.NETWORK, retryable = true)
        }
    }

    suspend fun uploadBlob(
        deliveryId: String,
        blobId: String,
        ciphertext: ByteArray,
        declaredSize: Long,
        declaredSha256Hex: String,
        accessToken: String,
    ): PendingBlobResult {
        if (accessToken.isBlank() || deliveryId.isBlank() || blobId.isBlank() ||
            declaredSha256Hex.length != 64
        ) {
            return PendingBlobResult.Failure(PendingPublishFailure.VALIDATION_FAILED, retryable = false)
        }
        // Client-side declaration binding: never send mismatched bytes.
        if (ciphertext.size.toLong() != declaredSize ||
            ciphertext.size.toLong() > MAX_PENDING_BLOB_BYTES
        ) {
            return PendingBlobResult.Failure(PendingPublishFailure.VALIDATION_FAILED, retryable = false)
        }
        val actualSha = MessageDigest.getInstance("SHA-256").digest(ciphertext)
            .joinToString("") { "%02x".format(it) }
        if (!actualSha.equals(declaredSha256Hex, ignoreCase = false)) {
            return PendingBlobResult.Failure(PendingPublishFailure.VALIDATION_FAILED, retryable = false)
        }
        val request = Request.Builder()
            .url(baseUrl.resolve("v2/pending-deliveries/$deliveryId/blobs/$blobId"))
            .header("Accept", "application/json; charset=utf-8")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $accessToken")
            .put(ciphertext.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        return try {
            client.executeResponseWithCallLifetime(request) { response ->
                interpretBlob(response, blobId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            PendingBlobResult.Failure(PendingPublishFailure.NETWORK, retryable = true)
        }
    }

    suspend fun finalize(
        deliveryId: String,
        publishStatementB64: String,
        publishSignature69B64: String,
        escrowPayloadB64: String,
        idempotencyKey: String,
        accessToken: String,
    ): PendingFinalizeResult {
        if (accessToken.isBlank() || deliveryId.isBlank() || publishStatementB64.isBlank() ||
            publishSignature69B64.isBlank() || escrowPayloadB64.isBlank() || idempotencyKey.isBlank()
        ) {
            return PendingFinalizeResult.Failure(PendingPublishFailure.VALIDATION_FAILED, retryable = false)
        }
        val body = try {
            NetworkJson.encodeToString(
                PendingFinalizeRequestDto.serializer(),
                PendingFinalizeRequestDto(
                    publishStatementB64, publishSignature69B64,
                    escrowPayloadB64, idempotencyKey,
                ),
            )
        } catch (_: Exception) {
            return PendingFinalizeResult.Failure(PendingPublishFailure.VALIDATION_FAILED, retryable = false)
        }
        val request = Request.Builder()
            .url(baseUrl.resolve("v2/pending-deliveries/$deliveryId/finalize"))
            .header("Accept", "application/json; charset=utf-8")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $accessToken")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            client.executeResponseWithCallLifetime(request) { response ->
                interpretFinalize(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            PendingFinalizeResult.Failure(PendingPublishFailure.NETWORK, retryable = true)
        }
    }

    private suspend fun interpretDraft(response: Response): PendingDraftResult {
        val bytes = readPublishBounded(response.body) ?: return PendingDraftResult.Failure(
            PendingPublishFailure.INVALID_RESPONSE,
            response.code,
            retryable = capsuleHttpFallbackIsRetryable(response.code),
        )
        if (response.code != 200) {
            return draftProblem(response, bytes)
        }
        if (!isPublishJson(response, "json")) {
            return PendingDraftResult.Failure(
                PendingPublishFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        val dto = try {
            NetworkJson.decodeFromString<PendingDraftResponseDto>(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return PendingDraftResult.Failure(
                PendingPublishFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        return try {
            require(dto.state == "DRAFT")
            require(dto.deliveryId.isNotBlank() && dto.addressCommitmentHex.length == 64 &&
                dto.expiresAt >= 0)
            PendingDraftResult.Draft(dto.deliveryId, dto.addressCommitmentHex, dto.expiresAt)
        } catch (_: IllegalArgumentException) {
            PendingDraftResult.Failure(
                PendingPublishFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
    }

    private suspend fun interpretBlob(response: Response, blobId: String): PendingBlobResult {
        val bytes = readPublishBounded(response.body) ?: return PendingBlobResult.Failure(
            PendingPublishFailure.INVALID_RESPONSE,
            response.code,
            retryable = capsuleHttpFallbackIsRetryable(response.code),
        )
        if (response.code != 200) {
            return blobProblem(response, bytes)
        }
        if (!isPublishJson(response, "json")) {
            return PendingBlobResult.Failure(
                PendingPublishFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        val dto = try {
            NetworkJson.decodeFromString<PendingBlobResponseDto>(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return PendingBlobResult.Failure(
                PendingPublishFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        return try {
            require(dto.blobId == blobId && dto.size >= 0)
            PendingBlobResult.Uploaded(dto.blobId, dto.size)
        } catch (_: IllegalArgumentException) {
            PendingBlobResult.Failure(
                PendingPublishFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
    }

    private suspend fun interpretFinalize(response: Response): PendingFinalizeResult {
        val bytes = readPublishBounded(response.body) ?: return PendingFinalizeResult.Failure(
            PendingPublishFailure.INVALID_RESPONSE,
            response.code,
            retryable = capsuleHttpFallbackIsRetryable(response.code),
        )
        if (response.code != 200) {
            return finalizeProblem(response, bytes)
        }
        if (!isPublishJson(response, "json")) {
            return PendingFinalizeResult.Failure(
                PendingPublishFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        val dto = try {
            NetworkJson.decodeFromString<PendingFinalizeResponseDto>(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return PendingFinalizeResult.Failure(
                PendingPublishFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        return try {
            require(dto.state == "PENDING")
            require(dto.deliveryId.isNotBlank() && dto.locatorCode.length == 5 &&
                dto.claimExpiresAt >= 0 && dto.hint.isNotBlank())
            PendingFinalizeResult.Finalized(
                dto.deliveryId, dto.locatorCode, dto.claimExpiresAt, dto.hint,
            )
        } catch (_: IllegalArgumentException) {
            PendingFinalizeResult.Failure(
                PendingPublishFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
    }

    private suspend fun draftProblem(response: Response, bytes: ByteArray): PendingDraftResult {
        val failure = publishProblem(response, bytes)
        return PendingDraftResult.Failure(failure.first, response.code, failure.second)
    }

    private suspend fun blobProblem(response: Response, bytes: ByteArray): PendingBlobResult {
        val failure = publishProblem(response, bytes)
        return PendingBlobResult.Failure(failure.first, response.code, failure.second)
    }

    private suspend fun finalizeProblem(response: Response, bytes: ByteArray): PendingFinalizeResult {
        val failure = publishProblem(response, bytes)
        return PendingFinalizeResult.Failure(failure.first, response.code, failure.second)
    }

    private suspend fun publishProblem(
        response: Response,
        bytes: ByteArray,
    ): Pair<PendingPublishFailure, Boolean> {
        val text = bytes.toString(Charsets.UTF_8)
        // Strict full-shape contract first (v1 fields, problem+json).
        if (isPublishJson(response, "problem+json")) {
            val problem = classifyCapsuleProblem(
                text,
                response.code,
                PENDING_PUBLISH_PROBLEM_CODES,
                PENDING_PUBLISH_ENDPOINT_STATUSES,
                PENDING_PUBLISH_ENDPOINT_EXCLUSIVE,
            )
            if (problem != null) {
                val reason = mapPublishProblem(problem.code)
                val retryable = problem.retryable ||
                    reason == PendingPublishFailure.AUTH_INVALID
                return reason to retryable
            }
        }
        // Exact minimal pending `_problem` contract (`about:blank` type,
        // no detail/request_id/retryable). The pending gateway emits
        // `application/json`, so both sub-types are accepted here —
        // success bodies never reach this path (non-200 only).
        if (isPublishJson(response, "problem+json") || isPublishJson(response, "json")) {
            val minimal = minimalPublishProblem(text, response.code)
            if (minimal != null) return minimal
        }
        return PendingPublishFailure.HTTP to
            capsuleHttpFallbackIsRetryable(response.code)
    }

    /**
     * Minimal-body tuple gate: frozen code set, wire status equality,
     * endpoint-scoped canonical tuple, locally derived retryability.
     * An explicit `retryable` that contradicts the canonical value is
     * rejected (never trusted); an omitted one is derived.
     */
    private fun minimalPublishProblem(
        text: String,
        httpStatus: Int,
    ): Pair<PendingPublishFailure, Boolean>? {
        val dto = try {
            minimalPublishJson.decodeFromString<MinimalPublishProblemDto>(text)
        } catch (_: Exception) {
            return null
        }
        if (dto.code !in PENDING_PUBLISH_PROBLEM_CODES) return null
        if (dto.status != httpStatus) return null
        val canonical = canonicalTerminalTuple(
            dto.code,
            PENDING_PUBLISH_ENDPOINT_STATUSES,
            PENDING_PUBLISH_ENDPOINT_EXCLUSIVE,
        ) ?: return null
        if (canonical.first != httpStatus) return null
        if (dto.retryable != null && dto.retryable != canonical.second) return null
        val reason = mapPublishProblem(dto.code)
        val retryable = canonical.second ||
            reason == PendingPublishFailure.AUTH_INVALID
        return reason to retryable
    }

    private fun isPublishJson(response: Response, expectedSubtype: String): Boolean {
        val contentType = response.body.contentType() ?: return false
        return contentType.type == "application" && contentType.subtype == expectedSubtype
    }

    private suspend fun readPublishBounded(body: ResponseBody): ByteArray? {
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
        /** Raw blob ceiling enforced before send (frozen §8 R4). */
        const val MAX_PENDING_BLOB_BYTES: Long = 16 * 1024 * 1024L

        /** Public factory for cross-module use (client stays internal). */
        fun create(baseUrl: ApiBaseUrl): PendingDeliveryPublishRepository =
            PendingDeliveryPublishRepository(HttpClientFactory.create(), baseUrl)
    }
}
