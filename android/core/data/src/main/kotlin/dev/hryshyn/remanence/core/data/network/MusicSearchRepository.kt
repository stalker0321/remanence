package dev.hryshyn.remanence.core.data.network

import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import okio.Buffer

/**
 * S1 typed client for `GET /music/v1/search` (sample-backed catalog slice).
 *
 * Authenticated like the directory endpoints; the bearer attaches through
 * the shared authenticated client (or an explicit access token in tests).
 * Queries are client-validated to the server bounds (non-blank, at most
 * [QUERY_MAX_LENGTH] chars, [SEARCH_LIMIT_MIN]..[SEARCH_LIMIT_MAX] limit,
 * [SEARCH_OFFSET_MIN]..[SEARCH_OFFSET_MAX] offset) so invalid input fails
 * closed without a network call. Nothing sensitive is ever logged: result
 * types redact display strings and the query is never stored.
 */
data class MusicTrackHit(
    val id: String,
    val title: String,
    val artists: List<String>,
    val version: String?,
    val release: String?,
    val year: Int?,
    val durationMs: Long?,
    val artworkAvailable: Boolean,
) {
    override fun toString(): String =
        "MusicTrackHit(id=$id, artists=${artists.size}, version=${version != null})"
}

data class MusicSearchPage(
    val hits: List<MusicTrackHit>,
    val total: Int,
    val offset: Int,
    val limit: Int,
) {
    override fun toString(): String =
        "MusicSearchPage(hits=${hits.size}, total=$total, offset=$offset, limit=$limit)"
}

enum class MusicSearchFailure {
    AUTH_INVALID,
    VALIDATION_FAILED,
    RATE_LIMITED,
    UNAVAILABLE,
    NETWORK,
    HTTP,
    INVALID_RESPONSE,
    INTERNAL_ERROR,
}

sealed interface MusicSearchResult {
    data class Hits(val page: MusicSearchPage) : MusicSearchResult {
        override fun toString(): String = "MusicSearchResult.Hits($page)"
    }

    data class Failure(
        val reason: MusicSearchFailure,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    ) : MusicSearchResult
}

@Serializable
private data class MusicSearchResultItemDto(
    val id: String,
    val title: String,
    val artists: List<String>,
    val version: String? = null,
    val release: String? = null,
    val year: Int? = null,
    val durationMs: Long? = null,
    val artworkAvailable: Boolean = false,
)

@Serializable
private data class MusicSearchResponseDto(
    val results: List<MusicSearchResultItemDto>,
    val total: Int,
    val offset: Int,
)

class MusicSearchRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
    private val requestLeaseProvider: SessionRequestLeaseProvider? = null,
) {
    suspend fun search(
        query: String,
        limit: Int = SEARCH_LIMIT_DEFAULT,
        offset: Int = SEARCH_OFFSET_MIN,
        accessToken: String? = null,
    ): MusicSearchResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty() ||
            trimmed.length > QUERY_MAX_LENGTH ||
            limit !in SEARCH_LIMIT_MIN..SEARCH_LIMIT_MAX ||
            offset !in SEARCH_OFFSET_MIN..SEARCH_OFFSET_MAX
        ) {
            return MusicSearchResult.Failure(MusicSearchFailure.VALIDATION_FAILED, retryable = false)
        }
        val requestLease = requestLeaseProvider?.capture()
        if (requestLeaseProvider != null && requestLease == null) {
            return MusicSearchResult.Failure(MusicSearchFailure.NETWORK, retryable = true)
        }
        val url = baseUrl.resolve("music/v1/search").newBuilder()
            .addQueryParameter("q", trimmed)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
        if (accessToken != null) {
            requestBuilder.header(AUTHORIZATION_HEADER, BEARER_PREFIX + accessToken)
        }
        return try {
            val request = requestLeaseProvider?.tag(requestBuilder.build(), requestLease!!)
                ?: requestBuilder.build()
            client.newCall(request).executeAsync().use { response ->
                interpret(response, limit, offset)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            MusicSearchResult.Failure(reason = MusicSearchFailure.NETWORK, retryable = true)
        }
    }

    private fun interpret(response: Response, limit: Int, offset: Int): MusicSearchResult {
        val status = response.code
        val contentType = response.body.contentType()

        if (status in 200..299) {
            if (!contentType.isMusicJson()) {
                return invalidResponse(status, retryable = false)
            }
            val body = response.body.readMusicBoundedUtf8() ?: return invalidResponse(status, retryable = false)
            val dto = try {
                NetworkJson.decodeFromString<MusicSearchResponseDto>(body)
            } catch (_: SerializationException) {
                return invalidResponse(status, retryable = false)
            } catch (_: IllegalArgumentException) {
                return invalidResponse(status, retryable = false)
            }
            if (dto.total < 0 || dto.offset != offset || dto.results.size > limit) {
                return invalidResponse(status, retryable = false)
            }
            val hits = try {
                dto.results.map { item ->
                    MusicTrackHit(
                        id = UUID.fromString(item.id).toString(),
                        title = item.title.takeIf { it.isNotBlank() }
                            ?: throw IllegalArgumentException("blank title"),
                        artists = item.artists.takeIf { list ->
                            list.isNotEmpty() && list.all { it.isNotBlank() }
                        } ?: throw IllegalArgumentException("invalid artists"),
                        version = item.version,
                        release = item.release,
                        year = item.year,
                        durationMs = item.durationMs,
                        artworkAvailable = item.artworkAvailable,
                    )
                }
            } catch (_: IllegalArgumentException) {
                return invalidResponse(status, retryable = false)
            }
            return MusicSearchResult.Hits(MusicSearchPage(hits, dto.total, dto.offset, limit))
        }

        val body = if (contentType.isMusicProblemJson()) response.body.readMusicBoundedUtf8() else null
        val classified = body?.let {
            classifyCapsuleProblem(
                text = it,
                httpStatus = status,
                allowedCodes = ALLOWED_PROBLEM_CODES,
            )
        }
        if (classified != null) {
            return when (classified.code) {
                CODE_AUTH_INVALID -> failure(MusicSearchFailure.AUTH_INVALID, status, classified.retryable)
                CODE_VALIDATION_FAILED -> failure(
                    MusicSearchFailure.VALIDATION_FAILED,
                    status,
                    classified.retryable,
                )
                CODE_RATE_LIMITED -> failure(MusicSearchFailure.RATE_LIMITED, status, classified.retryable)
                CODE_INTERNAL_ERROR ->
                    if (status == HTTP_SERVICE_UNAVAILABLE) {
                        // The server emits unwired/overloaded search as 503
                        // with the generic internal code: never a misleading
                        // empty page, always an explicit retryable outage.
                        failure(MusicSearchFailure.UNAVAILABLE, status, classified.retryable)
                    } else {
                        failure(MusicSearchFailure.INTERNAL_ERROR, status, classified.retryable)
                    }
                else -> invalidResponse(status, retryable = capsuleHttpFallbackIsRetryable(status))
            }
        }

        return MusicSearchResult.Failure(
            reason = if (contentType.isMusicProblemJson()) {
                MusicSearchFailure.INVALID_RESPONSE
            } else {
                MusicSearchFailure.HTTP
            },
            httpStatus = status,
            retryable = capsuleHttpFallbackIsRetryable(status),
        )
    }

    private fun failure(
        reason: MusicSearchFailure,
        status: Int,
        retryable: Boolean,
    ): MusicSearchResult.Failure =
        MusicSearchResult.Failure(reason = reason, httpStatus = status, retryable = retryable)

    private fun invalidResponse(status: Int, retryable: Boolean): MusicSearchResult.Failure =
        MusicSearchResult.Failure(
            reason = MusicSearchFailure.INVALID_RESPONSE,
            httpStatus = status,
            retryable = retryable,
        )

    companion object {
        internal fun create(client: OkHttpClient, baseUrl: ApiBaseUrl): MusicSearchRepository =
            MusicSearchRepository(client, baseUrl)

        const val QUERY_MAX_LENGTH = 100
        const val SEARCH_LIMIT_DEFAULT = 10
        const val SEARCH_LIMIT_MIN = 1
        const val SEARCH_LIMIT_MAX = 20
        const val SEARCH_OFFSET_MIN = 0
        const val SEARCH_OFFSET_MAX = 200

        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
        private const val HTTP_SERVICE_UNAVAILABLE = 503
        private const val CODE_AUTH_INVALID = "AUTH_INVALID"
        private const val CODE_VALIDATION_FAILED = "VALIDATION_FAILED"
        private const val CODE_RATE_LIMITED = "RATE_LIMITED"
        private const val CODE_INTERNAL_ERROR = "INTERNAL_ERROR"
        private val ALLOWED_PROBLEM_CODES = setOf(
            CODE_AUTH_INVALID,
            CODE_VALIDATION_FAILED,
            CODE_RATE_LIMITED,
            CODE_INTERNAL_ERROR,
        )
    }
}

private fun okhttp3.MediaType?.isMusicJson(): Boolean =
    this?.type == "application" && this.subtype == "json"

private fun okhttp3.MediaType?.isMusicProblemJson(): Boolean =
    this?.type == "application" && this.subtype == "problem+json"

private fun okhttp3.ResponseBody.readMusicBoundedUtf8(): String? {
    if (contentLength() > MUSIC_MAX_BODY_BYTES) return null
    val source = source()
    val buffer = Buffer()
    var totalBytes = 0L
    while (true) {
        val read = source.read(buffer, MUSIC_MAX_BODY_BYTES + 1L - totalBytes)
        if (read == -1L) break
        totalBytes += read
        if (totalBytes > MUSIC_MAX_BODY_BYTES) return null
    }
    return buffer.readByteArray().toString(Charsets.UTF_8)
}

private const val MUSIC_MAX_BODY_BYTES = 64 * 1024
