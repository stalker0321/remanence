package dev.hryshyn.remanence.core.data.network

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.coroutines.executeAsync
import okio.BufferedSource
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.ProtocolV1Limits

/** The transport declaration for one caller-owned temporary ciphertext file. */
class RecipientBlobDownloadRequest(
    val capsuleId: CapsuleId,
    val blobId: BlobId,
    val expectedCiphertextSize: Long,
    expectedCiphertextSha256: ByteArray,
    val destination: File,
) {
    private val expectedCiphertextSha256Snapshot = expectedCiphertextSha256.copyOf()

    val expectedCiphertextSha256: ByteArray
        get() = expectedCiphertextSha256Snapshot.copyOf()

    init {
        require(expectedCiphertextSize > 0L) { "ciphertext size must be positive" }
        require(expectedCiphertextSize <= ProtocolV1Limits.ENCRYPTED_PHOTO_MAX_CIPHERTEXT_BYTES) {
            "ciphertext size exceeds protocol limit"
        }
        require(expectedCiphertextSha256Snapshot.size == SHA256_BYTES) {
            "ciphertext hash must be SHA-256"
        }
    }

    override fun toString(): String = "RecipientBlobDownloadRequest(<redacted>)"

    private companion object {
        const val SHA256_BYTES = 32
    }
}

enum class RecipientBlobDownloadFailure {
    VALIDATION_FAILED,
    DESTINATION_NOT_FRESH,
    NETWORK,
    AUTH_INVALID,
    NOT_FOUND,
    RATE_LIMITED,
    HTTP,
    INVALID_RESPONSE,
    INTEGRITY_FAILED,
    LOCAL_STORAGE,
    INTERNAL_ERROR,
}

sealed interface RecipientBlobDownloadResult {
    class Success(
        val ciphertextFile: File,
        val sizeBytes: Long,
    ) : RecipientBlobDownloadResult {
        override fun toString(): String = "RecipientBlobDownloadResult.Success(<redacted>)"
    }

    data class Failure(
        val reason: RecipientBlobDownloadFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : RecipientBlobDownloadResult
}

/**
 * Authenticated, streaming GET of one opaque recipient ciphertext blob.
 * Transport verification is complete when [RecipientBlobDownloadResult.Success]
 * is returned; crypto acceptance and durable cache adoption remain later
 * boundaries.
 */
class RecipientBlobDownloadRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
    private val outputStreamFactory: (File) -> OutputStream = { FileOutputStream(it) },
) {

    suspend fun downloadBlob(
        request: RecipientBlobDownloadRequest,
        accessToken: String,
    ): RecipientBlobDownloadResult {
        require(accessToken.isNotBlank()) { "access token must not be blank" }

        val destinationExists = try {
            request.destination.exists()
        } catch (_: SecurityException) {
            return RecipientBlobDownloadResult.Failure(
                reason = RecipientBlobDownloadFailure.LOCAL_STORAGE,
                retryable = true,
            )
        }
        if (destinationExists) {
            return RecipientBlobDownloadResult.Failure(
                reason = RecipientBlobDownloadFailure.DESTINATION_NOT_FRESH,
                retryable = false,
            )
        }

        val httpRequest = Request.Builder()
            .url(baseUrl.resolve("v1/capsules/${request.capsuleId.toRestString()}/blobs/${request.blobId.toRestString()}"))
            .header("Accept", OCTET_STREAM_MEDIA_TYPE)
            .header("Authorization", BEARER_PREFIX + accessToken)
            .get()
            .build()

        var createdByInvocation = false
        var retainDestination = false
        return try {
            val result = try {
                client.newCall(httpRequest).executeAsync().use { response ->
                    if (response.code != HTTP_OK) {
                        interpretNonSuccess(response)
                    } else if (!hasCanonicalSuccessHeaders(response, request)) {
                        RecipientBlobDownloadResult.Failure(
                            reason = RecipientBlobDownloadFailure.INVALID_RESPONSE,
                            httpStatus = response.code,
                            retryable = false,
                        )
                    } else {
                        val created = try {
                            request.destination.createNewFile()
                        } catch (_: IOException) {
                            false
                        } catch (_: SecurityException) {
                            false
                        }
                        if (!created) {
                            if (request.destination.exists()) {
                                RecipientBlobDownloadResult.Failure(
                                    reason = RecipientBlobDownloadFailure.DESTINATION_NOT_FRESH,
                                    httpStatus = response.code,
                                    retryable = false,
                                )
                            } else {
                                RecipientBlobDownloadResult.Failure(
                                    reason = RecipientBlobDownloadFailure.LOCAL_STORAGE,
                                    httpStatus = response.code,
                                    retryable = true,
                                )
                            }
                        } else {
                            createdByInvocation = true
                            val streamFailure = streamVerifiedCiphertext(
                                body = response.body,
                                request = request,
                            )
                            if (streamFailure != null) {
                                RecipientBlobDownloadResult.Failure(
                                    reason = streamFailure,
                                    httpStatus = response.code,
                                    retryable = streamFailure == RecipientBlobDownloadFailure.NETWORK ||
                                        streamFailure == RecipientBlobDownloadFailure.LOCAL_STORAGE,
                                )
                            } else {
                                RecipientBlobDownloadResult.Success(
                                    ciphertextFile = request.destination,
                                    sizeBytes = request.expectedCiphertextSize,
                                )
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                RecipientBlobDownloadResult.Failure(
                    reason = RecipientBlobDownloadFailure.NETWORK,
                    retryable = true,
                )
            } catch (_: SecurityException) {
                RecipientBlobDownloadResult.Failure(
                    reason = RecipientBlobDownloadFailure.LOCAL_STORAGE,
                    retryable = true,
                )
            }
            if (result is RecipientBlobDownloadResult.Success) {
                retainDestination = true
            }
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (createdByInvocation && !retainDestination) {
                runCatching { request.destination.delete() }
            }
        }
    }

    private fun interpretNonSuccess(response: Response): RecipientBlobDownloadResult {
        val status = response.code
        val problemBody = if (isProblemJson(response)) readBounded(response.body) else null
        val problem = problemBody?.let {
            classifyCapsuleProblem(
                text = it,
                httpStatus = status,
                allowedCodes = ALLOWED_PROBLEM_CODES,
            )
        }
        val reason = when (problem?.code) {
            CODE_AUTH_INVALID -> RecipientBlobDownloadFailure.AUTH_INVALID
            CODE_RATE_LIMITED -> RecipientBlobDownloadFailure.RATE_LIMITED
            CODE_CAPSULE_NOT_FOUND,
            CODE_BLOB_NOT_DECLARED,
            -> RecipientBlobDownloadFailure.NOT_FOUND
            CODE_INTERNAL_ERROR -> RecipientBlobDownloadFailure.INTERNAL_ERROR
            else -> when (status) {
                HTTP_UNAUTHORIZED -> RecipientBlobDownloadFailure.AUTH_INVALID
                HTTP_NOT_FOUND -> RecipientBlobDownloadFailure.NOT_FOUND
                HTTP_RATE_LIMITED -> RecipientBlobDownloadFailure.RATE_LIMITED
                else -> if (problemBody != null) {
                    RecipientBlobDownloadFailure.INVALID_RESPONSE
                } else {
                    RecipientBlobDownloadFailure.HTTP
                }
            }
        }
        return RecipientBlobDownloadResult.Failure(
            reason = reason,
            httpStatus = status,
            retryable = problem?.retryable ?: capsuleHttpFallbackIsRetryable(status),
        )
    }

    private fun hasCanonicalSuccessHeaders(
        response: Response,
        request: RecipientBlobDownloadRequest,
    ): Boolean {
        val expectedEtag = "\"${lowercaseHex(request.expectedCiphertextSha256)}\""
        return response.headers.values(CONTENT_TYPE_HEADER).singleOrNull() == OCTET_STREAM_MEDIA_TYPE &&
            response.headers.values(CONTENT_LENGTH_HEADER).singleOrNull() ==
            request.expectedCiphertextSize.toString() &&
            response.headers.values(ETAG_HEADER).singleOrNull() == expectedEtag &&
            response.headers.values(CONTENT_ENCODING_HEADER).isEmpty() &&
            response.headers.values(TRANSFER_ENCODING_HEADER).isEmpty() &&
            response.headers.values(CONTENT_RANGE_HEADER).isEmpty() &&
            response.headers.values(TRAILER_HEADER).isEmpty()
    }

    /** Returns a stable transport failure, or null after full verification. */
    private fun streamVerifiedCiphertext(
        body: ResponseBody,
        request: RecipientBlobDownloadRequest,
    ): RecipientBlobDownloadFailure? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var source: BufferedSource? = null
        var output: OutputStream? = null
        var failure: RecipientBlobDownloadFailure? = null
        var totalBytes = 0L

        try {
            output = try {
                outputStreamFactory(request.destination)
            } catch (_: IOException) {
                failure = RecipientBlobDownloadFailure.LOCAL_STORAGE
                null
            } catch (_: SecurityException) {
                failure = RecipientBlobDownloadFailure.LOCAL_STORAGE
                null
            }
            if (output != null) {
                val input = body.source()
                source = input
                while (failure == null) {
                    val remaining = request.expectedCiphertextSize - totalBytes
                    val readLimit = if (remaining >= buffer.size.toLong()) {
                        buffer.size
                    } else {
                        (remaining + 1L).toInt()
                    }
                    val read = try {
                        input.read(buffer, 0, readLimit)
                    } catch (_: IOException) {
                        failure = RecipientBlobDownloadFailure.NETWORK
                        continue
                    }
                    if (read == -1) break
                    if (read <= 0 || read.toLong() > remaining) {
                        failure = RecipientBlobDownloadFailure.INTEGRITY_FAILED
                        continue
                    }
                    try {
                        output.write(buffer, 0, read)
                    } catch (_: IOException) {
                        failure = RecipientBlobDownloadFailure.LOCAL_STORAGE
                        continue
                    }
                    digest.update(buffer, 0, read)
                    totalBytes += read
                }
                if (failure == null && totalBytes != request.expectedCiphertextSize) {
                    failure = RecipientBlobDownloadFailure.INTEGRITY_FAILED
                }
                if (failure == null &&
                    !MessageDigest.isEqual(digest.digest(), request.expectedCiphertextSha256)
                ) {
                    failure = RecipientBlobDownloadFailure.INTEGRITY_FAILED
                }
                if (failure == null) {
                    try {
                        output.flush()
                    } catch (_: IOException) {
                        failure = RecipientBlobDownloadFailure.LOCAL_STORAGE
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            try {
                source?.close()
            } catch (_: IOException) {
                if (failure == null) failure = RecipientBlobDownloadFailure.NETWORK
            }
            try {
                output?.close()
            } catch (_: IOException) {
                if (failure == null) failure = RecipientBlobDownloadFailure.LOCAL_STORAGE
            }
        }
        return failure
    }

    private fun isProblemJson(response: Response): Boolean = response.body.contentType()?.let {
        it.type == "application" && it.subtype == "problem+json"
    } == true

    private fun readBounded(body: ResponseBody): String? {
        if (body.contentLength() > MAX_PROBLEM_BYTES) return null
        val source = body.source()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var totalBytes = 0L
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == -1) break
            if (read <= 0) return null
            totalBytes += read
            if (totalBytes > MAX_PROBLEM_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        private const val CONTENT_LENGTH_HEADER = "Content-Length"
        private const val CONTENT_TYPE_HEADER = "Content-Type"
        private const val CONTENT_ENCODING_HEADER = "Content-Encoding"
        private const val TRANSFER_ENCODING_HEADER = "Transfer-Encoding"
        private const val CONTENT_RANGE_HEADER = "Content-Range"
        private const val TRAILER_HEADER = "Trailer"
        private const val ETAG_HEADER = "ETag"
        private const val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream"
        private const val HTTP_OK = 200
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_RATE_LIMITED = 429
        private const val STREAM_BUFFER_BYTES = 8 * 1024
        private const val MAX_PROBLEM_BYTES = 64 * 1024L
        private const val CODE_AUTH_INVALID = "AUTH_INVALID"
        private const val CODE_RATE_LIMITED = "RATE_LIMITED"
        private const val CODE_CAPSULE_NOT_FOUND = "CAPSULE_NOT_FOUND"
        private const val CODE_BLOB_NOT_DECLARED = "BLOB_NOT_DECLARED"
        private const val CODE_INTERNAL_ERROR = "INTERNAL_ERROR"
        private val ALLOWED_PROBLEM_CODES = setOf(
            CODE_AUTH_INVALID,
            CODE_RATE_LIMITED,
            CODE_CAPSULE_NOT_FOUND,
            CODE_BLOB_NOT_DECLARED,
            CODE_INTERNAL_ERROR,
        )

        fun create(baseUrl: ApiBaseUrl): RecipientBlobDownloadRepository =
            RecipientBlobDownloadRepository(HttpClientFactory.create(), baseUrl)

        private fun lowercaseHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            val digits = "0123456789abcdef"
            bytes.forEach { value ->
                val unsigned = value.toInt() and 0xff
                append(digits[unsigned ushr 4])
                append(digits[unsigned and 0x0f])
            }
        }
    }
}
