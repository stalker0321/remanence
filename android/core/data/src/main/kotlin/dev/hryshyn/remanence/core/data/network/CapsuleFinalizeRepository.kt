package dev.hryshyn.remanence.core.data.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
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
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.ProtocolV1Limits

/** One fully uploaded draft's signed statement and recipient envelope. */
class CapsuleFinalizeRequest(
    val capsuleId: CapsuleId,
    statement: ByteArray,
    signature: ByteArray,
    val senderKeyBundleId: KeyBundleId,
    val recipientKeyBundleId: KeyBundleId,
    recipientEnvelopeCiphertext: ByteArray,
    recipientEnvelopeCiphertextSha256: ByteArray,
) {
    private val statementSnapshot = statement.copyOf()
    private val signatureSnapshot = signature.copyOf()
    private val recipientEnvelopeCiphertextSnapshot = recipientEnvelopeCiphertext.copyOf()
    private val recipientEnvelopeCiphertextSha256Snapshot = recipientEnvelopeCiphertextSha256.copyOf()

    /** Returns a copy so callers cannot alter a later finalize request. */
    val statement: ByteArray
        get() = statementSnapshot.copyOf()

    /** Returns a copy so callers cannot alter the signed request. */
    val signature: ByteArray
        get() = signatureSnapshot.copyOf()

    /** Returns a copy so callers cannot alter the recipient envelope. */
    val recipientEnvelopeCiphertext: ByteArray
        get() = recipientEnvelopeCiphertextSnapshot.copyOf()

    /** Returns a copy so callers cannot alter the envelope's transport hash. */
    val recipientEnvelopeCiphertextSha256: ByteArray
        get() = recipientEnvelopeCiphertextSha256Snapshot.copyOf()

    init {
        require(statementSnapshot.isNotEmpty() && statementSnapshot.size <= MAX_STATEMENT_BYTES) {
            "publish statement size is invalid"
        }
        require(signatureSnapshot.size == PUBLISH_SIGNATURE_BYTES) {
            "publish signature size is invalid"
        }
        require(
            recipientEnvelopeCiphertextSnapshot.isNotEmpty() &&
                recipientEnvelopeCiphertextSnapshot.size <= ProtocolV1Limits.RECIPIENT_ENVELOPE_MAX_CIPHERTEXT_BYTES,
        ) { "recipient envelope size is invalid" }
        require(recipientEnvelopeCiphertextSha256Snapshot.size == SHA256_BYTES) {
            "recipient envelope hash must be SHA-256"
        }
        require(
            MessageDigest.isEqual(
                MessageDigest.getInstance("SHA-256").digest(recipientEnvelopeCiphertextSnapshot),
                recipientEnvelopeCiphertextSha256Snapshot,
            ),
        ) { "recipient envelope hash does not match ciphertext" }
    }

    override fun toString(): String = "CapsuleFinalizeRequest(<redacted>)"

    private companion object {
        const val MAX_STATEMENT_BYTES = 4096
        const val PUBLISH_SIGNATURE_BYTES = 69
        const val SHA256_BYTES = 32
    }
}

enum class CapsuleFinalizeState {
    READY,
}

data class CapsuleFinalize(
    val capsuleId: CapsuleId,
    val state: CapsuleFinalizeState,
    val readyAt: String,
)

enum class CapsuleFinalizeFailure {
    NETWORK,
    RATE_LIMITED,
    HTTP,
    INVALID_RESPONSE,
    AUTH_INVALID,
    VALIDATION_FAILED,
    CAPSULE_NOT_FOUND,
    CAPSULE_STATE_INVALID,
    DRAFT_EXPIRED,
    KEY_BUNDLE_NOT_FOUND,
    KEY_BUNDLE_INVALID,
    KEY_BUNDLE_REVOKED,
    RECIPIENT_KEY_STALE,
    STATEMENT_INVALID,
    SIGNATURE_INVALID,
    ENVELOPE_INVALID,
    FINALIZE_CONFLICT,
    INTERNAL_UNAVAILABLE,
    INTERNAL_ERROR,
}

sealed interface CapsuleFinalizeResult {
    data class Success(
        val finalize: CapsuleFinalize,
        val httpStatus: Int,
    ) : CapsuleFinalizeResult

    data class Failure(
        val reason: CapsuleFinalizeFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : CapsuleFinalizeResult
}

@Serializable
private data class CapsuleFinalizeSignedPublishStatementDto(
    val statement: String,
    val signature: String,
    @SerialName("sender_key_bundle_id") val senderKeyBundleId: String,
)

@Serializable
private data class CapsuleFinalizeRecipientEnvelopeDto(
    @SerialName("recipient_key_bundle_id") val recipientKeyBundleId: String,
    val ciphertext: String,
    @SerialName("ciphertext_size") val ciphertextSize: Int,
    @SerialName("ciphertext_sha256") val ciphertextSha256: String,
)

@Serializable
private data class CapsuleFinalizeRequestDto(
    @SerialName("signed_publish_statement") val signedPublishStatement: CapsuleFinalizeSignedPublishStatementDto,
    @SerialName("recipient_envelope") val recipientEnvelope: CapsuleFinalizeRecipientEnvelopeDto,
)

@Serializable
private data class CapsuleFinalizeResponseDto(
    @SerialName("capsule_id") val capsuleId: String,
    val state: String,
    @SerialName("ready_at") val readyAt: String,
)

/** Authenticated finalize call for one signed, fully uploaded draft. */
class CapsuleFinalizeRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {

    suspend fun finalize(
        request: CapsuleFinalizeRequest,
        accessToken: String,
    ): CapsuleFinalizeResult {
        val statement = request.statement
        val signature = request.signature
        val envelopeCiphertext = request.recipientEnvelopeCiphertext
        val envelopeSha256 = request.recipientEnvelopeCiphertextSha256
        val wireRequest = CapsuleFinalizeRequestDto(
            signedPublishStatement = CapsuleFinalizeSignedPublishStatementDto(
                statement = base64url(statement),
                signature = base64url(signature),
                senderKeyBundleId = request.senderKeyBundleId.toRestString(),
            ),
            recipientEnvelope = CapsuleFinalizeRecipientEnvelopeDto(
                recipientKeyBundleId = request.recipientKeyBundleId.toRestString(),
                ciphertext = base64url(envelopeCiphertext),
                ciphertextSize = envelopeCiphertext.size,
                ciphertextSha256 = base64url(envelopeSha256),
            ),
        )
        val requestBody = NetworkJson.encodeToString(wireRequest)
            .toRequestBody(JSON_MEDIA_TYPE.toMediaTypeOrNull())
        val httpRequest = Request.Builder()
            .url(baseUrl.resolve("v1/capsules/${request.capsuleId.toRestString()}/finalize"))
            .header("Accept", JSON_MEDIA_TYPE)
            .header("Authorization", BEARER_PREFIX + accessToken)
            .post(requestBody)
            .build()

        return try {
            client.newCall(httpRequest).executeAsync().use { response ->
                interpret(response, request)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            CapsuleFinalizeResult.Failure(CapsuleFinalizeFailure.NETWORK, retryable = true)
        }
    }

    private fun interpret(
        response: Response,
        request: CapsuleFinalizeRequest,
    ): CapsuleFinalizeResult {
        val bytes = readBounded(response.body)
            ?: return CapsuleFinalizeResult.Failure(
                CapsuleFinalizeFailure.INVALID_RESPONSE,
                response.code,
                retryable = capsuleHttpFallbackIsRetryable(response.code),
            )
        if (response.code != HTTP_CREATED && response.code != HTTP_OK) {
            if (!isJson(response, PROBLEM_JSON_SUBTYPE)) {
                return CapsuleFinalizeResult.Failure(
                    CapsuleFinalizeFailure.INVALID_RESPONSE,
                    response.code,
                    retryable = capsuleHttpFallbackIsRetryable(response.code),
                )
            }
            val problem = parseProblem(bytes.toString(Charsets.UTF_8), response.code)
            return CapsuleFinalizeResult.Failure(
                reason = problem?.let { mapProblemCode(it.code) } ?: CapsuleFinalizeFailure.HTTP,
                httpStatus = response.code,
                retryable = problem?.retryable ?: capsuleHttpFallbackIsRetryable(response.code),
            )
        }
        if (!isJson(response, JSON_SUBTYPE)) {
            return CapsuleFinalizeResult.Failure(
                CapsuleFinalizeFailure.INVALID_RESPONSE,
                response.code,
                retryable = false,
            )
        }
        val dto = try {
            NetworkJson.decodeFromString<CapsuleFinalizeResponseDto>(bytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return CapsuleFinalizeResult.Failure(CapsuleFinalizeFailure.INVALID_RESPONSE, response.code, retryable = false)
        } catch (_: IllegalArgumentException) {
            return CapsuleFinalizeResult.Failure(CapsuleFinalizeFailure.INVALID_RESPONSE, response.code, retryable = false)
        }
        return try {
            CapsuleFinalizeResult.Success(mapResponse(dto, request), response.code)
        } catch (_: IllegalArgumentException) {
            CapsuleFinalizeResult.Failure(CapsuleFinalizeFailure.INVALID_RESPONSE, response.code, retryable = false)
        }
    }

    private fun mapResponse(
        dto: CapsuleFinalizeResponseDto,
        request: CapsuleFinalizeRequest,
    ): CapsuleFinalize {
        require(dto.state == CapsuleFinalizeState.READY.name)
        val capsuleId = CapsuleId.parseRest(dto.capsuleId)
        require(capsuleId == request.capsuleId)
        require(dto.readyAt.isNotEmpty())
        return CapsuleFinalize(
            capsuleId = capsuleId,
            state = CapsuleFinalizeState.READY,
            readyAt = dto.readyAt,
        )
    }

    private fun parseProblem(text: String, httpStatus: Int): ClassifiedCapsuleProblem? =
        classifyCapsuleProblem(text, httpStatus, FINALIZE_PROBLEM_CODES)

    private fun mapProblemCode(code: String): CapsuleFinalizeFailure = when (code) {
        "AUTH_INVALID" -> CapsuleFinalizeFailure.AUTH_INVALID
        "RATE_LIMITED" -> CapsuleFinalizeFailure.RATE_LIMITED
        "VALIDATION_FAILED" -> CapsuleFinalizeFailure.VALIDATION_FAILED
        "CAPSULE_NOT_FOUND" -> CapsuleFinalizeFailure.CAPSULE_NOT_FOUND
        "CAPSULE_STATE_INVALID" -> CapsuleFinalizeFailure.CAPSULE_STATE_INVALID
        "DRAFT_EXPIRED" -> CapsuleFinalizeFailure.DRAFT_EXPIRED
        "KEY_BUNDLE_NOT_FOUND" -> CapsuleFinalizeFailure.KEY_BUNDLE_NOT_FOUND
        "KEY_BUNDLE_INVALID" -> CapsuleFinalizeFailure.KEY_BUNDLE_INVALID
        "KEY_BUNDLE_REVOKED" -> CapsuleFinalizeFailure.KEY_BUNDLE_REVOKED
        "RECIPIENT_KEY_STALE" -> CapsuleFinalizeFailure.RECIPIENT_KEY_STALE
        "STATEMENT_INVALID" -> CapsuleFinalizeFailure.STATEMENT_INVALID
        "SIGNATURE_INVALID" -> CapsuleFinalizeFailure.SIGNATURE_INVALID
        "ENVELOPE_INVALID" -> CapsuleFinalizeFailure.ENVELOPE_INVALID
        "FINALIZE_CONFLICT" -> CapsuleFinalizeFailure.FINALIZE_CONFLICT
        "INTERNAL_ERROR" -> CapsuleFinalizeFailure.INTERNAL_ERROR
        else -> CapsuleFinalizeFailure.HTTP
    }

    private fun isJson(response: Response, expectedSubtype: String): Boolean {
        val contentType = response.body.contentType() ?: return false
        return contentType.type == "application" && contentType.subtype == expectedSubtype
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

    private fun base64url(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
        private const val JSON_SUBTYPE = "json"
        private const val PROBLEM_JSON_SUBTYPE = "problem+json"
        private const val HTTP_CREATED = 201
        private const val HTTP_OK = 200
        private const val MAX_RESPONSE_BYTES = 64 * 1024L

        private val FINALIZE_PROBLEM_CODES = setOf(
            "AUTH_INVALID",
            "RATE_LIMITED",
            "VALIDATION_FAILED",
            "CAPSULE_NOT_FOUND",
            "CAPSULE_STATE_INVALID",
            "DRAFT_EXPIRED",
            "KEY_BUNDLE_NOT_FOUND",
            "KEY_BUNDLE_INVALID",
            "KEY_BUNDLE_REVOKED",
            "RECIPIENT_KEY_STALE",
            "STATEMENT_INVALID",
            "SIGNATURE_INVALID",
            "ENVELOPE_INVALID",
            "FINALIZE_CONFLICT",
            "INTERNAL_ERROR",
        )

        fun create(baseUrl: ApiBaseUrl): CapsuleFinalizeRepository =
            CapsuleFinalizeRepository(HttpClientFactory.create(), baseUrl)
    }
}
