package dev.hryshyn.remanence.core.data.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.coroutines.executeAsync
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId

/** One redacted recipient revocation event from the authenticated feed. */
data class IncomingTombstone(
    val capsuleId: CapsuleId,
    val revokedAtEpochMs: Long,
) {
    override fun toString(): String = "IncomingTombstone(<redacted>)"
}

/** One bounded opaque-cursor tombstone page. */
data class IncomingTombstonePage(
    val items: List<IncomingTombstone>,
    val hasMore: Boolean,
    val nextCursor: String?,
) {
    override fun toString(): String = "IncomingTombstonePage(<redacted>)"
}

enum class IncomingTombstoneFailure {
    NETWORK,
    RATE_LIMITED,
    HTTP,
    INVALID_RESPONSE,
    AUTH_INVALID,
    VALIDATION_FAILED,
    INTERNAL_ERROR,
}

sealed interface IncomingTombstoneResult {
    data class Success(val page: IncomingTombstonePage, val httpStatus: Int) : IncomingTombstoneResult

    data class Failure(
        val reason: IncomingTombstoneFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : IncomingTombstoneResult
}

/** Narrow seam used by the local transactional tombstone application. */
fun interface IncomingTombstoneFeed {
    suspend fun fetchPage(
        ownerUserId: UserId,
        cursor: String?,
        limit: Int,
        accessToken: String,
    ): IncomingTombstoneResult
}

/** Authenticated transport for the recipient's redacted tombstone feed. */
class IncomingTombstoneRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) : IncomingTombstoneFeed {

    override suspend fun fetchPage(
        ownerUserId: UserId,
        cursor: String?,
        limit: Int,
        accessToken: String,
    ): IncomingTombstoneResult {
        if (limit !in 1..MAX_PAGE_SIZE || cursor?.isBlank() == true ||
            cursor?.length ?: 0 > MAX_CURSOR_CHARS || accessToken.isBlank()
        ) {
            return IncomingTombstoneResult.Failure(
                reason = IncomingTombstoneFailure.VALIDATION_FAILED,
                retryable = false,
            )
        }
        val url = baseUrl.resolve(PATH).newBuilder().apply {
            if (cursor != null) addQueryParameter("since", cursor)
            addQueryParameter("limit", limit.toString())
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", JSON_MEDIA_TYPE)
            .header("Authorization", BEARER_PREFIX + accessToken)
            .get()
            .build()

        return try {
            client.newCall(request).executeAsync().use { response ->
                interpret(response, cursor, limit)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            IncomingTombstoneResult.Failure(
                reason = IncomingTombstoneFailure.NETWORK,
                retryable = true,
            )
        }
    }

    private fun interpret(
        response: Response,
        requestedCursor: String?,
        limit: Int,
    ): IncomingTombstoneResult {
        val status = response.code
        val bytes = readBounded(response.body) ?: return invalidResponse(status)
        val text = decodeUtf8(bytes) ?: return invalidResponse(status)
        if (status != HTTP_OK) {
            if (!isProblemJson(response)) return invalidResponse(status)
            val problem = classifyCapsuleProblem(
                text = text,
                httpStatus = status,
                allowedCodes = ALLOWED_PROBLEM_CODES,
            )
            return IncomingTombstoneResult.Failure(
                reason = when (problem?.code) {
                    CODE_AUTH_INVALID -> IncomingTombstoneFailure.AUTH_INVALID
                    CODE_RATE_LIMITED -> IncomingTombstoneFailure.RATE_LIMITED
                    CODE_VALIDATION_FAILED -> IncomingTombstoneFailure.VALIDATION_FAILED
                    CODE_INTERNAL_ERROR -> IncomingTombstoneFailure.INTERNAL_ERROR
                    else -> IncomingTombstoneFailure.HTTP
                },
                httpStatus = status,
                retryable = problem?.retryable ?: capsuleHttpFallbackIsRetryable(status),
            )
        }
        if (!isJson(response)) return invalidResponse(status)
        val dto = try {
            val root = NetworkJson.parseToJsonElement(text)
            require(root is JsonObject)
            val hasMore = root["has_more"]
            require(hasMore is JsonPrimitive && !hasMore.isString)
            NetworkJson.decodeFromJsonElement<IncomingTombstonesResponseDto>(root)
        } catch (_: SerializationException) {
            return invalidResponse(status)
        } catch (_: IllegalArgumentException) {
            return invalidResponse(status)
        }
        return try {
            val items = dto.items.map { item ->
                val capsuleId = CapsuleId.parseRest(item.capsuleId)
                require(item.revokedAtEpochMs() >= 0L)
                IncomingTombstone(capsuleId, item.revokedAtEpochMs())
            }
            require(items.size <= limit)
            require(items.map { it.capsuleId }.toSet().size == items.size)
            val nextCursor = if (items.isNotEmpty()) {
                requireNotNull(dto.nextCursor).also {
                    require(it.isNotBlank() && it.length <= MAX_CURSOR_CHARS)
                }
            } else {
                require(dto.nextCursor == requestedCursor)
                dto.nextCursor?.also { require(it.isNotBlank() && it.length <= MAX_CURSOR_CHARS) }
            }
            require(!dto.hasMore || items.isNotEmpty())
            require(!dto.hasMore || nextCursor != null)
            IncomingTombstoneResult.Success(
                IncomingTombstonePage(items, dto.hasMore, nextCursor),
                status,
            )
        } catch (_: IllegalArgumentException) {
            invalidResponse(status)
        } catch (_: java.time.DateTimeException) {
            invalidResponse(status)
        } catch (_: ArithmeticException) {
            invalidResponse(status)
        }
    }

    private fun TombstoneDto.revokedAtEpochMs(): Long {
        val parsed = OffsetDateTime.parse(revokedAt)
        require(parsed.offset == ZoneOffset.UTC)
        return parsed.toInstant().toEpochMilli()
    }

    private fun invalidResponse(status: Int) = IncomingTombstoneResult.Failure(
        reason = IncomingTombstoneFailure.INVALID_RESPONSE,
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
        fun create(client: OkHttpClient, baseUrl: ApiBaseUrl): IncomingTombstoneRepository =
            IncomingTombstoneRepository(client, baseUrl)

        private const val PATH = "v1/incoming/tombstones"
        private const val JSON_MEDIA_TYPE = "application/json"
        private const val BEARER_PREFIX = "Bearer "
        private const val HTTP_OK = 200
        private const val MAX_PAGE_SIZE = 100
        private const val MAX_CURSOR_CHARS = 4096
        private const val MAX_RESPONSE_BYTES = 256 * 1024L
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

@Serializable
private data class TombstoneDto(
    @SerialName("capsule_id") val capsuleId: String,
    @SerialName("revoked_at") val revokedAt: String,
)

@Serializable
private data class IncomingTombstonesResponseDto(
    val items: List<TombstoneDto>,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("next_cursor") val nextCursor: String?,
)
