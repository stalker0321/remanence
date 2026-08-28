package dev.hryshyn.remanence.core.data.network

import dev.hryshyn.remanence.core.model.UserId
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import okio.Buffer

enum class RecipientUserLookupFailure {
    AUTH_INVALID,
    NETWORK,
    RATE_LIMITED,
    HTTP,
    INVALID_RESPONSE,
    INTERNAL_ERROR,
}

sealed interface RecipientUserLookupResult {
    data class Found(val snapshot: ResolvedHandleSnapshot) : RecipientUserLookupResult

    data object NotFound : RecipientUserLookupResult

    data class Failure(
        val reason: RecipientUserLookupFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : RecipientUserLookupResult
}

/**
 * Authenticated immutable-recipient lookup. This is deliberately separate
 * from handle lookup: callers already possess the canonical UserId and must
 * never fall back to mutable handle semantics.
 */
class RecipientUserLookupRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {
    suspend fun lookup(userId: UserId, accessToken: String? = null): RecipientUserLookupResult {
        val requestBuilder = Request.Builder()
            .url(baseUrl.resolve("v1/directory/users/${userId.toRestString()}"))
            .header("Accept", "application/json")
            .get()
        if (accessToken != null) {
            requestBuilder.header(AUTHORIZATION_HEADER, BEARER_PREFIX + accessToken)
        }
        return try {
            client.newCall(requestBuilder.build()).executeAsync().use { response ->
                interpret(response, userId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            RecipientUserLookupResult.Failure(
                reason = RecipientUserLookupFailure.NETWORK,
                retryable = true,
            )
        }
    }

    private fun interpret(response: Response, requestedUserId: UserId): RecipientUserLookupResult {
        val status = response.code
        val contentType = response.body.contentType()

        if (status in 200..299) {
            if (!contentType.isJson()) {
                return invalidResponse(status, retryable = false)
            }
            val body = response.body.readBoundedUtf8() ?: return invalidResponse(status, retryable = false)
            val dto = try {
                NetworkJson.decodeFromString<DirectoryLookupResponseDto>(body)
            } catch (_: SerializationException) {
                return invalidResponse(status, retryable = false)
            } catch (_: IllegalArgumentException) {
                return invalidResponse(status, retryable = false)
            }
            val snapshot = try {
                mapDirectoryLookupToSnapshot(dto)
            } catch (_: IllegalArgumentException) {
                return invalidResponse(status, retryable = false)
            }
            if (!isValidSnapshot(dto, snapshot, requestedUserId)) {
                return invalidResponse(status, retryable = false)
            }
            return RecipientUserLookupResult.Found(snapshot)
        }

        val body = if (contentType.isProblemJson()) response.body.readBoundedUtf8() else null
        val classified = body?.let {
            classifyCapsuleProblem(
                text = it,
                httpStatus = status,
                allowedCodes = ALLOWED_PROBLEM_CODES,
            )
        }
        if (classified != null) {
            return when (classified.code) {
                CODE_USER_NOT_FOUND -> RecipientUserLookupResult.NotFound
                CODE_AUTH_INVALID -> RecipientUserLookupResult.Failure(
                    reason = RecipientUserLookupFailure.AUTH_INVALID,
                    httpStatus = status,
                    retryable = classified.retryable,
                )
                CODE_RATE_LIMITED -> RecipientUserLookupResult.Failure(
                    reason = RecipientUserLookupFailure.RATE_LIMITED,
                    httpStatus = status,
                    retryable = classified.retryable,
                )
                CODE_INTERNAL_ERROR -> RecipientUserLookupResult.Failure(
                    reason = RecipientUserLookupFailure.INTERNAL_ERROR,
                    httpStatus = status,
                    retryable = classified.retryable,
                )
                else -> invalidResponse(status, retryable = capsuleHttpFallbackIsRetryable(status))
            }
        }

        return RecipientUserLookupResult.Failure(
            reason = if (contentType.isProblemJson()) {
                RecipientUserLookupFailure.INVALID_RESPONSE
            } else {
                RecipientUserLookupFailure.HTTP
            },
            httpStatus = status,
            retryable = capsuleHttpFallbackIsRetryable(status),
        )
    }

    private fun isValidSnapshot(
        dto: DirectoryLookupResponseDto,
        snapshot: ResolvedHandleSnapshot,
        requestedUserId: UserId,
    ): Boolean =
        snapshot.userId == requestedUserId &&
            snapshot.userId.toRestString() == dto.user.userId &&
            snapshot.handle.value == dto.user.handle &&
            snapshot.keyBundleStatus == ACTIVE_STATUS &&
            dto.keyBundle.userId == requestedUserId.toRestString() &&
            snapshot.suite == SUPPORTED_SUITE &&
            snapshot.protocolVersion == SUPPORTED_PROTOCOL_VERSION

    private fun invalidResponse(status: Int, retryable: Boolean): RecipientUserLookupResult.Failure =
        RecipientUserLookupResult.Failure(
            reason = RecipientUserLookupFailure.INVALID_RESPONSE,
            httpStatus = status,
            retryable = retryable,
        )

    companion object {
        fun create(client: OkHttpClient, baseUrl: ApiBaseUrl): RecipientUserLookupRepository =
            RecipientUserLookupRepository(client, baseUrl)

        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
        private const val ACTIVE_STATUS = "ACTIVE"
        private const val SUPPORTED_SUITE = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519"
        private const val SUPPORTED_PROTOCOL_VERSION = 1
        private const val CODE_USER_NOT_FOUND = "USER_NOT_FOUND"
        private const val CODE_AUTH_INVALID = "AUTH_INVALID"
        private const val CODE_RATE_LIMITED = "RATE_LIMITED"
        private const val CODE_INTERNAL_ERROR = "INTERNAL_ERROR"
        private val ALLOWED_PROBLEM_CODES = setOf(
            CODE_USER_NOT_FOUND,
            CODE_AUTH_INVALID,
            CODE_RATE_LIMITED,
            CODE_INTERNAL_ERROR,
        )
    }
}

private fun okhttp3.MediaType?.isJson(): Boolean =
    this?.type == "application" && this.subtype == "json"

private fun okhttp3.MediaType?.isProblemJson(): Boolean =
    this?.type == "application" && this.subtype == "problem+json"

private fun okhttp3.ResponseBody.readBoundedUtf8(): String? {
    if (contentLength() > MAX_BODY_BYTES) return null
    val source = source()
    val buffer = Buffer()
    var totalBytes = 0L
    while (true) {
        val read = source.read(buffer, MAX_BODY_BYTES + 1L - totalBytes)
        if (read == -1L) break
        totalBytes += read
        if (totalBytes > MAX_BODY_BYTES) return null
    }
    return buffer.readByteArray().toString(Charsets.UTF_8)
}

private const val MAX_BODY_BYTES = 64 * 1024
