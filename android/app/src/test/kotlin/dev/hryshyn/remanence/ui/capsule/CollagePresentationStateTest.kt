package dev.hryshyn.remanence.ui.capsule

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.data.network.ArtworkCache
import dev.hryshyn.remanence.core.data.network.ArtworkFetchFailure
import dev.hryshyn.remanence.core.data.network.ArtworkFetchResult
import dev.hryshyn.remanence.core.data.network.ArtworkFetcher
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import dev.hryshyn.remanence.ui.collage.FontRegistry
import dev.hryshyn.remanence.ui.collage.StyleRegistry
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Lifecycle coverage for [CollagePresentationState]: decode-once keyed by the
 * sealed content id, owned-JPEG zeroization, fail-closed decode/cancellation,
 * and release on close/unsupported.
 *
 * Resource note: only the owned JPEG buffers are zeroized; decoded pixels are
 * released by dropping references (native pixels are not scrubbable).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollagePresentationStateTest {

    private val contentIds = listOf("p0", "p1", "p2")
    private val owned = mutableListOf<ByteArray>()

    private fun okDecoder() = Ber1PhotoDecoder { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap() }

    private fun decoder(failOn: String? = null, cancelOn: String? = null) = Ber1PhotoDecoder { bytes ->
        when (val id = bytes.decodeToString()) {
            failOn -> null
            cancelOn -> throw CancellationException("test cancellation")
            else -> Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }

    private fun state(decoder: Ber1PhotoDecoder) = CollagePresentationState(
        expression = expressionV3(),
        loadPhoto = { contentId -> contentId.toByteArray().also { owned += it } },
        decoder = decoder,
    )

    private fun expressionV3() = GeneratorExpression.ResolvedExpression(
        canvasVersion = 1,
        grammarId = "g-hold",
        grammarVersion = 3,
        branchId = "b-rows",
        input = GeneratorExpression.GeneratorInput(
            ownerId = "owner-1",
            epoch = 7L,
            photos = contentIds.mapIndexed { index, id ->
                GeneratorExpression.PhotoRef(
                    contentId = id, ordinal = index, widthPx = 1000, heightPx = 1000,
                    contentHash = ('a' + index).toString().repeat(64),
                )
            },
            note = null,
            music = null,
        ),
        placements = contentIds.mapIndexed { index, id ->
            GeneratorExpression.Placement(
                contentId = id, x = index * 120, y = 0, width = 120, height = 213,
                crop = GeneratorExpression.CropWindow(0, 0, 10000, 10000),
                maskId = null,
                contentRect = null,
            )
        },
        noteTreatment = "regular-region",
        diagnostics = emptyList(),
        fontVersion = FontRegistry.VERSION,
        paletteVersion = CollageRenderer.PALETTE_REGISTRY_VERSION,
        expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V3,
        noteRegion = null,
        style = GeneratorExpression.StyleBinding(
            styleId = "press",
            styleVersion = "style-study-1",
            digest = requireNotNull(StyleRegistry.digest("press")),
        ),
        noteType = GeneratorExpression.TextType("StudySans", 16, 400, false, 18),
        musicRegion = null,
    )

    private fun assertOwnedBytesZeroed() {
        assertTrue("expected owned buffers", owned.isNotEmpty())
        assertTrue("every owned JPEG buffer must be zeroized", owned.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test
    fun loadsOnceKeyedBySealedContentIdAndZeroizesOwnedBuffers() = runBlocking {
        val state = state(okDecoder())
        state.load()
        val ready = state.status as? CollagePresentationState.Status.Ready
            ?: error("expected Ready, got ${state.status}")
        assertEquals(setOf("p0", "p1", "p2"), ready.bitmaps.keys)
        assertEquals(3, owned.size)
        assertOwnedBytesZeroed()
    }

    @Test
    fun decodeFailureReleasesPartialDecodeAndYieldsUnsupported() = runBlocking {
        val state = state(decoder(failOn = "p1"))
        state.load()
        assertEquals(
            CollagePresentationState.Status.Unsupported(CollagePresentationState.DECODE_FAILURE_REASON),
            state.status,
        )
        assertTrue(state.bitmaps.isEmpty())
        assertOwnedBytesZeroed()
    }

    @Test
    fun cancellationReleasesPartialDecodeAndRethrows() {
        val state = state(decoder(cancelOn = "p1"))
        var cancelled = false
        try {
            runBlocking { state.load() }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue("cancellation must propagate", cancelled)
        assertEquals(CollagePresentationState.Status.Loading, state.status)
        assertTrue(state.bitmaps.isEmpty())
        assertOwnedBytesZeroed()
    }

    @Test
    fun secondLoadIsRefused() = runBlocking {
        val state = state(okDecoder())
        state.load()
        var refused = false
        try {
            state.load()
        } catch (_: IllegalStateException) {
            refused = true
        }
        assertTrue("a second load must be refused", refused)
    }

    @Test
    fun closeReleasesBitmapsAndIsIdempotent() = runBlocking {
        val state = state(okDecoder())
        state.load()
        state.close()
        assertEquals(CollagePresentationState.Status.Loading, state.status)
        assertTrue(state.bitmaps.isEmpty())
        state.close()
        assertEquals(CollagePresentationState.Status.Loading, state.status)
    }

    @Test
    fun unsupportedReleasesBitmaps() = runBlocking {
        val state = state(okDecoder())
        state.load()
        state.unsupported("finished collage rendering is not supported")
        assertEquals(
            CollagePresentationState.Status.Unsupported("finished collage rendering is not supported"),
            state.status,
        )
        assertTrue(state.bitmaps.isEmpty())
    }

    @Test
    fun loadAfterCloseIsRefused() {
        val state = state(okDecoder())
        state.close()
        var refused = false
        try {
            runBlocking { state.load() }
        } catch (_: IllegalStateException) {
            refused = true
        }
        assertTrue("load after close must be refused", refused)
        assertFalse(state.status is CollagePresentationState.Status.Ready)
    }

    @Test
    fun closeDuringFirstLoadPreventsSecondIdAndReady() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val loadedIds = mutableListOf<String>()
        val buffers = mutableListOf<ByteArray>()
        val state = CollagePresentationState(
            expression = expressionV3(),
            loadPhoto = { contentId ->
                loadedIds += contentId
                if (contentId == "p0") {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                contentId.toByteArray().also { buffers += it }
            },
            decoder = okDecoder(),
        )
        val job = launch(Dispatchers.Default) { state.load() }
        firstStarted.await()
        state.close()
        releaseFirst.complete(Unit)
        job.join()

        assertEquals(listOf("p0"), loadedIds)
        assertEquals(CollagePresentationState.Status.Loading, state.status)
        assertTrue(state.bitmaps.isEmpty())
        assertTrue("the in-flight buffer must be zeroized", buffers.isNotEmpty())
        assertTrue(buffers.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test
    fun closeBetweenDecodeAndPublicationCannotResurrectContent() {
        val decodeEntered = CountDownLatch(1)
        val releaseDecode = CountDownLatch(1)
        val decodedBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()
        var buffer: ByteArray? = null
        val state = CollagePresentationState(
            expression = expressionV3(),
            loadPhoto = { contentId -> contentId.toByteArray().also { buffer = it } },
            decoder = Ber1PhotoDecoder {
                decodeEntered.countDown()
                releaseDecode.await()
                decodedBitmap
            },
        )
        val loader = thread { runBlocking { state.load() } }
        decodeEntered.await()
        state.close()
        releaseDecode.countDown()
        loader.join()

        assertEquals(CollagePresentationState.Status.Loading, state.status)
        assertTrue(state.bitmaps.isEmpty())
        assertTrue(requireNotNull(buffer).all { it == 0.toByte() })
    }

    @Test
    fun repeatedCloseAfterLoadIsANoOp() = runBlocking {
        val state = state(okDecoder())
        state.load()
        state.close()
        state.close()
        state.close()
        assertEquals(CollagePresentationState.Status.Loading, state.status)
        assertTrue(state.bitmaps.isEmpty())
    }

    private fun artworkRef() = GeneratorExpression.ArtworkRef(
        provider = "catalog-sample",
        url = "https://art.example.org/cover/ab12.webp",
        sha256 = ByteArray(32) { it.toByte() },
        mimeType = "image/webp",
        width = 4,
        height = 4,
    )

    private fun expressionV3Thumbnail() = expressionV3().copy(
        musicRegion = GeneratorExpression.MusicRegion(
            x = 0, y = 220, width = 360, height = 60,
            mode = "thumbnail",
            type = GeneratorExpression.TextType("StudySans", 14, 400, false, 16),
            artwork = artworkRef(),
        ),
    )

    private fun artworkState(
        fetcher: ArtworkFetcher?,
        cache: ArtworkCache? = null,
        timeoutMs: Long = CollagePresentationState.DEFAULT_ARTWORK_TIMEOUT_MS,
    ) = CollagePresentationState(
        expression = expressionV3Thumbnail(),
        loadPhoto = { contentId -> contentId.toByteArray().also { owned += it } },
        decoder = okDecoder(),
        artworkFetcher = fetcher,
        artworkCache = cache,
        artworkTimeoutMs = timeoutMs,
    )

    @Test
    fun verifiedArtworkIsFetchedDecodedPublishedAndCached() = runBlocking {
        val cache = ArtworkCache()
        val state = artworkState(
            fetcher = ArtworkFetcher { ArtworkFetchResult.Verified("cover".toByteArray()) },
            cache = cache,
        )
        state.load()
        val ready = state.status as? CollagePresentationState.Status.Ready
            ?: error("expected Ready, got ${state.status}")
        assertNotNull("a verified cover must publish a bitmap", ready.artwork)
        assertEquals(1, cache.size)
        assertOwnedBytesZeroed()
    }

    @Test
    fun artworkFetchFailureDegradesToTheNoArtLayout() = runBlocking {
        val state = artworkState(
            fetcher = ArtworkFetcher { ArtworkFetchResult.Failed(ArtworkFetchFailure.NETWORK) },
        )
        state.load()
        val ready = state.status as? CollagePresentationState.Status.Ready
            ?: error("expected Ready, got ${state.status}")
        assertNull("a failed fetch must degrade to no art", ready.artwork)
        assertEquals(setOf("p0", "p1", "p2"), ready.bitmaps.keys)
    }

    @Test
    fun artworkCacheHitSkipsTheFetcher() = runBlocking {
        var fetches = 0
        val cache = ArtworkCache()
        cache.put(requireNotNull(expressionV3Thumbnail().musicRegion?.artwork).sha256, "cached".toByteArray())
        val state = artworkState(
            fetcher = ArtworkFetcher {
                fetches++
                ArtworkFetchResult.Failed(ArtworkFetchFailure.NETWORK)
            },
            cache = cache,
        )
        state.load()
        val ready = state.status as? CollagePresentationState.Status.Ready
            ?: error("expected Ready, got ${state.status}")
        assertNotNull(ready.artwork)
        assertEquals("a cache hit must not fetch", 0, fetches)
    }

    @Test
    fun nullFetcherCannotProduceArtwork() = runBlocking {
        val state = artworkState(fetcher = null)
        state.load()
        val ready = state.status as? CollagePresentationState.Status.Ready
            ?: error("expected Ready, got ${state.status}")
        assertNull(ready.artwork)
    }

    @Test
    fun slowArtworkFetchDegradesToTheNoArtLayout() = runBlocking {
        val state = artworkState(
            fetcher = ArtworkFetcher {
                delay(5_000)
                ArtworkFetchResult.Verified("cover".toByteArray())
            },
            timeoutMs = 50,
        )
        state.load()
        val ready = state.status as? CollagePresentationState.Status.Ready
            ?: error("expected Ready, got ${state.status}")
        assertNull("a slow fetch must degrade to no art", ready.artwork)
    }

    @Test
    fun cancellationDuringArtworkFetchRethrowsAndReleasesPhotos() {
        val state = artworkState(
            fetcher = ArtworkFetcher { throw CancellationException("artwork cancelled") },
        )
        var cancelled = false
        try {
            runBlocking { state.load() }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue("artwork cancellation must propagate", cancelled)
        assertEquals(CollagePresentationState.Status.Loading, state.status)
        assertTrue(state.bitmaps.isEmpty())
        assertOwnedBytesZeroed()
    }
}
