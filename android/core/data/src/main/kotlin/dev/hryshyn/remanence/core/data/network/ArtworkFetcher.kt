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
 * equal the sealed size. No credentials, cookies, or query are ever sent, and
 * no URL, host, or image byte is ever logged. Every failure returns a typed
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
        val host = validatedHost(descriptor.url)
            ?: return ArtworkFetchResult.Failed(ArtworkFetchFailure.INVALID_URL)
        if (host !in allowedHosts) {
            return ArtworkFetchResult.Failed(ArtworkFetchFailure.HOST_NOT_ALLOWED)
        }
        val request = Request.Builder()
            .url(descriptor.url)
            .header("Accept", descriptor.mimeType)
            .get()
            .build()
        return try {
            client.executeResponseWithCallLifetime(request) { response ->
                interpret(response, descriptor)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            ArtworkFetchResult.Failed(ArtworkFetchFailure.NETWORK)
        }
    }

    private fun interpret(
        response: Response,
        descriptor: GeneratorExpression.ArtworkRef,
    ): ArtworkFetchResult {
        if (!response.isSuccessful) {
            return ArtworkFetchResult.Failed(ArtworkFetchFailure.HTTP)
        }
        val body = response.body
        val contentType = body.contentType()?.toString()?.substringBefore(';')?.trim()
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

    private companion object {
        const val MAX_BODY_BYTES = 8L * 1024 * 1024
        const val READ_CHUNK_BYTES = 8 * 1024
    }
}
