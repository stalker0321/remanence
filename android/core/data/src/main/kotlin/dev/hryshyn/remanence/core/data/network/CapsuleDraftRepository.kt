package dev.hryshyn.remanence.core.data.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.coroutines.executeAsync
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.RecipientTarget
import dev.hryshyn.remanence.core.model.UserId

/** One opaque ciphertext declaration sent during draft creation. */
data class CapsuleDraftBlobDeclaration(
    val blobId: BlobId,
    val kind: CapsuleArtifactKind,
    val ordinal: Int?,
    val ciphertextSize: Long,
    val ciphertextSha256: ByteArray,
) {
    init {
        require(ciphertextSize > 0L) { "ciphertext size must be positive" }
        require(ciphertextSha256.size == SHA256_BYTES) { "ciphertext hash must be SHA-256" }
    }

    private companion object {
        const val SHA256_BYTES = 32
    }
}

/** All non-body transport data required for one idempotent draft request. */
data class CapsuleDraftRequest(
    val capsuleId: CapsuleId,
    val senderKeyBundleId: KeyBundleId,
    val recipientTarget: RecipientTarget.ExistingUser,
    val idempotencyKey: UUID,
    val blobs: List<CapsuleDraftBlobDeclaration>,
)

enum class CapsuleDraftState {
    DRAFT,
}

enum class CapsuleDraftBlobState {
    DECLARED,
    STORED,
}

data class CapsuleDraftBlob(
    val blobId: BlobId,
    val state: CapsuleDraftBlobState,
)

/** Domain result returned by the draft repository; wire DTOs stay internal. */
data class CapsuleDraft(
    val capsuleId: CapsuleId,
    val state: CapsuleDraftState,
    val draftExpiresAt: String,
    val blobs: List<CapsuleDraftBlob>,
)

enum class CapsuleDraftFailure {
    NETWORK,
    HTTP,
    INVALID_RESPONSE,
    AUTH_INVALID,
    VALIDATION_FAILED,
    IDEMPOTENCY_CONFLICT,
    RECIPIENT_NOT_CONFIRMED,
    RECIPIENT_KEY_STALE,
    KEY_BUNDLE_NOT_FOUND,
    KEY_BUNDLE_INVALID,
    CAPSULE_STATE_INVALID,
    DRAFT_EXPIRED,
    INTERNAL_UNAVAILABLE,
    INTERNAL_ERROR,
}

sealed interface CapsuleDraftResult {
    data class Success(
        val draft: CapsuleDraft,
        val httpStatus: Int,
    ) : CapsuleDraftResult

    data class Failure(
        val reason: CapsuleDraftFailure,
        val httpStatus: Int? = null,
    ) : CapsuleDraftResult
}

@Serializable
private data class CapsuleDraftBlobRequestDto(
    @SerialName("blob_id") val blobId: String,
    val kind: String,
    val ordinal: Int?,
    @SerialName("ciphertext_size") val ciphertextSize: Long,
    @SerialName("ciphertext_sha256") val ciphertextSha256: String,
)

@Serializable
private data class CapsuleDraftRequestDto(
    @SerialName("capsule_id") val capsuleId: String,
    @SerialName("recipient_user_id") val recipientUserId: String,
    @SerialName("sender_key_bundle_id") val senderKeyBundleId: String,
    @SerialName("recipient_key_bundle_id") val recipientKeyBundleId: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    val blobs: List<CapsuleDraftBlobRequestDto>,
)

@Serializable
private data class CapsuleDraftBlobResponseDto(
    @SerialName("blob_id") val blobId: String,
    val state: String,
)

@Serializable
private data class CapsuleDraftResponseDto(
    @SerialName("capsule_id") val capsuleId: String,
    val state: String,
    @SerialName("draft_expires_at") val draftExpiresAt: String,
    val blobs: List<CapsuleDraftBlobResponseDto>,
)

@Serializable
private data class ProblemResponseDto(
    val type: String,
    val title: String,
    val status: Int,
    val code: String,
    val detail: String,
    val request_id: String,
    val retryable: Boolean,
)

/** Authenticated client for the existing-user M2 draft-create endpoint. */
class CapsuleDraftRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {

    suspend fun createDraft(
        request: CapsuleDraftRequest,
        accessToken: String,
    ): CapsuleDraftResult {
        val target = request.recipientTarget
        val body = CapsuleDraftRequestDto(
            capsuleId = request.capsuleId.toRestString(),
            recipientUserId = target.userId.toRestString(),
            senderKeyBundleId = request.senderKeyBundleId.toRestString(),
            recipientKeyBundleId = target.keyBundleId.toRestString(),
            protocolVersion = PROTOCOL_VERSION,
            blobs = request.blobs.map { blob ->
                CapsuleDraftBlobRequestDto(
                    blobId = blob.blobId.toRestString(),
                    kind = blob.kind.name,
                    // v1 represents manifest ordinals as JSON null.
                    ordinal = if (blob.kind == CapsuleArtifactKind.PHOTO) blob.ordinal else null,
                    ciphertextSize = blob.ciphertextSize,
                    ciphertextSha256 = java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(blob.ciphertextSha256),
                )
            },
        )
        val requestBody = NetworkJson.encodeToString(body)
            .toRequestBody(JSON_MEDIA_TYPE.toMediaTypeOrNull())
        val httpRequest = Request.Builder()
            .url(baseUrl.resolve(PATH))
            .header("Accept", JSON_MEDIA_TYPE)
            .header("Authorization", BEARER_PREFIX + accessToken)
            .header("Idempotency-Key", request.idempotencyKey.toString())
            .post(requestBody)
            .build()

        return try {
            client.newCall(httpRequest).executeAsync().use { response ->
                interpret(response, request)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            CapsuleDraftResult.Failure(CapsuleDraftFailure.NETWORK)
        }
    }

    private fun interpret(
        response: Response,
        request: CapsuleDraftRequest,
    ): CapsuleDraftResult {
        val body = response.body
        if (!isJson(response)) {
            return CapsuleDraftResult.Failure(
                CapsuleDraftFailure.INVALID_RESPONSE,
                response.code,
            )
        }
        val bytes = readBounded(body) ?: return CapsuleDraftResult.Failure(
            CapsuleDraftFailure.INVALID_RESPONSE,
            response.code,
        )
        val text = bytes.toString(Charsets.UTF_8)
        if (response.code != HTTP_CREATED && response.code != HTTP_OK) {
            return CapsuleDraftResult.Failure(
                reason = parseProblemCode(text) ?: CapsuleDraftFailure.HTTP,
                httpStatus = response.code,
            )
        }
        val dto = try {
            NetworkJson.decodeFromString<CapsuleDraftResponseDto>(text)
        } catch (_: SerializationException) {
            return CapsuleDraftResult.Failure(CapsuleDraftFailure.INVALID_RESPONSE, response.code)
        } catch (_: IllegalArgumentException) {
            return CapsuleDraftResult.Failure(CapsuleDraftFailure.INVALID_RESPONSE, response.code)
        }
        return try {
            CapsuleDraftResult.Success(mapResponse(dto, request), response.code)
        } catch (_: IllegalArgumentException) {
            CapsuleDraftResult.Failure(CapsuleDraftFailure.INVALID_RESPONSE, response.code)
        }
    }

    private fun mapResponse(
        dto: CapsuleDraftResponseDto,
        request: CapsuleDraftRequest,
    ): CapsuleDraft {
        require(dto.state == CapsuleDraftState.DRAFT.name)
        val capsuleId = CapsuleId.parseRest(dto.capsuleId)
        require(capsuleId == request.capsuleId)
        require(dto.draftExpiresAt.isNotEmpty())
        require(dto.blobs.size == request.blobs.size)
        val blobs = dto.blobs.mapIndexed { index, blob ->
            val blobId = BlobId.parseRest(blob.blobId)
            require(blobId == request.blobs[index].blobId)
            CapsuleDraftBlob(
                blobId = blobId,
                state = CapsuleDraftBlobState.valueOf(blob.state),
            )
        }
        return CapsuleDraft(
            capsuleId = capsuleId,
            state = CapsuleDraftState.DRAFT,
            draftExpiresAt = dto.draftExpiresAt,
            blobs = blobs,
        )
    }

    private fun parseProblemCode(text: String): CapsuleDraftFailure? {
        val code = try {
            NetworkJson.decodeFromString<ProblemResponseDto>(text).code
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return when (code) {
            "AUTH_INVALID" -> CapsuleDraftFailure.AUTH_INVALID
            "VALIDATION_FAILED" -> CapsuleDraftFailure.VALIDATION_FAILED
            "IDEMPOTENCY_CONFLICT" -> CapsuleDraftFailure.IDEMPOTENCY_CONFLICT
            "RECIPIENT_NOT_CONFIRMED" -> CapsuleDraftFailure.RECIPIENT_NOT_CONFIRMED
            "RECIPIENT_KEY_STALE" -> CapsuleDraftFailure.RECIPIENT_KEY_STALE
            "KEY_BUNDLE_NOT_FOUND" -> CapsuleDraftFailure.KEY_BUNDLE_NOT_FOUND
            "KEY_BUNDLE_INVALID" -> CapsuleDraftFailure.KEY_BUNDLE_INVALID
            "CAPSULE_STATE_INVALID" -> CapsuleDraftFailure.CAPSULE_STATE_INVALID
            "DRAFT_EXPIRED" -> CapsuleDraftFailure.DRAFT_EXPIRED
            "INTERNAL_UNAVAILABLE" -> CapsuleDraftFailure.INTERNAL_UNAVAILABLE
            "INTERNAL_ERROR" -> CapsuleDraftFailure.INTERNAL_ERROR
            else -> null
        }
    }

    private fun isJson(response: Response): Boolean {
        val contentType = response.body.contentType() ?: return false
        return contentType.type == "application" &&
            contentType.subtype in setOf("json", "problem+json")
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
        private const val PATH = "v1/capsules"
        private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
        private const val BEARER_PREFIX = "Bearer "
        private const val PROTOCOL_VERSION = 1
        private const val HTTP_CREATED = 201
        private const val HTTP_OK = 200
        private const val MAX_RESPONSE_BYTES = 64 * 1024L

        fun create(baseUrl: ApiBaseUrl): CapsuleDraftRepository =
            CapsuleDraftRepository(HttpClientFactory.create(), baseUrl)
    }
}
