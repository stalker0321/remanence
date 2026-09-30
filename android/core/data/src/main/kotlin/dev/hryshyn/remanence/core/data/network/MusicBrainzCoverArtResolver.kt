package dev.hryshyn.remanence.core.data.network

import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import dev.hryshyn.remanence.core.model.GeneratorExpression

/**
 * Sender-side cover descriptor resolution (T062).
 *
 * The backend exposes only the canonical MusicBrainz *recording* MBID; it never
 * fetches, proxies, caches or stores artwork bytes or artwork URLs (V5
 * invariant I1). This client is the missing other half: an **unauthenticated,
 * direct** client that turns a recording MBID into deterministic
 * release-group cover metadata, fetches the Cover Art Archive bytes on-device,
 * and hands back a descriptor ready to seal as
 * [GeneratorExpression.ArtworkRef].
 *
 * Rules enforced here (V5 artwork contract T2–T7):
 *  - every redirect hop is re-validated against a documented-host policy and a
 *    stable https/no-query/no-userinfo/no-fragment shape — never trusted;
 *  - one bounded GET per URL, body size capped *before* decode;
 *  - only `image/jpeg`, `image/png`, `image/webp`, decoded bounds in
 *    1..4096 px;
 *  - SHA-256 is computed over the exact fetched bytes;
 *  - no credential is ever attached, and no URL, digest or pixel value is
 *    logged anywhere in this file.
 *
 * Any failure — no MBID, no release-group, no cover, policy rejection, HTTP
 * error, oversize, bad media type, undecodable bounds — resolves to
 * [CoverArtResolution.Unavailable] so the caller degrades to text mode without
 * blocking music.
 */

/** Decoded pixel size, or `null` when the bytes are not a readable image. */
data class ImageSize(val width: Int, val height: Int)

/** Bounds-only image probe; production uses the platform decoder. */
fun interface ImageBoundsReader {
    fun decodeBounds(bytes: ByteArray): ImageSize?
}

/** `BitmapFactory` bounds probe: header parse only, never allocates pixels. */
object BitmapImageBoundsReader : ImageBoundsReader {
    override fun decodeBounds(bytes: ByteArray): ImageSize? = try {
        val options = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            ImageSize(options.outWidth, options.outHeight)
        } else {
            null
        }
    } catch (_: RuntimeException) {
        null
    }
}

/**
 * Fetch-time host policy for the sender artwork path.
 *
 * Suffix matching is deliberate: Cover Art Archive answers with a redirect to
 * `archive.org`, which in turn redirects to a per-request node such as
 * `dn721806.ca.archive.org` or `ia801506.us.archive.org`. Captured protocol
 * evidence (tasks/T062/evidence) shows those node hosts *alternate between
 * requests*, which is exactly why [isCanonicalDownloadHost] only ever accepts
 * the bare `archive.org` download host as a sealable hop.
 */
interface CoverArtHostPolicy {
    /** True when a redirect hop to [url] may be followed. */
    fun allowsRedirect(url: HttpUrl): Boolean

    /** True when [url] is the stable, content-addressing download hop. */
    fun isCanonicalDownloadHost(url: HttpUrl): Boolean
}

/** Production policy: documented MusicBrainz / CAA / archive.org hosts only. */
object MusicBrainzCoverArtHostPolicy : CoverArtHostPolicy {
    /** Documented host suffixes (exact host or a sub-domain of one). */
    val HOST_SUFFIXES: List<String> = listOf(
        "musicbrainz.org",
        "coverartarchive.org",
        "archive.org",
    )

    override fun allowsRedirect(url: HttpUrl): Boolean {
        if (url.scheme != "https") return false
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        val host = url.host.lowercase()
        return HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
    }

    override fun isCanonicalDownloadHost(url: HttpUrl): Boolean =
        url.scheme == "https" && url.host.equals("archive.org", ignoreCase = true) &&
            url.encodedPath.startsWith("/download/")
}

/**
 * Rule A4 / T3 URL shape for anything that can end up sealed: absolute
 * https, no userinfo, no query, no fragment, no explicit port other than
 * 443, length within [GeneratorExpression.ARTWORK_URL_MAX].
 */
fun hasStableSealableShape(url: HttpUrl): Boolean {
    if (url.scheme != "https") return false
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
    if (url.query != null || url.encodedQuery != null) return false
    if (url.fragment != null || url.encodedFragment != null) return false
    val full = url.toString()
    val authority = full.substringAfter("://", "").substringBefore("/")
    val explicitPort = authority.substringAfter(':', "")
    if (explicitPort.isNotEmpty() && explicitPort != "443") return false
    return full.length <= GeneratorExpression.ARTWORK_URL_MAX
}

sealed interface CoverArtResolution {
    data class Resolved(val artwork: GeneratorExpression.ArtworkRef) : CoverArtResolution

    data class Unavailable(val reason: Reason) : CoverArtResolution

    enum class Reason {
        /** No recording MBID on the hit (catalog has no stored id). */
        NO_RECORDING_ID,
        /** MusicBrainz returned no usable release-group. */
        NO_RELEASE_GROUP,
        /** Cover Art Archive reports no front image for that release-group. */
        NO_COVER,
        /** Non-2xx from the metadata or cover endpoint. */
        HTTP_ERROR,
        /** A hop left the documented host/scheme/shape allow-list. */
        HOST_NOT_ALLOWED,
        /** Body exceeded the bounded size. */
        OVERSIZED,
        /** Media type outside image/jpeg|png|webp. */
        UNSUPPORTED_MEDIA_TYPE,
        /** Bytes did not decode to in-range bounds. */
        DECODE_FAILED,
        /** No stable query-free URL could be selected to seal. */
        STABLE_URL_UNAVAILABLE,
        /** Metadata body was not a usable MusicBrainz recording document. */
        INVALID_METADATA,
        /** Transport failure (DNS/socket/timeout). */
        NETWORK,
    }
}

@Serializable
internal data class RecordingLookupDto(
    val releases: List<ReleaseDto> = emptyList(),
)

@Serializable
internal data class ReleaseDto(
    val date: String? = null,
    @SerialName("release-group") val releaseGroup: ReleaseGroupDto? = null,
)

@Serializable
internal data class ReleaseGroupDto(val id: String = "")

/**
 * Unauthenticated direct MusicBrainz → Cover Art Archive descriptor resolver.
 *
 * Only the MusicBrainz *metadata* request carries a query string
 * (`?inc=…&fmt=json`); that URL is never sealed. Every redirect target and the
 * sealed URL must be query-free, which is what keeps the descriptor canonical
 * under rule T4 (signed/expiring links are unrepresentable).
 */
class MusicBrainzCoverArtResolver(
    private val client: OkHttpClient,
    private val boundsReader: ImageBoundsReader = BitmapImageBoundsReader,
    private val musicBrainzBaseUrl: HttpUrl = MUSICBRAINZ_BASE_URL,
    private val coverArtBaseUrl: HttpUrl = COVER_ART_BASE_URL,
    private val hostPolicy: CoverArtHostPolicy = MusicBrainzCoverArtHostPolicy,
    /**
     * Rule A4/T3 shape check applied to the URL that will be sealed. The
     * production default is [hasStableSealableShape] (https, no query, no
     * fragment, no userinfo, no non-443 port, bounded), which has its own
     * dedicated tests; it is injectable only so hermetic tests can serve
     * loopback `http://127.0.0.1` without weakening the shipped default.
     */
    private val sealShapeCheck: (HttpUrl) -> Boolean = ::hasStableSealableShape,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Resolve [recordingMbid] to a sealable descriptor.
     *
     * Provider and protocol outcomes never throw: only coroutine cancellation
     * propagates, so the selection path degrades to text mode instead of
     * failing the music flow.
     */
    suspend fun resolve(recordingMbid: String?): CoverArtResolution {
        val mbid = recordingMbid?.trim()?.takeIf { it.isNotEmpty() }
            ?: return unavailable(CoverArtResolution.Reason.NO_RECORDING_ID)
        if (!isCanonicalUuid(mbid)) {
            return unavailable(CoverArtResolution.Reason.NO_RECORDING_ID)
        }

        val metadataBase = musicBrainzBaseUrl.resolve("ws/2/recording/$mbid")
            ?: return unavailable(CoverArtResolution.Reason.INVALID_METADATA)
        val metadataUrl = metadataBase.newBuilder()
            .addQueryParameter("inc", "releases+release-groups")
            .addQueryParameter("fmt", "json")
            .build()

        val metadata = when (val outcome = fetch(metadataUrl, MAX_METADATA_BYTES)) {
            is CoverFetch.Policy -> return unavailable(outcome.reason)
            is CoverFetch.Transport -> return unavailable(CoverArtResolution.Reason.NETWORK)
            is CoverFetch.Redirect -> return unavailable(CoverArtResolution.Reason.HOST_NOT_ALLOWED)
            is CoverFetch.Body -> outcome
        }
        when {
            metadata.status == 404 ->
                return unavailable(CoverArtResolution.Reason.NO_RELEASE_GROUP)
            metadata.status !in 200..299 ->
                return unavailable(CoverArtResolution.Reason.HTTP_ERROR)
            metadata.bytesRead > MAX_METADATA_BYTES ->
                return unavailable(CoverArtResolution.Reason.OVERSIZED)
            metadata.body == null ->
                return unavailable(CoverArtResolution.Reason.INVALID_METADATA)
        }
        val recording = try {
            json.decodeFromString<RecordingLookupDto>(
                metadata.body.toString(Charsets.UTF_8)
            )
        } catch (_: Exception) {
            return unavailable(CoverArtResolution.Reason.INVALID_METADATA)
        }
        val releaseGroupId = pickReleaseGroupId(recording)
            ?: return unavailable(CoverArtResolution.Reason.NO_RELEASE_GROUP)

        val coverUrl = coverArtBaseUrl.resolve("release-group/$releaseGroupId/$COVER_THUMB")
            ?: return unavailable(CoverArtResolution.Reason.NO_COVER)

        val cover = when (val outcome = fetch(coverUrl, MAX_IMAGE_BYTES)) {
            is CoverFetch.Policy -> return unavailable(outcome.reason)
            is CoverFetch.Transport -> return unavailable(CoverArtResolution.Reason.NETWORK)
            is CoverFetch.Redirect -> return unavailable(CoverArtResolution.Reason.HOST_NOT_ALLOWED)
            is CoverFetch.Body -> outcome
        }
        when {
            cover.status == 404 -> return unavailable(CoverArtResolution.Reason.NO_COVER)
            cover.status !in 200..299 ->
                return unavailable(CoverArtResolution.Reason.HTTP_ERROR)
            cover.bytesRead > MAX_IMAGE_BYTES ->
                return unavailable(CoverArtResolution.Reason.OVERSIZED)
            cover.body == null -> return unavailable(CoverArtResolution.Reason.HTTP_ERROR)
        }
        val bytes = cover.body
        val mimeType = normalizeImageMediaType(cover.contentType)
            ?: return unavailable(CoverArtResolution.Reason.UNSUPPORTED_MEDIA_TYPE)
        val size = boundsReader.decodeBounds(bytes)
            ?: return unavailable(CoverArtResolution.Reason.DECODE_FAILED)
        if (size.width !in 1..GeneratorExpression.ARTWORK_DIM_MAX ||
            size.height !in 1..GeneratorExpression.ARTWORK_DIM_MAX
        ) {
            return unavailable(CoverArtResolution.Reason.DECODE_FAILED)
        }

        val sealedUrl = stableSealableUrl(cover.hops, hostPolicy)
            ?: return unavailable(CoverArtResolution.Reason.STABLE_URL_UNAVAILABLE)
        if (!sealShapeCheck(sealedUrl)) {
            return unavailable(CoverArtResolution.Reason.STABLE_URL_UNAVAILABLE)
        }

        return CoverArtResolution.Resolved(
            GeneratorExpression.ArtworkRef(
                provider = COVER_ART_PROVIDER,
                url = sealedUrl.toString(),
                sha256 = sha256(bytes),
                mimeType = mimeType,
                width = size.width,
                height = size.height,
            ),
        )
    }

    /**
     * Deterministic release-group choice: earliest known release date first,
     * unknown dates last, release-group id ascending as the tie-break. The same
     * recording always resolves to the same release-group.
     */
    internal fun pickReleaseGroupId(lookup: RecordingLookupDto): String? {
        val candidates = lookup.releases.mapNotNull { release ->
            val raw = release.releaseGroup?.id?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val canonical = try {
                UUID.fromString(raw).toString()
            } catch (_: IllegalArgumentException) {
                return@mapNotNull null
            }
            if (canonical != raw) return@mapNotNull null
            (release.date ?: UNKNOWN_DATE_SORT_KEY) to canonical
        }
        return candidates
            .sortedWith(compareBy({ it.first }, { it.second }))
            .firstOrNull()
            ?.second
    }

    private fun unavailable(reason: CoverArtResolution.Reason): CoverArtResolution =
        CoverArtResolution.Unavailable(reason)

    /** Single bounded GET per hop; no credential is ever attached. */
    private suspend fun fetch(url: HttpUrl, maxBytes: Int): CoverFetch {
        var current = url
        val hops = mutableListOf(url)
        while (true) {
            if (hops.size > 1 && !hostPolicy.allowsRedirect(current)) {
                return CoverFetch.Policy(CoverArtResolution.Reason.HOST_NOT_ALLOWED)
            }
            if (current.query != null || current.encodedQuery != null) {
                // The single documented exception is the MusicBrainz metadata
                // request (first hop, MusicBrainz host). It is never sealed and
                // never a redirect target, so rule A4's no-query shape applies
                // to everything that can end up in the descriptor instead.
                val isMetadataRequest = hops.size == 1 &&
                    current.host.equals(musicBrainzBaseUrl.host, ignoreCase = true)
                if (!isMetadataRequest) {
                    return CoverFetch.Policy(CoverArtResolution.Reason.HOST_NOT_ALLOWED)
                }
            }

            val request = Request.Builder()
                .url(current)
                .header("Accept", "*/*")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            val outcome = try {
                client.executeResponseWithCallLifetime(request) { resp ->
                    val status = resp.code
                    val location = resp.header("Location")
                    if (status in REDIRECT_STATUSES && location != null) {
                        if (hops.size > MAX_REDIRECTS) {
                            return@executeResponseWithCallLifetime CoverFetch.Policy(
                                CoverArtResolution.Reason.HOST_NOT_ALLOWED,
                            )
                        }
                        val next = current.resolve(location)
                        if (next == null || !hostPolicy.allowsRedirect(next)) {
                            return@executeResponseWithCallLifetime CoverFetch.Policy(
                                CoverArtResolution.Reason.HOST_NOT_ALLOWED,
                            )
                        }
                        return@executeResponseWithCallLifetime CoverFetch.Redirect(next)
                    }
                    val contentType = resp.body.contentType()?.toString()
                    val bytes = readBounded(resp, maxBytes)
                    CoverFetch.Body(
                        status = status,
                        contentType = contentType,
                        body = bytes,
                        bytesRead = bytes?.size ?: (maxBytes + 1),
                        hops = hops.toList(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return CoverFetch.Transport
            }
            when (outcome) {
                is CoverFetch.Policy -> return outcome
                is CoverFetch.Transport -> return outcome
                is CoverFetch.Body -> return outcome
                is CoverFetch.Redirect -> {
                    current = outcome.next
                    hops += outcome.next
                }
            }
        }
    }

    private fun readBounded(response: okhttp3.Response, maxBytes: Int): ByteArray? {
        val source = response.body.source()
        val buffer = Buffer()
        var total = 0
        while (true) {
            val read = source.read(buffer, (maxBytes + 1 - total).toLong())
            if (read == -1L) break
            total += read.toInt()
            if (total > maxBytes) return null
        }
        return buffer.readByteArray()
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun isCanonicalUuid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun normalizeImageMediaType(contentType: String?): String? {
        val base = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return if (base in GeneratorExpression.ARTWORK_MIME_TYPES) base else null
    }

    private sealed interface CoverFetch {
        data class Body(
            val status: Int,
            val contentType: String?,
            val body: ByteArray?,
            val bytesRead: Int,
            val hops: List<HttpUrl>,
        ) : CoverFetch

        data class Redirect(val next: HttpUrl) : CoverFetch

        data class Policy(val reason: CoverArtResolution.Reason) : CoverFetch

        data object Transport : CoverFetch
    }

    companion object {
        /** Registry key sealed into [GeneratorExpression.ArtworkRef]. */
        const val COVER_ART_PROVIDER = "coverartarchive"

        /** CAA thumbnail size ARCHITECTURE-v1 §22 selects for capsule UI. */
        const val COVER_THUMB = "front-500"

        /** MusicBrainz metadata responses are small JSON documents. */
        const val MAX_METADATA_BYTES = 1 * 1024 * 1024

        /** Cover bytes are bounded before any decode. */
        const val MAX_IMAGE_BYTES = 10 * 1024 * 1024

        const val MAX_REDIRECTS = 5

        private const val USER_AGENT = "remanence-android/1.0 (client-only artwork)"

        private const val UNKNOWN_DATE_SORT_KEY = "9999-99-99"

        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

        val MUSICBRAINZ_BASE_URL: HttpUrl = "https://musicbrainz.org/".toHttpUrl()

        val COVER_ART_BASE_URL: HttpUrl = "https://coverartarchive.org/".toHttpUrl()

        /**
         * Seal the last *stable* hop.
         *
         * The terminal hop of a real CAA chain is a per-request archive node
         * (`dn721806.ca.archive.org`, `ia801506.us.archive.org`, …) that
         * alternates between requests, so it cannot be sealed without binding
         * identity to a host the service re-picks. The bare
         * `archive.org/download/…` hop addresses the exact file and returns
         * the same bytes every time; a chain with no redirect at all seals the
         * CAA URL itself.
         */
        fun stableSealableUrl(
            hops: List<HttpUrl>,
            policy: CoverArtHostPolicy = MusicBrainzCoverArtHostPolicy,
        ): HttpUrl? {
            hops.firstOrNull { policy.isCanonicalDownloadHost(it) }?.let { return it }
            return hops.singleOrNull()?.takeIf { policy.allowsRedirect(it) }
        }
    }
}
