package dev.hryshyn.remanence.core.data.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.coroutines.executeAsync
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId

/** The already encrypted bytes and their transport binding for one blob. */
class CapsuleBlobUploadRequest(
    val capsuleId: CapsuleId,
    val blobId: BlobId,
    ciphertext: ByteArray,
    ciphertextSha256: ByteArray,
    val idempotencyKey: UUID,
) {
    private val ciphertextSnapshot = ciphertext.copyOf()
    private val ciphertextSha256Snapshot = ciphertextSha256.copyOf()

    /** Returns a copy so callers cannot mutate the bytes used by a later upload. */
    val ciphertext: ByteArray
        get() = ciphertextSnapshot.copyOf()

    /** Returns a copy so callers cannot mutate the hash bound to the ciphertext. */
    val ciphertextSha256: ByteArray
        get() = ciphertextSha256Snapshot.copyOf()

    init {
        require(ciphertextSnapshot.isNotEmpty()) { "ciphertext must not be empty" }
        require(ciphertextSha256Snapshot.size == SHA256_BYTES) { "ciphertext hash must be SHA-256" }
        require(
            MessageDigest.isEqual(
                MessageDigest.getInstance("SHA-256").digest(ciphertextSnapshot),
                ciphertextSha256Snapshot,
            ),
        ) { "ciphertext hash does not match ciphertext" }
    }

    override fun toString(): String = "CapsuleBlobUploadRequest(<redacted>)"

    private companion object {
        const val SHA256_BYTES = 32
    }
}

enum class CapsuleBlobUploadFailure {
    NETWORK,
    RATE_LIMITED,
    HTTP,
    INVALID_RESPONSE,
    AUTH_INVALID,
    VALIDATION_FAILED,
    CAPSULE_NOT_FOUND,
    CAPSULE_STATE_INVALID,
    DRAFT_EXPIRED,
    BLOB_NOT_DECLARED,
    BLOB_SIZE_INVALID,
    BLOB_HASH_MISMATCH,
    BLOB_CONFLICT,
    INTERNAL_UNAVAILABLE,
    INTERNAL_ERROR,
}

sealed interface CapsuleBlobUploadResult {
    data class Success(val httpStatus: Int) : CapsuleBlobUploadResult

    data class Failure(
        val reason: CapsuleBlobUploadFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : CapsuleBlobUploadResult
}

/** Authenticated, resource-idempotent upload of one encrypted capsule blob. */
class CapsuleBlobUploadRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {

    suspend fun uploadBlob(
        request: CapsuleBlobUploadRequest,
        accessToken: String,
    ): CapsuleBlobUploadResult {
        val ciphertext = request.ciphertext
        val ciphertextSha256 = request.ciphertextSha256
        val hashHeader = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(ciphertextSha256)
        val requestBody = ciphertext.toRequestBody(OCTET_STREAM_MEDIA_TYPE.toMediaTypeOrNull())
        val httpRequest = Request.Builder()
            .url(baseUrl.resolve("v1/capsules/${request.capsuleId.toRestString()}/blobs/${request.blobId.toRestString()}"))
            .header("Accept", JSON_MEDIA_TYPE)
            .header("Authorization", BEARER_PREFIX + accessToken)
            .header("Content-Type", OCTET_STREAM_MEDIA_TYPE)
            .header("Content-Length", ciphertext.size.toString())
            .header("X-Remanence-Ciphertext-SHA256", hashHeader)
            .header("Idempotency-Key", request.idempotencyKey.toString())
            .put(requestBody)
            .build()

        return try {
            client.newCall(httpRequest).executeAsync().use { response ->
                interpret(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            CapsuleBlobUploadResult.Failure(CapsuleBlobUploadFailure.NETWORK, retryable = true)
        }
    }

    private fun interpret(response: Response): CapsuleBlobUploadResult {
        val bytes = readBounded(response.body)
            ?: return CapsuleBlobUploadResult.Failure(
                CapsuleBlobUploadFailure.INVALID_RESPONSE,
                response.code,
                retryable = capsuleHttpFallbackIsRetryable(response.code),
            )
        if (response.code == HTTP_NO_CONTENT) {
            return if (bytes.isEmpty()) {
                CapsuleBlobUploadResult.Success(response.code)
            } else {
                CapsuleBlobUploadResult.Failure(
                    CapsuleBlobUploadFailure.INVALID_RESPONSE,
                    response.code,
                    retryable = false,
                )
            }
        }
        if (!isProblemJson(response)) {
            return CapsuleBlobUploadResult.Failure(
                CapsuleBlobUploadFailure.INVALID_RESPONSE,
                response.code,
                retryable = capsuleHttpFallbackIsRetryable(response.code),
            )
        }
        val problem = parseProblem(bytes.toString(Charsets.UTF_8), response.code)
        return CapsuleBlobUploadResult.Failure(
            reason = problem?.let { mapProblemCode(it.code) } ?: CapsuleBlobUploadFailure.HTTP,
            httpStatus = response.code,
            retryable = problem?.retryable ?: capsuleHttpFallbackIsRetryable(response.code),
        )
    }

    private fun parseProblem(text: String, httpStatus: Int): ClassifiedCapsuleProblem? =
        classifyCapsuleProblem(text, httpStatus, BLOB_PROBLEM_CODES)

    private fun mapProblemCode(code: String): CapsuleBlobUploadFailure = when (code) {
        "AUTH_INVALID" -> CapsuleBlobUploadFailure.AUTH_INVALID
        "RATE_LIMITED" -> CapsuleBlobUploadFailure.RATE_LIMITED
        "VALIDATION_FAILED" -> CapsuleBlobUploadFailure.VALIDATION_FAILED
        "CAPSULE_NOT_FOUND" -> CapsuleBlobUploadFailure.CAPSULE_NOT_FOUND
        "CAPSULE_STATE_INVALID" -> CapsuleBlobUploadFailure.CAPSULE_STATE_INVALID
        "DRAFT_EXPIRED" -> CapsuleBlobUploadFailure.DRAFT_EXPIRED
        "BLOB_NOT_DECLARED" -> CapsuleBlobUploadFailure.BLOB_NOT_DECLARED
        "BLOB_SIZE_INVALID" -> CapsuleBlobUploadFailure.BLOB_SIZE_INVALID
        "BLOB_HASH_MISMATCH" -> CapsuleBlobUploadFailure.BLOB_HASH_MISMATCH
        "BLOB_CONFLICT" -> CapsuleBlobUploadFailure.BLOB_CONFLICT
        "INTERNAL_ERROR" -> CapsuleBlobUploadFailure.INTERNAL_ERROR
        else -> CapsuleBlobUploadFailure.HTTP
    }

    private fun isProblemJson(response: Response): Boolean {
        val contentType = response.body.contentType() ?: return false
        return contentType.type == "application" && contentType.subtype == "problem+json"
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
        private const val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream"
        private const val HTTP_NO_CONTENT = 204
        private const val MAX_RESPONSE_BYTES = 64 * 1024L

        private val BLOB_PROBLEM_CODES = setOf(
            "AUTH_INVALID",
            "RATE_LIMITED",
            "VALIDATION_FAILED",
            "CAPSULE_NOT_FOUND",
            "CAPSULE_STATE_INVALID",
            "DRAFT_EXPIRED",
            "BLOB_NOT_DECLARED",
            "BLOB_SIZE_INVALID",
            "BLOB_HASH_MISMATCH",
            "BLOB_CONFLICT",
            "INTERNAL_ERROR",
        )

        fun create(baseUrl: ApiBaseUrl): CapsuleBlobUploadRepository =
            CapsuleBlobUploadRepository(HttpClientFactory.create(), baseUrl)
    }
}
