package dev.hryshyn.remanence.core.data.network

import dev.hryshyn.remanence.core.model.GeneratorExpression
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Fully hermetic [HttpArtworkFetcher] coverage: the fake OkHttp interceptor
 * returns synthetic responses and never opens a socket, so no test performs a
 * network or backend call. Verifies URL/host re-validation, the single GET,
 * redirect refusal, bounded body, constant-time digest, mime and decoded-size
 * checks, and cancellation.
 */
class ArtworkFetcherTest {

    private val host = "art.example.org"
    private val url = "https://art.example.org/cover/ab12.webp"
    private val mime = "image/webp"
    private val body = ByteArray(64) { it.toByte() }

    private fun sha(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun descriptor(
        bytes: ByteArray = body,
        digest: ByteArray = sha(body),
        urlValue: String = url,
        mimeValue: String = mime,
        width: Int = 640,
        height: Int = 640,
    ) = GeneratorExpression.ArtworkRef(
        provider = "catalog-sample",
        url = urlValue,
        sha256 = digest,
        mimeType = mimeValue,
        width = width,
        height = height,
    )

    private fun fetcher(
        hosts: Set<String> = setOf(host),
        imageInfo: ArtworkImageInfo = ArtworkImageInfo { ArtworkDimensions(640, 640) },
        maxBytes: Long = 8L * 1024 * 1024,
        respond: (Request) -> Response,
    ): ArtworkFetcher {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain -> respond(chain.request()) }
            .build()
        return HttpArtworkFetcher(client, hosts, imageInfo, maxBytes)
    }

    private fun imageResponse(
        request: Request,
        code: Int = 200,
        contentType: String? = mime,
        bytes: ByteArray = body,
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(bytes.toResponseBody(contentType?.toMediaType()))
        if (contentType != null) builder.header("Content-Type", contentType)
        return builder.build()
    }

    private fun failure(result: ArtworkFetchResult): ArtworkFetchFailure {
        val failed = assertIs<ArtworkFetchResult.Failed>(result)
        return failed.reason
    }

    @Test
    fun verifiedBytesWhenEveryCheckPasses() = runBlocking {
        var recorded: Request? = null
        val fetcher = fetcher { request ->
            recorded = request
            imageResponse(request)
        }
        val result = fetcher.fetch(descriptor())
        val verified = assertIs<ArtworkFetchResult.Verified>(result)
        assertTrue(verified.bytes.contentEquals(body))
        val request = assertIs<Request>(recorded)
        assertEquals("GET", request.method)
        assertEquals(mime, request.header("Accept"))
        assertEquals(null, request.url.query)
    }

    @Test
    fun redirectIsNotFollowed() = runBlocking {
        val fetcher = fetcher { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(302)
                .message("Found")
                .header("Location", "https://art.example.org/cover/other.webp")
                .body(ByteArray(0).toResponseBody(null))
                .build()
        }
        assertEquals(ArtworkFetchFailure.HTTP, failure(fetcher.fetch(descriptor())))
    }

    @Test
    fun nonAllowlistedHostIsRejectedWithoutARequest() = runBlocking {
        var called = false
        val fetcher = fetcher(respond = { request ->
            called = true
            imageResponse(request)
        })
        val result = fetcher.fetch(descriptor(urlValue = "https://evil.example.org/cover/ab12.webp"))
        assertEquals(ArtworkFetchFailure.HOST_NOT_ALLOWED, failure(result))
        assertFalse(called, "a non-allowlisted host must not issue a request")
    }

    @Test
    fun invalidUrlsAreRejected() = runBlocking {
        val fetcher = fetcher { request -> imageResponse(request) }
        for (bad in listOf(
            "http://art.example.org/cover/ab12.webp",
            "https://art.example.org/cover/ab12.webp?sig=1",
            "https://art.example.org/cover/ab12.webp#frag",
            "https://user@art.example.org/cover/ab12.webp",
            "https://art.example.org:8443/cover/ab12.webp",
        )) {
            assertEquals(
                ArtworkFetchFailure.INVALID_URL,
                failure(fetcher.fetch(descriptor(urlValue = bad))),
                "expected INVALID_URL for $bad",
            )
        }
    }

    @Test
    fun oversizeBodyIsRejected() = runBlocking {
        val fetcher = fetcher(maxBytes = 32) { request -> imageResponse(request) }
        assertEquals(ArtworkFetchFailure.OVERSIZE, failure(fetcher.fetch(descriptor())))
    }

    @Test
    fun mimeMismatchIsRejected() = runBlocking {
        val fetcher = fetcher { request -> imageResponse(request, contentType = "image/png") }
        assertEquals(ArtworkFetchFailure.MIME_MISMATCH, failure(fetcher.fetch(descriptor())))
    }

    @Test
    fun digestMismatchIsRejected() = runBlocking {
        val fetcher = fetcher { request -> imageResponse(request) }
        val result = fetcher.fetch(descriptor(digest = ByteArray(32) { 9 }))
        assertEquals(ArtworkFetchFailure.DIGEST_MISMATCH, failure(result))
    }

    @Test
    fun dimensionMismatchIsRejected() = runBlocking {
        val fetcher = fetcher(imageInfo = ArtworkImageInfo { ArtworkDimensions(100, 100) }) { request ->
            imageResponse(request)
        }
        assertEquals(ArtworkFetchFailure.DIMENSION_MISMATCH, failure(fetcher.fetch(descriptor())))
    }

    @Test
    fun undecodableImageIsRejected() = runBlocking {
        val fetcher = fetcher(imageInfo = ArtworkImageInfo { null }) { request -> imageResponse(request) }
        assertEquals(ArtworkFetchFailure.DECODE_FAILED, failure(fetcher.fetch(descriptor())))
    }

    @Test
    fun httpErrorIsRejected() = runBlocking {
        val fetcher = fetcher { request -> imageResponse(request, code = 404) }
        assertEquals(ArtworkFetchFailure.HTTP, failure(fetcher.fetch(descriptor())))
    }

    @Test
    fun transportFailureIsNetwork() = runBlocking {
        val fetcher = fetcher { throw IOException("no socket") }
        assertEquals(ArtworkFetchFailure.NETWORK, failure(fetcher.fetch(descriptor())))
    }

    @Test
    fun cancellationPropagatesAndNeverCompletes() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val fetcher = fetcher { request ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            imageResponse(request)
        }
        val job = launch(Dispatchers.IO) {
            fetcher.fetch(descriptor())
            completed.set(true)
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the fetch must start")
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(completed.get(), "a cancelled fetch must not complete")
        release.countDown()
    }
}
