package dev.hryshyn.remanence.core.data.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * T186 M13: receiver-owned v2 incoming feed — exact frozen T185
 * `api-contract.md` §8 (`GET {prefix}/v2/pending-incoming`). Claim-time
 * cursor, per-owner feed; v1 incoming NEVER returns v2 rows (separate
 * route, separate cursor — this client never touches v1).
 *
 * Item keys are exact (`capsule_id, delivery_id, delivery_target_id,
 * claim_id, available_at, envelope_ref, manifest_ref, blob_refs[],
 * completion`); `completion` is null until first-open completion, then
 * `{completed_at}` — surviving retirement, reinstall and second-device
 * restore (same envelope via R12, no code/OTP). Rows exist only for
 * the fixed claim owner (foreign accounts see an empty page — no
 * existence oracle); disabled recipients keep already-delivered
 * envelopes (no remote revocation). No pending-gift list in product
 * UI beyond this sync feed. `limit` default 50, max 200.
 */
enum class PendingFeedFailure {
    NETWORK,
    RATE_LIMITED,
    HTTP,
    INVALID_RESPONSE,
    AUTH_INVALID,
    VALIDATION_FAILED,
    PROTOCOL_UNSUPPORTED,
    INTERNAL_ERROR,
}

data class PendingFeedCompletion(
    val completedAt: Long,
)

data class PendingFeedItem(
    val capsuleId: String,
    val deliveryId: String,
    val deliveryTargetId: String,
    val claimId: String,
    val availableAt: Long,
    val envelopeRef: String,
    val manifestRef: String,
    val blobRefs: List<String>,
    val completion: PendingFeedCompletion?,
)

sealed interface PendingFeedResult {
    data class Page(
        val items: List<PendingFeedItem>,
        /** Opaque cursor for the next page (empty when exhausted). */
        val nextCursor: String,
    ) : PendingFeedResult

    data class Failure(
        val reason: PendingFeedFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : PendingFeedResult
}

@Serializable
private data class PendingFeedCompletionDto(
    @SerialName("completed_at") val completedAt: Long = -1,
)

@Serializable
private data class PendingFeedItemDto(
    @SerialName("capsule_id") val capsuleId: String = "",
    @SerialName("delivery_id") val deliveryId: String = "",
    @SerialName("delivery_target_id") val deliveryTargetId: String = "",
    @SerialName("claim_id") val claimId: String = "",
    @SerialName("available_at") val availableAt: Long = -1,
    @SerialName("envelope_ref") val envelopeRef: String = "",
    @SerialName("manifest_ref") val manifestRef: String = "",
    @SerialName("blob_refs") val blobRefs: List<String> = emptyList(),
    val completion: PendingFeedCompletionDto? = null,
)

@Serializable
private data class PendingFeedResponseDto(
    val items: List<PendingFeedItemDto> = emptyList(),
    /**
     * T186 M14b (T187 B2): the server ALWAYS includes the key with a
     * null value when no page follows (terminal pages, disabled-owner
     * empty page). Nullable DTO with `""`-when-null normalization at
     * the result layer — never `coerceInputValues`/`ignoreUnknownKeys`
     * weakening on the shared [NetworkJson].
     */
    @SerialName("next_cursor") val nextCursor: String? = null,
)

private val PENDING_FEED_PROBLEM_CODES = setOf(
    "AUTH_INVALID",
    "RATE_LIMITED",
    "VALIDATION_FAILED",
    "PROTOCOL_UNSUPPORTED",
    "INTERNAL_ERROR",
)

/**
 * T186 M14b (T187 minor): strict endpoint-scoped terminal tuples for
 * the feed surface (verified against
 * `server/src/remanence/pending/api.py`): `VALIDATION_FAILED` at 400
 * (cursor), `PROTOCOL_UNSUPPORTED` at 404 (gate). The shared canonical
 * map must NOT absorb these — legacy v1 keeps both at 422.
 */
private val PENDING_FEED_ENDPOINT_STATUSES = mapOf(
    "VALIDATION_FAILED" to 400,
    "PROTOCOL_UNSUPPORTED" to 404,
)

private val PENDING_FEED_ENDPOINT_EXCLUSIVE = PENDING_FEED_ENDPOINT_STATUSES.keys

class PendingIncomingRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {
    suspend fun page(
        cursor: String?,
        limit: Int = 50,
        accessToken: String,
    ): PendingFeedResult {
        if (accessToken.isBlank() || limit !in 1..200) {
            return PendingFeedResult.Failure(PendingFeedFailure.VALIDATION_FAILED, retryable = false)
        }
        val url = baseUrl.resolve("v2/pending-incoming").newBuilder()
            .addQueryParameter("limit", limit.toString())
        if (!cursor.isNullOrEmpty()) {
            url.addQueryParameter("cursor", cursor)
        }
        val request = Request.Builder()
            .url(url.build())
            .header("Accept", "application/json; charset=utf-8")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return try {
            client.executeResponseWithCallLifetime(request) { response ->
                interpret(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            PendingFeedResult.Failure(PendingFeedFailure.NETWORK, retryable = true)
        }
    }

    private suspend fun interpret(response: Response): PendingFeedResult {
        val bytes = readFeedBounded(response.body) ?: return PendingFeedResult.Failure(
            PendingFeedFailure.INVALID_RESPONSE,
            response.code,
            retryable = capsuleHttpFallbackIsRetryable(response.code),
        )
        if (response.code != 200) {
            return feedProblem(response, bytes)
        }
        if (!isFeedJson(response, "json")) {
            return PendingFeedResult.Failure(
                PendingFeedFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        val dto = try {
            NetworkJson.decodeFromString<PendingFeedResponseDto>(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return PendingFeedResult.Failure(
                PendingFeedFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        return try {
            PendingFeedResult.Page(
                dto.items.map { item ->
                    require(item.capsuleId.isNotBlank() && item.deliveryId.isNotBlank() &&
                        item.deliveryTargetId.isNotBlank() && item.claimId.isNotBlank() &&
                        item.availableAt >= 0 && item.envelopeRef.isNotBlank() &&
                        item.manifestRef.isNotBlank())
                    val completion = item.completion?.let {
                        require(it.completedAt >= 0)
                        PendingFeedCompletion(it.completedAt)
                    }
                    PendingFeedItem(
                        item.capsuleId, item.deliveryId, item.deliveryTargetId,
                        item.claimId, item.availableAt, item.envelopeRef,
                        item.manifestRef, item.blobRefs.toList(), completion,
                    )
                },
                dto.nextCursor ?: "",
            )
        } catch (_: IllegalArgumentException) {
            PendingFeedResult.Failure(
                PendingFeedFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
    }

    private suspend fun feedProblem(response: Response, bytes: ByteArray): PendingFeedResult {
        val failure = feedPublishProblem(response, bytes)
        return PendingFeedResult.Failure(failure.first, response.code, failure.second)
    }

    private suspend fun feedPublishProblem(
        response: Response,
        bytes: ByteArray,
    ): Pair<PendingFeedFailure, Boolean> {
        val text = bytes.toString(Charsets.UTF_8)
        if (isFeedJson(response, "problem+json")) {
            val problem = classifyCapsuleProblem(
                text,
                response.code,
                PENDING_FEED_PROBLEM_CODES,
                PENDING_FEED_ENDPOINT_STATUSES,
                PENDING_FEED_ENDPOINT_EXCLUSIVE,
            )
            if (problem != null) {
                val reason = mapFeedProblem(problem.code)
                val retryable = problem.retryable ||
                    reason == PendingFeedFailure.AUTH_INVALID
                return reason to retryable
            }
        }
        if (isFeedJson(response, "problem+json") || isFeedJson(response, "json")) {
            val minimal = minimalFeedProblem(text, response.code)
            if (minimal != null) return minimal
        }
        return PendingFeedFailure.HTTP to
            capsuleHttpFallbackIsRetryable(response.code)
    }

    private fun minimalFeedProblem(
        text: String,
        httpStatus: Int,
    ): Pair<PendingFeedFailure, Boolean>? {
        val dto = try {
            minimalFeedJson.decodeFromString<MinimalFeedProblemDto>(text)
        } catch (_: Exception) {
            return null
        }
        if (dto.code !in PENDING_FEED_PROBLEM_CODES) return null
        if (dto.status != httpStatus) return null
        val canonical = canonicalTerminalTuple(
            dto.code,
            PENDING_FEED_ENDPOINT_STATUSES,
            PENDING_FEED_ENDPOINT_EXCLUSIVE,
        ) ?: return null
        if (canonical.first != httpStatus) return null
        if (dto.retryable != null && dto.retryable != canonical.second) return null
        val reason = mapFeedProblem(dto.code)
        val retryable = canonical.second ||
            reason == PendingFeedFailure.AUTH_INVALID
        return reason to retryable
    }

    private fun mapFeedProblem(code: String): PendingFeedFailure = when (code) {
        "AUTH_INVALID" -> PendingFeedFailure.AUTH_INVALID
        "RATE_LIMITED" -> PendingFeedFailure.RATE_LIMITED
        "VALIDATION_FAILED" -> PendingFeedFailure.VALIDATION_FAILED
        "PROTOCOL_UNSUPPORTED" -> PendingFeedFailure.PROTOCOL_UNSUPPORTED
        "INTERNAL_ERROR" -> PendingFeedFailure.INTERNAL_ERROR
        else -> PendingFeedFailure.HTTP
    }

    private fun isFeedJson(response: Response, expectedSubtype: String): Boolean {
        val contentType = response.body.contentType() ?: return false
        return contentType.type == "application" && contentType.subtype == expectedSubtype
    }

    private suspend fun readFeedBounded(body: ResponseBody): ByteArray? {
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
        fun create(baseUrl: ApiBaseUrl): PendingIncomingRepository =
            PendingIncomingRepository(HttpClientFactory.create(), baseUrl)
    }
}

@Serializable
private data class MinimalFeedProblemDto(
    val code: String = "",
    val status: Int = 0,
    val retryable: Boolean? = null,
)

private val minimalFeedJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
