package dev.hryshyn.remanence.ui.capsule

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import dev.hryshyn.remanence.ui.collage.FontRegistry
import dev.hryshyn.remanence.ui.collage.StyleRegistry
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
