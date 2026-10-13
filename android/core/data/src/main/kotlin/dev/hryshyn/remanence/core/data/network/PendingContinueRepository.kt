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
 * T186 M7c: typed R7 continuation client — EXACT frozen T185
 * `api-contract.md` §8 (routes scaffold server-side: implemented from the
 * frozen contract, backend never claimed to work).
 *
 * Request: `{}` (the reference is NOT authorization; principal comes from
 * the session bearer). Response `200`, exactly one of:
 * - `{"next": "sync", "capsule_id": "uuid"}` — already owned: ordinary
 *   sync/scan, no proof.
 * - `{"next": "proof", "gift_reference": ..., "delivery_id": ...,
 *   "capsule_id": ..., "delivery_target_id": ..., "channel": ...,
 *   "claim_expires_at": N, "publish_statement_b64": ...,
 *   "publish_signature_69_b64": ...}` — everything for R8 (`delivery_id`)
 *   and R10/R11 (`S` snapshot holds D/KC/expiry/service-key; H is its
 *   hash). `S` is sender-signed public verification material.
 * - Foreign already-owned gifts → generic `GIFT_UNAVAILABLE`, never the
 *   proof branch for someone else.
 */
sealed interface ContinueResult {
    data class Sync(val capsuleId: String) : ContinueResult

    data class Proof(
        val giftReference: String,
        val deliveryId: String,
        val capsuleId: String,
        val deliveryTargetId: String,
        val channel: String,
        val claimExpiresAt: Long,
        val publishStatementB64: String,
        val publishSignature69B64: String,
    ) : ContinueResult

    data class Failure(
        val reason: GiftLookupFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : ContinueResult
}

@Serializable
private data class ContinueProofDto(
    val next: String,
    @SerialName("gift_reference") val giftReference: String = "",
    @SerialName("delivery_id") val deliveryId: String = "",
    @SerialName("capsule_id") val capsuleId: String = "",
    @SerialName("delivery_target_id") val deliveryTargetId: String = "",
    val channel: String = "",
    @SerialName("claim_expires_at") val claimExpiresAt: Long = -1,
    @SerialName("publish_statement_b64") val publishStatementB64: String = "",
    @SerialName("publish_signature_69_b64") val publishSignature69B64: String = "",
)

class PendingContinueRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {
    suspend fun continueFrom(reference: String, accessToken: String): ContinueResult {
        if (accessToken.isBlank() || reference.isBlank()) {
            return ContinueResult.Failure(GiftLookupFailure.VALIDATION_FAILED, retryable = false)
        }
        val request = Request.Builder()
            .url(baseUrl.resolve("v2/gift-lookups/$reference/continue"))
            .header("Accept", "application/json; charset=utf-8")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $accessToken")
            .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            client.executeResponseWithCallLifetime(request) { response ->
                interpret(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            ContinueResult.Failure(GiftLookupFailure.NETWORK, retryable = true)
        }
    }

    private suspend fun interpret(response: Response): ContinueResult {
        val bytes = readContinueBounded(response.body) ?: return ContinueResult.Failure(
            GiftLookupFailure.INVALID_RESPONSE,
            response.code,
            retryable = capsuleHttpFallbackIsRetryable(response.code),
        )
        if (response.code != 200) {
            if (!isContinueJson(response, "problem+json")) {
                return ContinueResult.Failure(
                    GiftLookupFailure.INVALID_RESPONSE,
                    response.code,
                    retryable = capsuleHttpFallbackIsRetryable(response.code),
                )
            }
            val (reason, retryable) = lookupProblemOutcome(
                bytes.toString(Charsets.UTF_8),
                response.code,
            )
            return ContinueResult.Failure(reason, response.code, retryable)
        }
        if (!isContinueJson(response, "json")) {
            return ContinueResult.Failure(
                GiftLookupFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        val dto = try {
            NetworkJson.decodeFromString<ContinueProofDto>(bytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return ContinueResult.Failure(
                GiftLookupFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        } catch (_: IllegalArgumentException) {
            return ContinueResult.Failure(
                GiftLookupFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        return try {
            when (dto.next) {
                "sync" -> {
                    require(dto.capsuleId.isNotBlank())
                    ContinueResult.Sync(dto.capsuleId)
                }
                "proof" -> {
                    require(dto.giftReference.isNotBlank())
                    require(dto.deliveryId.isNotBlank())
                    require(dto.capsuleId.isNotBlank())
                    require(dto.deliveryTargetId.isNotBlank())
                    require(dto.channel.isNotBlank())
                    require(dto.claimExpiresAt >= 0)
                    require(dto.publishStatementB64.isNotBlank())
                    require(dto.publishSignature69B64.isNotBlank())
                    ContinueResult.Proof(
                        dto.giftReference, dto.deliveryId, dto.capsuleId,
                        dto.deliveryTargetId, dto.channel, dto.claimExpiresAt,
                        dto.publishStatementB64, dto.publishSignature69B64,
                    )
                }
                else -> throw IllegalArgumentException("unknown next")
            }
        } catch (_: IllegalArgumentException) {
            ContinueResult.Failure(
                GiftLookupFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
    }

    private fun isContinueJson(response: Response, expectedSubtype: String): Boolean {
        val contentType = response.body.contentType() ?: return false
        return contentType.type == "application" && contentType.subtype == expectedSubtype
    }

    private suspend fun readContinueBounded(body: ResponseBody): ByteArray? {
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
        /** Public factory for cross-module use (client stays internal). */
        fun create(baseUrl: ApiBaseUrl): PendingContinueRepository =
            PendingContinueRepository(HttpClientFactory.create(), baseUrl)
    }
}
