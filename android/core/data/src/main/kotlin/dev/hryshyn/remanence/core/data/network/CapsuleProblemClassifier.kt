package dev.hryshyn.remanence.core.data.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The safe, non-sensitive portion of one validated capsule problem response. */
internal data class ClassifiedCapsuleProblem(
    val code: String,
    val retryable: Boolean,
)

@Serializable
private data class CapsuleProblemResponseDto(
    val type: String,
    val title: String,
    val status: Int,
    val code: String,
    val detail: String,
    val request_id: String,
    val retryable: Boolean,
)

/**
 * Parses only a bounded, endpoint-allowed problem response and accepts it
 * only when the wire status/code/retryability tuple is canonical.
 */
internal fun classifyCapsuleProblem(
    text: String,
    httpStatus: Int,
    allowedCodes: Set<String>,
): ClassifiedCapsuleProblem? {
    val problem = try {
        capsuleProblemJson.decodeFromString<CapsuleProblemResponseDto>(text)
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }

    if (problem.status != httpStatus || problem.code !in allowedCodes) return null
    val canonicalRetryable = canonicalRetryability(problem.code, problem.status) ?: return null
    if (problem.retryable != canonicalRetryable) return null
    return ClassifiedCapsuleProblem(problem.code, problem.retryable)
}

internal fun capsuleHttpFallbackIsRetryable(httpStatus: Int): Boolean =
    httpStatus == HTTP_RATE_LIMITED || httpStatus in HTTP_SERVER_ERROR_RANGE

private fun canonicalRetryability(code: String, status: Int): Boolean? = when {
    code == CODE_RATE_LIMITED && status == HTTP_RATE_LIMITED -> true
    code == CODE_INTERNAL_ERROR && status == HTTP_SERVICE_UNAVAILABLE -> true
    code == CODE_INTERNAL_ERROR && status == HTTP_INTERNAL_SERVER_ERROR -> false
    TERMINAL_CODE_STATUSES[code] == status -> false
    else -> null
}

private val capsuleProblemJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
    explicitNulls = true
    coerceInputValues = false
}

private val TERMINAL_CODE_STATUSES = mapOf(
    "AUTH_INVALID" to 401,
    "VALIDATION_FAILED" to 422,
    "IDEMPOTENCY_CONFLICT" to 409,
    "RECIPIENT_NOT_CONFIRMED" to 409,
    "RECIPIENT_KEY_STALE" to 409,
    "KEY_BUNDLE_NOT_FOUND" to 404,
    "KEY_BUNDLE_INVALID" to 409,
    "KEY_BUNDLE_REVOKED" to 409,
    "CAPSULE_NOT_FOUND" to 404,
    "CAPSULE_STATE_INVALID" to 409,
    "DRAFT_EXPIRED" to 409,
    "BLOB_NOT_DECLARED" to 404,
    "BLOB_SIZE_INVALID" to 422,
    "BLOB_HASH_MISMATCH" to 422,
    "BLOB_CONFLICT" to 409,
    "STATEMENT_INVALID" to 422,
    "SIGNATURE_INVALID" to 422,
    "ENVELOPE_INVALID" to 422,
    "FINALIZE_CONFLICT" to 409,
    "PROTOCOL_UNSUPPORTED" to 422,
)

private const val CODE_RATE_LIMITED = "RATE_LIMITED"
private const val CODE_INTERNAL_ERROR = "INTERNAL_ERROR"
private const val HTTP_RATE_LIMITED = 429
private const val HTTP_INTERNAL_SERVER_ERROR = 500
private const val HTTP_SERVICE_UNAVAILABLE = 503
private val HTTP_SERVER_ERROR_RANGE = 500..599
