package dev.hryshyn.remanence.core.data.network

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.coroutines.executeAsync
import dev.hryshyn.remanence.core.model.ArtifactLayoutValidation
import dev.hryshyn.remanence.core.model.ArtifactLayoutValidator
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.UserId

/** Opaque, route-only metadata for one ready incoming capsule. */
data class IncomingCapsule(
    val capsuleId: CapsuleId,
    val senderUserId: UserId,
    val recipientUserId: UserId,
    val senderKeyBundleId: KeyBundleId,
    val recipientKeyBundleId: KeyBundleId,
    val protocolVersion: Int,
    val readyAtEpochMs: Long,
    val signedStatementBytes: ByteArray,
    val signedStatementSha256: ByteArray,
    val publishSignatureBytes: ByteArray,
    val envelope: IncomingEnvelope,
    val blobs: List<IncomingBlobDeclaration>,
) {
    override fun toString(): String = "IncomingCapsule(<redacted>)"
}

/** Opaque HPKE envelope transport material; no plaintext is represented. */
data class IncomingEnvelope(
    val recipientKeyBundleId: KeyBundleId,
    val ciphertext: ByteArray,
    val ciphertextSha256: ByteArray,
) {
    override fun toString(): String = "IncomingEnvelope(<redacted>)"
}

/** One server-declared ciphertext blob; bytes are fetched by a later task. */
data class IncomingBlobDeclaration(
    val blobId: dev.hryshyn.remanence.core.model.BlobId,
    val kind: CapsuleArtifactKind,
    val ordinal: Int?,
    val ciphertextSize: Long,
    val ciphertextSha256: ByteArray,
) {
    override fun toString(): String = "IncomingBlobDeclaration(<redacted>)"
}

/** One bounded opaque cursor page returned by the incoming endpoint. */
data class IncomingCapsulePage(
    val items: List<IncomingCapsule>,
    val nextCursor: String?,
) {
    override fun toString(): String = "IncomingCapsulePage(<redacted>)"
}

enum class IncomingCapsuleFailure {
    NETWORK,
    RATE_LIMITED,
    HTTP,
    INVALID_RESPONSE,
    AUTH_INVALID,
    VALIDATION_FAILED,
    INTERNAL_ERROR,
}

sealed interface IncomingCapsuleResult {
    data class Success(
        val page: IncomingCapsulePage,
        val httpStatus: Int,
    ) : IncomingCapsuleResult

    data class Failure(
        val reason: IncomingCapsuleFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : IncomingCapsuleResult
}

@Serializable
private data class IncomingSignedStatementDto(
    val statement: String,
    @SerialName("statement_sha256") val statementSha256: String,
    val signature: String,
)

@Serializable
private data class IncomingEnvelopeDto(
    @SerialName("recipient_key_bundle_id") val recipientKeyBundleId: String,
    val ciphertext: String,
    @SerialName("ciphertext_size") val ciphertextSize: Int,
    @SerialName("ciphertext_sha256") val ciphertextSha256: String,
)

@Serializable
private data class IncomingBlobDto(
    @SerialName("blob_id") val blobId: String,
    val kind: String,
    val ordinal: Int?,
    @SerialName("ciphertext_size") val ciphertextSize: Int,
    @SerialName("ciphertext_sha256") val ciphertextSha256: String,
)

@Serializable
private data class IncomingCapsuleDto(
    @SerialName("capsule_id") val capsuleId: String,
    @SerialName("sender_user_id") val senderUserId: String,
    @SerialName("recipient_user_id") val recipientUserId: String,
    @SerialName("sender_key_bundle_id") val senderKeyBundleId: String,
    @SerialName("recipient_key_bundle_id") val recipientKeyBundleId: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("ready_at") val readyAt: String,
    @SerialName("signed_publish_statement") val signedPublishStatement: IncomingSignedStatementDto,
    @SerialName("recipient_envelope") val recipientEnvelope: IncomingEnvelopeDto,
    val blobs: List<IncomingBlobDto>,
)

@Serializable
private data class IncomingCapsulesResponseDto(
    val items: List<IncomingCapsuleDto>,
    @SerialName("next_cursor") val nextCursor: String?,
)

/**
 * Authenticated incoming page transport. The response is fully decoded and
 * transport-validated before it is returned to the local page committer.
 * Signature, HPKE, and control-payload verification remain A11 concerns.
 */
class IncomingCapsuleRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {

    suspend fun fetchPage(
        ownerUserId: UserId,
        cursor: String?,
        limit: Int = DEFAULT_LIMIT,
        accessToken: String? = null,
    ): IncomingCapsuleResult {
        if (limit !in 1..MAX_PAGE_SIZE || cursor?.isBlank() == true || cursor?.length ?: 0 > MAX_CURSOR_CHARS) {
            return IncomingCapsuleResult.Failure(
                reason = IncomingCapsuleFailure.VALIDATION_FAILED,
                retryable = false,
            )
        }
        if (accessToken != null && accessToken.isEmpty()) {
            return IncomingCapsuleResult.Failure(
                reason = IncomingCapsuleFailure.VALIDATION_FAILED,
                retryable = false,
            )
        }

        val url = baseUrl.resolve(PATH).newBuilder().apply {
            if (cursor != null) addQueryParameter("cursor", cursor)
            addQueryParameter("limit", limit.toString())
        }.build()
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", JSON_MEDIA_TYPE)
            .get()
        if (accessToken != null) {
            requestBuilder.header("Authorization", BEARER_PREFIX + accessToken)
        }

        return try {
            client.newCall(requestBuilder.build()).executeAsync().use { response ->
                interpret(response, ownerUserId, limit)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: java.io.IOException) {
            IncomingCapsuleResult.Failure(
                reason = IncomingCapsuleFailure.NETWORK,
                retryable = true,
            )
        }
    }

    private fun interpret(
        response: Response,
        ownerUserId: UserId,
        limit: Int,
    ): IncomingCapsuleResult {
        val status = response.code
        val body = response.body
        val bytes = readBounded(body) ?: return invalidResponse(status)
        val text = decodeUtf8(bytes) ?: return invalidResponse(status)

        if (status != HTTP_OK) {
            if (!isProblemJson(response)) {
                return IncomingCapsuleResult.Failure(
                    reason = IncomingCapsuleFailure.INVALID_RESPONSE,
                    httpStatus = status,
                    retryable = capsuleHttpFallbackIsRetryable(status),
                )
            }
            val problem = classifyCapsuleProblem(
                text = text,
                httpStatus = status,
                allowedCodes = ALLOWED_PROBLEM_CODES,
            )
            return IncomingCapsuleResult.Failure(
                reason = when (problem?.code) {
                    CODE_AUTH_INVALID -> IncomingCapsuleFailure.AUTH_INVALID
                    CODE_RATE_LIMITED -> IncomingCapsuleFailure.RATE_LIMITED
                    CODE_VALIDATION_FAILED -> IncomingCapsuleFailure.VALIDATION_FAILED
                    CODE_INTERNAL_ERROR -> IncomingCapsuleFailure.INTERNAL_ERROR
                    else -> IncomingCapsuleFailure.HTTP
                },
                httpStatus = status,
                retryable = problem?.retryable ?: capsuleHttpFallbackIsRetryable(status),
            )
        }

        if (!isJson(response)) return invalidResponse(status)
        val dto = try {
            NetworkJson.decodeFromString<IncomingCapsulesResponseDto>(text)
        } catch (_: SerializationException) {
            return invalidResponse(status)
        } catch (_: IllegalArgumentException) {
            return invalidResponse(status)
        }
        return try {
            IncomingCapsuleResult.Success(
                page = mapPage(dto, ownerUserId, limit),
                httpStatus = status,
            )
        } catch (_: IllegalArgumentException) {
            invalidResponse(status)
        } catch (_: ArithmeticException) {
            invalidResponse(status)
        }
    }

    private fun mapPage(
        dto: IncomingCapsulesResponseDto,
        ownerUserId: UserId,
        limit: Int,
    ): IncomingCapsulePage {
        require(dto.items.size <= limit)
        val seenCapsules = HashSet<CapsuleId>(dto.items.size)
        val seenBlobs = HashSet<dev.hryshyn.remanence.core.model.BlobId>()
        val items = dto.items.map { item ->
            val capsule = mapCapsule(item, ownerUserId, seenBlobs)
            require(seenCapsules.add(capsule.capsuleId))
            capsule
        }
        val nextCursor = dto.nextCursor?.also {
            require(it.isNotBlank() && it.length <= MAX_CURSOR_CHARS)
            require(items.isNotEmpty())
        }
        return IncomingCapsulePage(items = items, nextCursor = nextCursor)
    }

    private fun mapCapsule(
        dto: IncomingCapsuleDto,
        ownerUserId: UserId,
        seenBlobs: MutableSet<dev.hryshyn.remanence.core.model.BlobId>,
    ): IncomingCapsule {
        val capsuleId = CapsuleId.parseRest(dto.capsuleId)
        val senderUserId = UserId.parseRest(dto.senderUserId)
        val recipientUserId = UserId.parseRest(dto.recipientUserId)
        require(recipientUserId == ownerUserId)
        val senderKeyBundleId = KeyBundleId.parseRest(dto.senderKeyBundleId)
        val recipientKeyBundleId = KeyBundleId.parseRest(dto.recipientKeyBundleId)
        require(dto.protocolVersion == PROTOCOL_VERSION)

        val readyAtEpochMs = parseUtcEpochMs(dto.readyAt)
        val statement = decodeBase64Url(
            dto.signedPublishStatement.statement,
            maxBytes = MAX_PUBLISH_STATEMENT_BYTES,
        )
        val statementSha256 = decodeBase64Url(
            dto.signedPublishStatement.statementSha256,
            expectedBytes = SHA256_BYTES,
        )
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(statement), statementSha256))
        val signature = decodeBase64Url(
            dto.signedPublishStatement.signature,
            expectedBytes = PUBLISH_SIGNATURE_BYTES,
        )

        val envelope = dto.recipientEnvelope
        val envelopeKeyBundleId = KeyBundleId.parseRest(envelope.recipientKeyBundleId)
        require(envelopeKeyBundleId == recipientKeyBundleId)
        val envelopeCiphertext = decodeBase64Url(
            envelope.ciphertext,
            maxBytes = MAX_ENVELOPE_BYTES,
        )
        require(envelope.ciphertextSize in 1..MAX_ENVELOPE_BYTES)
        require(envelopeCiphertext.size == envelope.ciphertextSize)
        val envelopeSha256 = decodeBase64Url(
            envelope.ciphertextSha256,
            expectedBytes = SHA256_BYTES,
        )
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(envelopeCiphertext), envelopeSha256))

        val blobs = dto.blobs.map { blob ->
            val blobId = dev.hryshyn.remanence.core.model.BlobId.parseRest(blob.blobId)
            require(seenBlobs.add(blobId))
            val kind = try {
                CapsuleArtifactKind.valueOf(blob.kind)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("unsupported incoming blob kind")
            }
            val ordinal = when (kind) {
                CapsuleArtifactKind.PHOTO -> blob.ordinal.also {
                    require(it != null && it in PHOTO_ORDINAL_MIN..PHOTO_ORDINAL_MAX)
                }
                CapsuleArtifactKind.RECOGNITION_MANIFEST,
                CapsuleArtifactKind.CONTENT_MANIFEST,
                -> blob.ordinal.also { require(it == null) }
            }
            val maxBytes = when (kind) {
                CapsuleArtifactKind.RECOGNITION_MANIFEST -> MAX_RECOGNITION_BYTES
                CapsuleArtifactKind.CONTENT_MANIFEST -> MAX_CONTENT_BYTES
                CapsuleArtifactKind.PHOTO -> MAX_PHOTO_BYTES
            }
            require(blob.ciphertextSize in 1..maxBytes)
            val digest = decodeBase64Url(blob.ciphertextSha256, expectedBytes = SHA256_BYTES)
            IncomingBlobDeclaration(
                blobId = blobId,
                kind = kind,
                ordinal = ordinal,
                ciphertextSize = blob.ciphertextSize.toLong(),
                ciphertextSha256 = digest,
            )
        }
        require(blobs.size in MIN_BLOB_COUNT..MAX_BLOB_COUNT)
        val totalBytes = blobs.sumOf { it.ciphertextSize }
        require(totalBytes <= MAX_TOTAL_CIPHERTEXT_BYTES)
        val slots = blobs.map {
            dev.hryshyn.remanence.core.model.ArtifactSlot(
                blobId = it.blobId,
                kind = it.kind,
                ordinal = it.ordinal ?: NON_PHOTO_ORDINAL,
            )
        }
        require(ArtifactLayoutValidator.validate(slots) is ArtifactLayoutValidation.Valid)
        require(dev.hryshyn.remanence.core.model.CanonicalArtifactOrder.isCanonical(slots))

        return IncomingCapsule(
            capsuleId = capsuleId,
            senderUserId = senderUserId,
            recipientUserId = recipientUserId,
            senderKeyBundleId = senderKeyBundleId,
            recipientKeyBundleId = recipientKeyBundleId,
            protocolVersion = dto.protocolVersion,
            readyAtEpochMs = readyAtEpochMs,
            signedStatementBytes = statement,
            signedStatementSha256 = statementSha256,
            publishSignatureBytes = signature,
            envelope = IncomingEnvelope(
                recipientKeyBundleId = envelopeKeyBundleId,
                ciphertext = envelopeCiphertext,
                ciphertextSha256 = envelopeSha256,
            ),
            blobs = blobs,
        )
    }

    private fun decodeBase64Url(
        value: String,
        expectedBytes: Int? = null,
        maxBytes: Int? = expectedBytes,
    ): ByteArray {
        require(value.isNotEmpty())
        val maxEncodedChars = maxBytes?.let { ((it + 2) / 3) * 4 }
        require(maxEncodedChars == null || value.length <= maxEncodedChars)
        val decoded = Base64.getUrlDecoder().decode(value)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value)
        if (expectedBytes != null) require(decoded.size == expectedBytes)
        if (maxBytes != null) require(decoded.size <= maxBytes)
        return decoded
    }

    private fun parseUtcEpochMs(value: String): Long {
        val parsed = OffsetDateTime.parse(value)
        require(parsed.offset == ZoneOffset.UTC)
        return parsed.toInstant().toEpochMilli()
    }

    private fun invalidResponse(status: Int): IncomingCapsuleResult.Failure =
        IncomingCapsuleResult.Failure(
            reason = IncomingCapsuleFailure.INVALID_RESPONSE,
            httpStatus = status,
            retryable = false,
        )

    private fun isJson(response: Response): Boolean = response.body.contentType()?.let {
        it.type == "application" && it.subtype == "json"
    } == true

    private fun isProblemJson(response: Response): Boolean = response.body.contentType()?.let {
        it.type == "application" && it.subtype == "problem+json"
    } == true

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

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }

    companion object {
        fun create(client: OkHttpClient, baseUrl: ApiBaseUrl): IncomingCapsuleRepository =
            IncomingCapsuleRepository(client, baseUrl)

        private const val PATH = "v1/capsules/incoming"
        private const val JSON_MEDIA_TYPE = "application/json"
        private const val BEARER_PREFIX = "Bearer "
        private const val HTTP_OK = 200
        private const val PROTOCOL_VERSION = 1
        private const val DEFAULT_LIMIT = 50
        private const val MAX_PAGE_SIZE = 100
        private const val MAX_CURSOR_CHARS = 4096
        private const val MAX_RESPONSE_BYTES = 8L * 1024L * 1024L
        private const val MAX_PUBLISH_STATEMENT_BYTES = 4096
        private const val PUBLISH_SIGNATURE_BYTES = 69
        private const val SHA256_BYTES = 32
        private const val MAX_ENVELOPE_BYTES = 16_384
        private const val MAX_RECOGNITION_BYTES = 1_048_576
        private const val MAX_CONTENT_BYTES = 65_536
        private const val MAX_PHOTO_BYTES = 8_388_641
        private const val MAX_TOTAL_CIPHERTEXT_BYTES = 44_040_192L
        private const val MIN_BLOB_COUNT = 5
        private const val MAX_BLOB_COUNT = 7
        private const val PHOTO_ORDINAL_MIN = 0
        private const val PHOTO_ORDINAL_MAX = 4
        private const val NON_PHOTO_ORDINAL = -1
        private const val CODE_AUTH_INVALID = "AUTH_INVALID"
        private const val CODE_RATE_LIMITED = "RATE_LIMITED"
        private const val CODE_VALIDATION_FAILED = "VALIDATION_FAILED"
        private const val CODE_INTERNAL_ERROR = "INTERNAL_ERROR"
        private val ALLOWED_PROBLEM_CODES = setOf(
            CODE_AUTH_INVALID,
            CODE_RATE_LIMITED,
            CODE_VALIDATION_FAILED,
            CODE_INTERNAL_ERROR,
        )
    }
}
