package dev.hryshyn.remanence.core.data.network

import dev.hryshyn.remanence.core.model.GeneratorExpression
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

/** Typed fetch-time failure. Never carries a URL, host, or image bytes. */
enum class ArtworkFetchFailure {
    INVALID_URL,
    HOST_NOT_ALLOWED,
    NETWORK,
    HTTP,
    OVERSIZE,
    MIME_MISMATCH,
    DIGEST_MISMATCH,
    DECODE_FAILED,
    DIMENSION_MISMATCH,
}

/** Decoded pixel bounds of an image byte block. */
data class ArtworkDimensions(val width: Int, val height: Int)

/**
 * Header-only image probe. Returns the intrinsic pixel size, or `null` when the
 * bytes are not a decodable image. The production probe reads only the header
 * (`inJustDecodeBounds`), so a hostile payload is never fully decoded here.
 */
fun interface ArtworkImageInfo {
    fun dimensions(bytes: ByteArray): ArtworkDimensions?
}

/** Android production probe: bounds-only decode, no pixel allocation. */
internal object BitmapArtworkImageInfo : ArtworkImageInfo {
    override fun dimensions(bytes: ByteArray): ArtworkDimensions? {
        if (bytes.isEmpty()) return null
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return ArtworkDimensions(options.outWidth, options.outHeight)
    }
}

sealed interface ArtworkFetchResult {
    /**
     * The exact bytes whose SHA-256 equals the sealed descriptor digest and
     * whose decoded bounds equal the sealed pixel size. Ownership transfers to
     * the caller, which must zeroize them after decode.
     */
    data class Verified(val bytes: ByteArray) : ArtworkFetchResult

    data class Failed(val reason: ArtworkFetchFailure) : ArtworkFetchResult
}

/**
 * Direct client-to-provider cover-art fetcher (V5 artwork contract section 8).
 *
 * The sealed [GeneratorExpression.ArtworkRef] is the spec: the URL is
 * re-validated (https, no userinfo/query/fragment, port absent or 443, bounded
 * length), the host must be in the fetch-time allowlist, one GET is issued with
 * redirects disabled (the shared [HttpClientFactory]), the body is bounded
 * before it is read, the exact bytes are compared to the sealed SHA-256 with
 * [MessageDigest.isEqual] (constant time), and the decoded pixel bounds must
 * equal the sealed size. The shared client keeps redirects disabled, so **at
 * most one** 3xx is followed manually: `Location` is resolved, then re-validated
 * in full (https, no userinfo/query/fragment, default-or-443 port, bounded
 * length) and its host must satisfy the rotating Archive.org node policy
 * (exact member or exact sub-domain of a member of [allowedHosts]); a second
 * redirect, a missing `Location`, any other host or any other shape is
 * rejected, and every body/digest/mime/size gate then applies unchanged to the
 * final response. No credentials, cookies, or query are ever sent, and no URL,
 * host, or image byte is ever logged. Every failure returns a typed
 * [ArtworkFetchResult.Failed]; the caller renders the no-art layout.
 *
 * The backend is never contacted: host allowlisting and the direct GET keep the
 * bytes client <-> provider only.
 */
fun interface ArtworkFetcher {
    suspend fun fetch(descriptor: GeneratorExpression.ArtworkRef): ArtworkFetchResult

    companion object {
        /** Process-wide production fetcher over the model allowlist. */
        val Default: ArtworkFetcher by lazy {
            HttpArtworkFetcher(HttpClientFactory.create(), GeneratorExpression.ARTWORK_HOSTS)
        }
    }
}

internal class HttpArtworkFetcher(
    private val client: OkHttpClient,
    private val allowedHosts: Set<String>,
    private val imageInfo: ArtworkImageInfo = BitmapArtworkImageInfo,
    private val maxBytes: Long = MAX_BODY_BYTES,
) : ArtworkFetcher {

    override suspend fun fetch(descriptor: GeneratorExpression.ArtworkRef): ArtworkFetchResult {
        // Sealed origin: exact host membership only — node hosts are never
        // sealed, so they can never be the origin.
        val host = validatedHost(descriptor.url)
            ?: return ArtworkFetchResult.Failed(ArtworkFetchFailure.INVALID_URL)
        if (host !in allowedHosts) {
            return ArtworkFetchResult.Failed(ArtworkFetchFailure.HOST_NOT_ALLOWED)
        }
        return try {
            when (val first = getHop(descriptor.url, descriptor)) {
                is Hop.Finished -> first.result
                is Hop.Redirect -> followSingleRedirect(first, descriptor)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            ArtworkFetchResult.Failed(ArtworkFetchFailure.NETWORK)
        }
    }

    /**
     * Follow exactly one redirect hop and reject everything else.
     *
     * The sealed origin (`archive.org`) answers `302` to a rotating download
     * node, so a redirecting origin must be tolerated — but only one hop, and
     * only after the target re-passes the full A4 shape and the rotating
     * node-host policy. A second `3xx` is refused rather than followed.
     */
    private suspend fun followSingleRedirect(
        first: Hop.Redirect,
        descriptor: GeneratorExpression.ArtworkRef,
    ): ArtworkFetchResult {
        if (first.location.isBlank()) {
            return ArtworkFetchResult.Failed(ArtworkFetchFailure.INVALID_URL)
        }
        val target = resolveRedirect(descriptor.url, first.location)
            ?: return ArtworkFetchResult.Failed(ArtworkFetchFailure.INVALID_URL)
        val hopHost = validatedHost(target)
            ?: return ArtworkFetchResult.Failed(ArtworkFetchFailure.INVALID_URL)
        if (!allowedRedirectHost(hopHost)) {
            return ArtworkFetchResult.Failed(ArtworkFetchFailure.HOST_NOT_ALLOWED)
        }
        return when (val second = getHop(target, descriptor)) {
            is Hop.Finished -> second.result
            is Hop.Redirect -> ArtworkFetchResult.Failed(ArtworkFetchFailure.HTTP)
        }
    }

    /** One GET through the redirect-disabled shared client. */
    private suspend fun getHop(
        url: String,
        descriptor: GeneratorExpression.ArtworkRef,
    ): Hop {
        val request = Request.Builder()
            .url(url)
            .header("Accept", descriptor.mimeType)
            .get()
            .build()
        return client.executeResponseWithCallLifetime(request) { response ->
            if (response.code in REDIRECT_STATUSES) {
                Hop.Redirect(response.header("Location") ?: "")
            } else {
                Hop.Finished(interpret(response, descriptor))
            }
        }
    }

    /**
     * Rotating Archive.org node hosts (`dn721806.ca.archive.org`,
     * `ia801506.us.archive.org`, ...) are accepted as the single hop only when
     * the host is a member of [allowedHosts] or an exact sub-domain of one —
     * the same suffix rule the accepted T062 sender policy applies. The sealed
     * origin itself is checked with exact membership above.
     */
    private fun allowedRedirectHost(host: String): Boolean =
        allowedHosts.any { member -> host == member || host.endsWith(".$member") }

    private fun resolveRedirect(from: String, location: String): String? = try {
        URI(from).resolve(location.trim()).toString()
    } catch (_: Exception) {
        null
    }

    private fun interpret(
        response: Response,
        descriptor: GeneratorExpression.ArtworkRef,
    ): ArtworkFetchResult {
        if (!response.isSuccessful) {
            return ArtworkFetchResult.Failed(ArtworkFetchFailure.HTTP)
        }
        val body = response.body
        // Normalize the response side (parameters stripped, lowercased) and
        // compare against the already-normalized sealed MIME (T062 seals a
        // lowercase member of ARTWORK_MIME_TYPES).
        val contentType = body.contentType()?.toString()
            ?.substringBefore(';')?.trim()?.lowercase()
        if (contentType != descriptor.mimeType) {
            return ArtworkFetchResult.Failed(ArtworkFetchFailure.MIME_MISMATCH)
        }
        val bytes = readBounded(body)
            ?: return ArtworkFetchResult.Failed(ArtworkFetchFailure.OVERSIZE)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        if (!MessageDigest.isEqual(digest, descriptor.sha256)) {
            bytes.fill(0)
            return ArtworkFetchResult.Failed(ArtworkFetchFailure.DIGEST_MISMATCH)
        }
        val dimensions = imageInfo.dimensions(bytes)
        if (dimensions == null) {
            bytes.fill(0)
            return ArtworkFetchResult.Failed(ArtworkFetchFailure.DECODE_FAILED)
        }
        if (dimensions.width != descriptor.width || dimensions.height != descriptor.height) {
            bytes.fill(0)
            return ArtworkFetchResult.Failed(ArtworkFetchFailure.DIMENSION_MISMATCH)
        }
        return ArtworkFetchResult.Verified(bytes)
    }

    private fun readBounded(body: ResponseBody): ByteArray? {
        val contentLength = body.contentLength()
        if (contentLength > maxBytes) {
            return null
        }
        val collected = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        body.byteStream().use { stream ->
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) {
                    break
                }
                if (collected.size().toLong() + read > maxBytes) {
                    return null
                }
                collected.write(chunk, 0, read)
            }
        }
        return collected.toByteArray()
    }

    /**
     * Rule A4 re-checked with a real URL parser (the sealed descriptor is the
     * spec, but the fetcher never trusts the sealer). Returns the normalized
     * host or `null` when the URL is not a fetchable https URL.
     */
    private fun validatedHost(url: String): String? {
        if (url.length !in 1..GeneratorExpression.ARTWORK_URL_MAX) return null
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.rawUserInfo != null) return null
        if (uri.rawQuery != null) return null
        if (uri.rawFragment != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        val host = uri.host ?: return null
        if (host.isBlank()) return null
        return host.lowercase()
    }

    /** Outcome of a single GET: either a redirect hint or a final response. */
    private sealed interface Hop {
        data class Redirect(val location: String) : Hop
        data class Finished(val result: ArtworkFetchResult) : Hop
    }

    private companion object {
        /** Aligned to the sender's sealing ceiling (T062 `MAX_IMAGE_BYTES`). */
        const val MAX_BODY_BYTES = 10L * 1024 * 1024
        const val READ_CHUNK_BYTES = 8 * 1024
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}
