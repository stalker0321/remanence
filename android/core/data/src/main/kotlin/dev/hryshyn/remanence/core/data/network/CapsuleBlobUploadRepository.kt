package dev.hryshyn.remanence.core.data.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
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
data class CapsuleBlobUploadRequest(
    val capsuleId: CapsuleId,
    val blobId: BlobId,
    val ciphertext: ByteArray,
    val ciphertextSha256: ByteArray,
    val idempotencyKey: UUID,
) {
    init {
        require(ciphertext.isNotEmpty()) { "ciphertext must not be empty" }
        require(ciphertextSha256.size == SHA256_BYTES) { "ciphertext hash must be SHA-256" }
        require(
            MessageDigest.isEqual(
                MessageDigest.getInstance("SHA-256").digest(ciphertext),
                ciphertextSha256,
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
    ) : CapsuleBlobUploadResult
}

@Serializable
private data class CapsuleBlobProblemResponseDto(
    val type: String,
    val title: String,
    val status: Int,
    val code: String,
    val detail: String,
    val request_id: String,
    val retryable: Boolean,
)

/** Authenticated, resource-idempotent upload of one encrypted capsule blob. */
class CapsuleBlobUploadRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {

    suspend fun uploadBlob(
        request: CapsuleBlobUploadRequest,
        accessToken: String,
    ): CapsuleBlobUploadResult {
        val hashHeader = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(request.ciphertextSha256)
        val requestBody = request.ciphertext.toRequestBody(OCTET_STREAM_MEDIA_TYPE.toMediaTypeOrNull())
        val httpRequest = Request.Builder()
            .url(baseUrl.resolve("v1/capsules/${request.capsuleId.toRestString()}/blobs/${request.blobId.toRestString()}"))
            .header("Accept", JSON_MEDIA_TYPE)
            .header("Authorization", BEARER_PREFIX + accessToken)
            .header("Content-Type", OCTET_STREAM_MEDIA_TYPE)
            .header("Content-Length", request.ciphertext.size.toString())
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
            CapsuleBlobUploadResult.Failure(CapsuleBlobUploadFailure.NETWORK)
        }
    }

    private fun interpret(response: Response): CapsuleBlobUploadResult {
        val bytes = readBounded(response.body)
            ?: return CapsuleBlobUploadResult.Failure(
                CapsuleBlobUploadFailure.INVALID_RESPONSE,
                response.code,
            )
        if (response.code == HTTP_NO_CONTENT) {
            return if (bytes.isEmpty()) {
                CapsuleBlobUploadResult.Success(response.code)
            } else {
                CapsuleBlobUploadResult.Failure(
                    CapsuleBlobUploadFailure.INVALID_RESPONSE,
                    response.code,
                )
            }
        }
        if (!isProblemJson(response)) {
            return CapsuleBlobUploadResult.Failure(
                CapsuleBlobUploadFailure.INVALID_RESPONSE,
                response.code,
            )
        }
        val problemCode = parseProblemCode(bytes.toString(Charsets.UTF_8))
        return CapsuleBlobUploadResult.Failure(
            problemCode ?: CapsuleBlobUploadFailure.HTTP,
            response.code,
        )
    }

    private fun parseProblemCode(text: String): CapsuleBlobUploadFailure? {
        val code = try {
            NetworkJson.decodeFromString<CapsuleBlobProblemResponseDto>(text).code
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return when (code) {
            "AUTH_INVALID" -> CapsuleBlobUploadFailure.AUTH_INVALID
            "VALIDATION_FAILED" -> CapsuleBlobUploadFailure.VALIDATION_FAILED
            "CAPSULE_NOT_FOUND" -> CapsuleBlobUploadFailure.CAPSULE_NOT_FOUND
            "CAPSULE_STATE_INVALID" -> CapsuleBlobUploadFailure.CAPSULE_STATE_INVALID
            "DRAFT_EXPIRED" -> CapsuleBlobUploadFailure.DRAFT_EXPIRED
            "BLOB_NOT_DECLARED" -> CapsuleBlobUploadFailure.BLOB_NOT_DECLARED
            "BLOB_SIZE_INVALID" -> CapsuleBlobUploadFailure.BLOB_SIZE_INVALID
            "BLOB_HASH_MISMATCH" -> CapsuleBlobUploadFailure.BLOB_HASH_MISMATCH
            "BLOB_CONFLICT" -> CapsuleBlobUploadFailure.BLOB_CONFLICT
            "INTERNAL_UNAVAILABLE" -> CapsuleBlobUploadFailure.INTERNAL_UNAVAILABLE
            "INTERNAL_ERROR" -> CapsuleBlobUploadFailure.INTERNAL_ERROR
            else -> null
        }
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
        private const val PROBLEM_MEDIA_TYPE = "application/problem+json"
        private const val HTTP_NO_CONTENT = 204
        private const val MAX_RESPONSE_BYTES = 64 * 1024L

        fun create(baseUrl: ApiBaseUrl): CapsuleBlobUploadRepository =
            CapsuleBlobUploadRepository(HttpClientFactory.create(), baseUrl)
    }
}
