package dev.hryshyn.remanence.core.data.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import dev.hryshyn.remanence.core.model.CapsuleId

/** Typed result of one recipient material-synced acknowledgement attempt. */
internal enum class RecipientMaterialSyncedFailure {
    NETWORK,
    RATE_LIMITED,
    HTTP,
    INVALID_RESPONSE,
    AUTH_INVALID,
    VALIDATION_FAILED,
    CAPSULE_NOT_FOUND,
    INTERNAL_ERROR,
}

internal sealed interface RecipientMaterialSyncedResult {
    data class Success(val httpStatus: Int) : RecipientMaterialSyncedResult

    data class Failure(
        val reason: RecipientMaterialSyncedFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : RecipientMaterialSyncedResult
}

/**
 * Authenticated, idempotent recipient acknowledgement after all capsule
 * material is durably cached. The repository has no local state side effects.
 */
internal class RecipientMaterialSyncedRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {

    suspend fun markMaterialSynced(
        capsuleId: CapsuleId,
        accessToken: String,
    ): RecipientMaterialSyncedResult {
        require(accessToken.isNotBlank()) { "access token must not be blank" }
        val canonicalCapsuleId = capsuleId.toRestString()
        require(CapsuleId.parseRest(canonicalCapsuleId).toRestString() == canonicalCapsuleId) {
            "capsule id must be canonical"
        }
        val request = Request.Builder()
            .url(baseUrl.resolve("v1/capsules/$canonicalCapsuleId/material-synced"))
            .header("Accept", JSON_MEDIA_TYPE)
            .header("Authorization", BEARER_PREFIX + accessToken)
            .post(ByteArray(0).toRequestBody(null))
            .build()

        return try {
            client.newCall(request).executeAsync().use { response ->
                interpret(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            RecipientMaterialSyncedResult.Failure(
                reason = RecipientMaterialSyncedFailure.NETWORK,
                retryable = true,
            )
        }
    }

    private fun interpret(response: Response): RecipientMaterialSyncedResult {
        val status = response.code
        if (status == HTTP_UNAUTHORIZED) {
            return RecipientMaterialSyncedResult.Failure(
                reason = RecipientMaterialSyncedFailure.AUTH_INVALID,
                httpStatus = status,
                retryable = false,
            )
        }
        if (status == HTTP_RATE_LIMITED) {
            return RecipientMaterialSyncedResult.Failure(
                reason = RecipientMaterialSyncedFailure.RATE_LIMITED,
                httpStatus = status,
                retryable = true,
            )
        }

        if (status == HTTP_NO_CONTENT) {
            if (response.headers[CONTENT_LENGTH_HEADER]?.let { it != "0" } == true) {
                return invalidResponse(status)
            }
            val bytes = readBounded(response.body)
                ?: return invalidResponse(status)
            return if (bytes.isEmpty()) {
                RecipientMaterialSyncedResult.Success(status)
            } else {
                invalidResponse(status)
            }
        }
        val bytes = readBounded(response.body)
            ?: return invalidResponse(status)
        if (!isProblemJson(response)) return invalidResponse(status)

        val problem = classifyCapsuleProblem(
            text = bytes.toString(Charsets.UTF_8),
            httpStatus = status,
            allowedCodes = ALLOWED_PROBLEM_CODES,
        )
        return RecipientMaterialSyncedResult.Failure(
            reason = problem?.let { mapProblemCode(it.code) } ?: RecipientMaterialSyncedFailure.HTTP,
            httpStatus = status,
            retryable = problem?.retryable ?: capsuleHttpFallbackIsRetryable(status),
        )
    }

    private fun invalidResponse(status: Int): RecipientMaterialSyncedResult.Failure =
        RecipientMaterialSyncedResult.Failure(
            reason = RecipientMaterialSyncedFailure.INVALID_RESPONSE,
            httpStatus = status,
            retryable = capsuleHttpFallbackIsRetryable(status),
        )

    private fun mapProblemCode(code: String): RecipientMaterialSyncedFailure = when (code) {
        CODE_AUTH_INVALID -> RecipientMaterialSyncedFailure.AUTH_INVALID
        CODE_RATE_LIMITED -> RecipientMaterialSyncedFailure.RATE_LIMITED
        CODE_CAPSULE_NOT_FOUND -> RecipientMaterialSyncedFailure.CAPSULE_NOT_FOUND
        CODE_VALIDATION_FAILED -> RecipientMaterialSyncedFailure.VALIDATION_FAILED
        CODE_INTERNAL_ERROR -> RecipientMaterialSyncedFailure.INTERNAL_ERROR
        else -> RecipientMaterialSyncedFailure.HTTP
    }

    private fun isProblemJson(response: Response): Boolean {
        val contentType = response.body.contentType() ?: return false
        return contentType.type == "application" && contentType.subtype == PROBLEM_JSON_SUBTYPE
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
        private const val JSON_MEDIA_TYPE = "application/json"
        private const val PROBLEM_JSON_SUBTYPE = "problem+json"
        private const val CONTENT_LENGTH_HEADER = "Content-Length"
        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_RATE_LIMITED = 429
        private const val MAX_RESPONSE_BYTES = 64 * 1024L
        private const val CODE_AUTH_INVALID = "AUTH_INVALID"
        private const val CODE_RATE_LIMITED = "RATE_LIMITED"
        private const val CODE_CAPSULE_NOT_FOUND = "CAPSULE_NOT_FOUND"
        private const val CODE_VALIDATION_FAILED = "VALIDATION_FAILED"
        private const val CODE_INTERNAL_ERROR = "INTERNAL_ERROR"

        private val ALLOWED_PROBLEM_CODES = setOf(
            CODE_AUTH_INVALID,
            CODE_RATE_LIMITED,
            CODE_CAPSULE_NOT_FOUND,
            CODE_VALIDATION_FAILED,
            CODE_INTERNAL_ERROR,
        )
    }
}
