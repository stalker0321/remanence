package dev.hryshyn.remanence.core.data.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * T186 M13: receiver-owned v2 material clients — exact frozen T185
 * `api-contract.md` §8 (gated server-side; implemented from the frozen
 * contract, backend never claimed to work).
 *
 * - R18 `GET v2/pending-deliveries/{id}/control`: exact published S +
 *   sender signature + H for new-device admission after code
 *   retirement. Final recipient only; claim expiry never blocks owned
 *   restore. These bytes (not rebuilt equivalents) re-verify the
 *   sender signature and re-derive H on the new device.
 * - R12 `GET v2/pending-deliveries/{id}/claim`: the durable R11 shape
 *   (same envelope bytes) after OTP/session renewal; never another
 *   account. Includes the service signature for deployment-pinned
 *   verification.
 * - Manifest `GET .../manifest`: owner-scoped artifact list.
 * - Blob `GET .../blobs/{blob}`: raw bytes (sender anytime, recipient
 *   only after claim — enforced server-side).
 *
 * Error bodies are the exact minimal pending `_problem` contract
 * (`about:blank`, 4 keys, `application/json`); retryability is derived
 * from the endpoint-scoped canonical tuples (verified against
 * `server/src/remanence/pending/api.py`), never trusted from the wire.
 * Transport + strict shapes only; envelope/HPKE admission lives in
 * `core:crypto` and the scan gates.
 */
enum class PendingMaterialFailure {
    NETWORK,
    RATE_LIMITED,
    HTTP,
    INVALID_RESPONSE,
    AUTH_INVALID,
    VALIDATION_FAILED,
    NOT_FOUND,
    PROVIDER_UNAVAILABLE,
    REGISTRY_NOT_READY,
    PROTOCOL_UNSUPPORTED,
    INTERNAL_ERROR,
}

sealed interface PendingControlResult {
    data class Control(
        val capsuleId: String,
        val deliveryTargetId: String,
        val deliveryId: String,
        val claimId: String,
        val publishStatementB64: String,
        val publishSignature69B64: String,
        val publishStatementSha256Hex: String,
    ) : PendingControlResult

    data class Failure(
        val reason: PendingMaterialFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : PendingControlResult
}

sealed interface PendingRedownloadResult {
    data class Redownloaded(
        val claimId: String,
        val claimBindingB64: String,
        val claimSignature69B64: String,
        val envelopeB64: String,
        val envelopeSha256Hex: String,
    ) : PendingRedownloadResult

    data class Failure(
        val reason: PendingMaterialFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : PendingRedownloadResult
}

data class PendingManifestArtifact(
    val blobId: String,
    val kind: Int,
    val ordinal: Int,
    val ciphertextSize: Long,
    val ciphertextSha256Hex: String,
)

sealed interface PendingManifestResult {
    data class Manifest(
        val deliveryId: String,
        val artifacts: List<PendingManifestArtifact>,
    ) : PendingManifestResult

    data class Failure(
        val reason: PendingMaterialFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : PendingManifestResult
}

sealed interface PendingBlobBytesResult {
    data class Bytes(val blobId: String, val bytes: ByteArray) : PendingBlobBytesResult

    data class Failure(
        val reason: PendingMaterialFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : PendingBlobBytesResult
}

@Serializable
private data class PendingControlResponseDto(
    @SerialName("capsule_id") val capsuleId: String = "",
    @SerialName("delivery_target_id") val deliveryTargetId: String = "",
    @SerialName("delivery_id") val deliveryId: String = "",
    @SerialName("claim_id") val claimId: String = "",
    @SerialName("publish_statement_b64") val publishStatementB64: String = "",
    @SerialName("publish_signature_69_b64") val publishSignature69B64: String = "",
    @SerialName("publish_statement_sha256_hex") val publishStatementSha256Hex: String = "",
)

@Serializable
private data class PendingRedownloadResponseDto(
    @SerialName("claim_id") val claimId: String = "",
    val state: String = "",
    @SerialName("claim_binding_b64") val claimBindingB64: String = "",
    @SerialName("claim_signature_69_b64") val claimSignature69B64: String = "",
    @SerialName("envelope_b64") val envelopeB64: String = "",
    @SerialName("envelope_sha256_hex") val envelopeSha256Hex: String = "",
)

@Serializable
private data class PendingManifestArtifactDto(
    @SerialName("blob_id") val blobId: String = "",
    val kind: Int = -1,
    val ordinal: Int = -999,
    @SerialName("ciphertext_size") val ciphertextSize: Long = -1,
    @SerialName("ciphertext_sha256_hex") val ciphertextSha256Hex: String = "",
)

@Serializable
private data class PendingManifestResponseDto(
    @SerialName("delivery_id") val deliveryId: String = "",
    val artifacts: List<PendingManifestArtifactDto> = emptyList(),
)

@Serializable
private data class PendingBlobSizeDto(
    @SerialName("blob_id") val blobId: String = "",
    val size: Long = -1,
)

private val PENDING_MATERIAL_PROBLEM_CODES = setOf(
    "AUTH_INVALID",
    "RATE_LIMITED",
    "VALIDATION_FAILED",
    "GIFT_UNAVAILABLE",
    "PROVIDER_UNAVAILABLE",
    "REGISTRY_NOT_READY",
    "PROTOCOL_UNSUPPORTED",
    "INTERNAL_ERROR",
)

/**
 * Strict endpoint-scoped terminal tuples for receiver-owned reads
 * (verified against `server/src/remanence/pending/api.py`; the pending
 * gateway emits minimal bodies). The shared canonical map must NOT
 * absorb the 400/404 rows — legacy v1 keeps `VALIDATION_FAILED` at
 * 422 and has no pending rows at all.
 */
private val PENDING_MATERIAL_ENDPOINT_STATUSES = mapOf(
    "GIFT_UNAVAILABLE" to 404,
    "VALIDATION_FAILED" to 400,
    "PROVIDER_UNAVAILABLE" to 503,
    "REGISTRY_NOT_READY" to 503,
    "PROTOCOL_UNSUPPORTED" to 404,
)

private val PENDING_MATERIAL_ENDPOINT_EXCLUSIVE = PENDING_MATERIAL_ENDPOINT_STATUSES.keys

private fun mapMaterialProblem(code: String): PendingMaterialFailure = when (code) {
    "AUTH_INVALID" -> PendingMaterialFailure.AUTH_INVALID
    "RATE_LIMITED" -> PendingMaterialFailure.RATE_LIMITED
    "VALIDATION_FAILED" -> PendingMaterialFailure.VALIDATION_FAILED
    "GIFT_UNAVAILABLE" -> PendingMaterialFailure.NOT_FOUND
    "PROVIDER_UNAVAILABLE" -> PendingMaterialFailure.PROVIDER_UNAVAILABLE
    "REGISTRY_NOT_READY" -> PendingMaterialFailure.REGISTRY_NOT_READY
    "PROTOCOL_UNSUPPORTED" -> PendingMaterialFailure.PROTOCOL_UNSUPPORTED
    "INTERNAL_ERROR" -> PendingMaterialFailure.INTERNAL_ERROR
    else -> PendingMaterialFailure.HTTP
}

class PendingOwnedMaterialRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {
    suspend fun control(deliveryId: String, accessToken: String): PendingControlResult {
        if (accessToken.isBlank() || deliveryId.isBlank()) {
            return PendingControlResult.Failure(PendingMaterialFailure.VALIDATION_FAILED, retryable = false)
        }
        return try {
            client.executeResponseWithCallLifetime(get("v2/pending-deliveries/$deliveryId/control", accessToken)) { response ->
                interpretControl(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            PendingControlResult.Failure(PendingMaterialFailure.NETWORK, retryable = true)
        }
    }

    suspend fun redownload(deliveryId: String, accessToken: String): PendingRedownloadResult {
        if (accessToken.isBlank() || deliveryId.isBlank()) {
            return PendingRedownloadResult.Failure(PendingMaterialFailure.VALIDATION_FAILED, retryable = false)
        }
        return try {
            client.executeResponseWithCallLifetime(get("v2/pending-deliveries/$deliveryId/claim", accessToken)) { response ->
                interpretRedownload(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            PendingRedownloadResult.Failure(PendingMaterialFailure.NETWORK, retryable = true)
        }
    }

    suspend fun manifest(deliveryId: String, accessToken: String): PendingManifestResult {
        if (accessToken.isBlank() || deliveryId.isBlank()) {
            return PendingManifestResult.Failure(PendingMaterialFailure.VALIDATION_FAILED, retryable = false)
        }
        return try {
            client.executeResponseWithCallLifetime(get("v2/pending-deliveries/$deliveryId/manifest", accessToken)) { response ->
                interpretManifest(response, deliveryId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            PendingManifestResult.Failure(PendingMaterialFailure.NETWORK, retryable = true)
        }
    }

    suspend fun blobBytes(
        deliveryId: String,
        blobId: String,
        accessToken: String,
    ): PendingBlobBytesResult {
        if (accessToken.isBlank() || deliveryId.isBlank() || blobId.isBlank()) {
            return PendingBlobBytesResult.Failure(PendingMaterialFailure.VALIDATION_FAILED, retryable = false)
        }
        return try {
            client.executeResponseWithCallLifetime(get("v2/pending-deliveries/$deliveryId/blobs/$blobId", accessToken)) { response ->
                interpretBlob(response, blobId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            PendingBlobBytesResult.Failure(PendingMaterialFailure.NETWORK, retryable = true)
        }
    }

    private fun get(path: String, accessToken: String): Request = Request.Builder()
        .url(baseUrl.resolve(path))
        .header("Accept", "application/json; charset=utf-8")
        .header("Cache-Control", "no-store")
        .header("Authorization", "Bearer $accessToken")
        .get()
        .build()

    private suspend fun interpretControl(response: Response): PendingControlResult {
        val bytes = readMaterialBounded(response.body, 256 * 1024L)
            ?: return PendingControlResult.Failure(
                PendingMaterialFailure.INVALID_RESPONSE,
                response.code,
                retryable = capsuleHttpFallbackIsRetryable(response.code),
            )
        if (response.code != 200) {
            return controlProblem(response, bytes)
        }
        if (!isMaterialJson(response, "json")) {
            return PendingControlResult.Failure(
                PendingMaterialFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        val dto = try {
            NetworkJson.decodeFromString<PendingControlResponseDto>(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return PendingControlResult.Failure(
                PendingMaterialFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        return try {
            require(dto.capsuleId.isNotBlank() && dto.deliveryTargetId.isNotBlank() &&
                dto.deliveryId.isNotBlank() && dto.claimId.isNotBlank() &&
                dto.publishStatementB64.isNotBlank() && dto.publishSignature69B64.isNotBlank() &&
                dto.publishStatementSha256Hex.length == 64)
            PendingControlResult.Control(
                dto.capsuleId, dto.deliveryTargetId, dto.deliveryId, dto.claimId,
                dto.publishStatementB64, dto.publishSignature69B64, dto.publishStatementSha256Hex,
            )
        } catch (_: IllegalArgumentException) {
            PendingControlResult.Failure(
                PendingMaterialFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
    }

    private suspend fun interpretRedownload(response: Response): PendingRedownloadResult {
        val bytes = readMaterialBounded(response.body, 256 * 1024L)
            ?: return PendingRedownloadResult.Failure(
                PendingMaterialFailure.INVALID_RESPONSE,
                response.code,
                retryable = capsuleHttpFallbackIsRetryable(response.code),
            )
        if (response.code != 200) {
            return redownloadProblem(response, bytes)
        }
        if (!isMaterialJson(response, "json")) {
            return PendingRedownloadResult.Failure(
                PendingMaterialFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        val dto = try {
            NetworkJson.decodeFromString<PendingRedownloadResponseDto>(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return PendingRedownloadResult.Failure(
                PendingMaterialFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        return try {
            require(dto.claimId.isNotBlank() && dto.state == "CLAIMED" &&
                dto.claimBindingB64.isNotBlank() &&
                dto.claimSignature69B64.isNotBlank() && dto.envelopeB64.isNotBlank() &&
                dto.envelopeSha256Hex.isNotBlank())
            PendingRedownloadResult.Redownloaded(
                dto.claimId, dto.claimBindingB64, dto.claimSignature69B64,
                dto.envelopeB64, dto.envelopeSha256Hex,
            )
        } catch (_: IllegalArgumentException) {
            PendingRedownloadResult.Failure(
                PendingMaterialFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
    }

    private suspend fun interpretManifest(response: Response, deliveryId: String): PendingManifestResult {
        val bytes = readMaterialBounded(response.body, 256 * 1024L)
            ?: return PendingManifestResult.Failure(
                PendingMaterialFailure.INVALID_RESPONSE,
                response.code,
                retryable = capsuleHttpFallbackIsRetryable(response.code),
            )
        if (response.code != 200) {
            return manifestProblem(response, bytes)
        }
        if (!isMaterialJson(response, "json")) {
            return PendingManifestResult.Failure(
                PendingMaterialFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        val dto = try {
            NetworkJson.decodeFromString<PendingManifestResponseDto>(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return PendingManifestResult.Failure(
                PendingMaterialFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        return try {
            require(dto.deliveryId == deliveryId && dto.artifacts.isNotEmpty())
            PendingManifestResult.Manifest(
                dto.deliveryId,
                dto.artifacts.map {
                    require(it.blobId.isNotBlank() && it.kind in 1..3 &&
                        it.ciphertextSize >= 0 && it.ciphertextSha256Hex.length == 64)
                    PendingManifestArtifact(
                        it.blobId, it.kind, it.ordinal,
                        it.ciphertextSize, it.ciphertextSha256Hex,
                    )
                },
            )
        } catch (_: IllegalArgumentException) {
            PendingManifestResult.Failure(
                PendingMaterialFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
    }

    private suspend fun interpretBlob(response: Response, blobId: String): PendingBlobBytesResult {
        // Raw-bytes bodies (success) vs JSON problem bodies (failure) share
        // the path: decide by status, then by content type.
        val bytes = readMaterialBounded(response.body, 16 * 1024 * 1024L + 1024L)
            ?: return PendingBlobBytesResult.Failure(
                PendingMaterialFailure.INVALID_RESPONSE,
                response.code,
                retryable = capsuleHttpFallbackIsRetryable(response.code),
            )
        if (response.code != 200) {
            return blobProblem(response, bytes)
        }
        // Success is raw bytes; a JSON body here is a contract violation.
        if (isMaterialJson(response, "json") || isMaterialJson(response, "problem+json")) {
            return PendingBlobBytesResult.Failure(
                PendingMaterialFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        if (bytes.isEmpty()) {
            return PendingBlobBytesResult.Failure(
                PendingMaterialFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        return PendingBlobBytesResult.Bytes(blobId, bytes)
    }

    private suspend fun controlProblem(response: Response, bytes: ByteArray): PendingControlResult {
        val failure = materialProblem(response, bytes)
        return PendingControlResult.Failure(failure.first, response.code, failure.second)
    }

    private suspend fun redownloadProblem(response: Response, bytes: ByteArray): PendingRedownloadResult {
        val failure = materialProblem(response, bytes)
        return PendingRedownloadResult.Failure(failure.first, response.code, failure.second)
    }

    private suspend fun manifestProblem(response: Response, bytes: ByteArray): PendingManifestResult {
        val failure = materialProblem(response, bytes)
        return PendingManifestResult.Failure(failure.first, response.code, failure.second)
    }

    private suspend fun blobProblem(response: Response, bytes: ByteArray): PendingBlobBytesResult {
        val failure = materialProblem(response, bytes)
        return PendingBlobBytesResult.Failure(failure.first, response.code, failure.second)
    }

    private suspend fun materialProblem(
        response: Response,
        bytes: ByteArray,
    ): Pair<PendingMaterialFailure, Boolean> {
        val text = bytes.toString(Charsets.UTF_8)
        // Strict full-shape contract first (v1 fields, problem+json).
        if (isMaterialJson(response, "problem+json")) {
            val problem = classifyCapsuleProblem(
                text,
                response.code,
                PENDING_MATERIAL_PROBLEM_CODES,
                PENDING_MATERIAL_ENDPOINT_STATUSES,
                PENDING_MATERIAL_ENDPOINT_EXCLUSIVE,
            )
            if (problem != null) {
                val reason = mapMaterialProblem(problem.code)
                val retryable = problem.retryable ||
                    reason == PendingMaterialFailure.AUTH_INVALID
                return reason to retryable
            }
        }
        // Exact minimal pending `_problem` contract (`about:blank`, no
        // detail/request_id/retryable). The pending gateway emits
        // `application/json`, so both sub-types are accepted here —
        // success bodies never reach this path (non-200 only).
        if (isMaterialJson(response, "problem+json") || isMaterialJson(response, "json")) {
            val minimal = minimalMaterialProblem(text, response.code)
            if (minimal != null) return minimal
        }
        return PendingMaterialFailure.HTTP to
            capsuleHttpFallbackIsRetryable(response.code)
    }

    private fun minimalMaterialProblem(
        text: String,
        httpStatus: Int,
    ): Pair<PendingMaterialFailure, Boolean>? {
        val dto = try {
            minimalMaterialJson.decodeFromString<MinimalMaterialProblemDto>(text)
        } catch (_: Exception) {
            return null
        }
        if (dto.code !in PENDING_MATERIAL_PROBLEM_CODES) return null
        if (dto.status != httpStatus) return null
        val canonical = canonicalTerminalTuple(
            dto.code,
            PENDING_MATERIAL_ENDPOINT_STATUSES,
            PENDING_MATERIAL_ENDPOINT_EXCLUSIVE,
        ) ?: return null
        if (canonical.first != httpStatus) return null
        if (dto.retryable != null && dto.retryable != canonical.second) return null
        val reason = mapMaterialProblem(dto.code)
        val retryable = canonical.second ||
            reason == PendingMaterialFailure.AUTH_INVALID
        return reason to retryable
    }

    private fun isMaterialJson(response: Response, expectedSubtype: String): Boolean {
        val contentType = response.body.contentType() ?: return false
        return contentType.type == "application" && contentType.subtype == expectedSubtype
    }

    private suspend fun readMaterialBounded(body: ResponseBody, maxBytes: Long): ByteArray? {
        if (body.contentLength() > maxBytes) return null
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
                if (output.size() > maxBytes) return null
            }
        }
        return output.toByteArray()
    }

    companion object {
        /** Public factory for cross-module use (client stays internal). */
        fun create(baseUrl: ApiBaseUrl): PendingOwnedMaterialRepository =
            PendingOwnedMaterialRepository(HttpClientFactory.create(), baseUrl)
    }
}

@Serializable
private data class MinimalMaterialProblemDto(
    val code: String = "",
    val status: Int = 0,
    val retryable: Boolean? = null,
)

private val minimalMaterialJson = Json { ignoreUnknownKeys = true }
