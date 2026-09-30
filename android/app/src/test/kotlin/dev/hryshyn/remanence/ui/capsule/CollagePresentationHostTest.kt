package dev.hryshyn.remanence.ui.capsule

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.data.network.ArtworkFetchResult
import dev.hryshyn.remanence.core.data.network.ArtworkFetcher
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import dev.hryshyn.remanence.ui.collage.FontRegistry
import dev.hryshyn.remanence.ui.collage.StyleRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose coverage for [CollagePresentationHost]: success paints
 * only the collage canvas (no v1 pager, no track card), every decode/prepare
 * failure shows the shared unsupported notice with no canvas, and the close
 * affordance releases the state and calls back.
 *
 * Environment note: rendered pixels are not asserted (Robolectric); the canvas
 * presence is pinned by the content/root tags and the deterministic math by
 * the collage package's pure tests.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollagePresentationHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val contentIds = listOf("p0", "p1", "p2")

    private fun okDecoder() = Ber1PhotoDecoder { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap() }

    private fun snapshot() = CapsuleTrackSnapshotV1.parse(
        trackId = "be30e36b-1111-4111-8111-000000000001",
        title = "505",
        artistDisplay = "Arctic Monkeys",
        version = null,
        durationMs = 253000L,
    )

    private fun artwork() = GeneratorExpression.ArtworkRef(
        provider = "catalog-sample",
        url = "https://art.example.org/cover/ab12.webp",
        sha256 = ByteArray(32) { it.toByte() },
        mimeType = "image/webp",
        width = 4,
        height = 4,
    )

    private fun expressionV3(
        note: String? = null,
        noteRegion: GeneratorExpression.NoteRegion? = null,
        musicRegion: GeneratorExpression.MusicRegion? = null,
    ) = GeneratorExpression.ResolvedExpression(
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
            note = note,
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
        noteRegion = noteRegion,
        style = GeneratorExpression.StyleBinding(
            styleId = "press",
            styleVersion = "style-study-1",
            digest = requireNotNull(StyleRegistry.digest("press")),
        ),
        noteType = GeneratorExpression.TextType("StudySans", 16, 400, false, 18),
        musicRegion = musicRegion,
    )

    private fun loadedState(
        expression: GeneratorExpression.ResolvedExpression,
        note: String? = null,
        track: CapsuleTrackSnapshotV1? = null,
        decoder: Ber1PhotoDecoder = okDecoder(),
        artworkFetcher: ArtworkFetcher? = null,
    ): CollagePresentationState {
        val state = CollagePresentationState(
            expression = expression,
            loadPhoto = { contentId -> contentId.toByteArray() },
            note = note,
            track = track,
            decoder = decoder,
            artworkFetcher = artworkFetcher,
        )
        runBlocking { state.load() }
        return state
    }

    @Test
    fun loadingStateRendersNothing() {
        val state = CollagePresentationState(
            expression = expressionV3(),
            loadPhoto = { it.toByteArray() },
            decoder = okDecoder(),
        )
        composeRule.setContent { CollagePresentationHost(state, "collage", onClose = {}) }
        composeRule.onNodeWithTag("collage_canvas").assertDoesNotExist()
        composeRule.onNodeWithTag("capsule_unsupported_notice").assertDoesNotExist()
    }

    @Test
    fun readyStatePaintsCanvasAndNoPagerOrTrackCard() {
        val state = loadedState(expressionV3())
        composeRule.setContent { CollagePresentationHost(state, "collage", onClose = {}) }
        composeRule.onNodeWithTag("collage_canvas").assertExists()
        composeRule.onNodeWithTag("capsule_page_0").assertDoesNotExist()
        composeRule.onNodeWithTag("capsule_underlayer").assertDoesNotExist()
        composeRule.onNodeWithTag("capsule_track_card").assertDoesNotExist()
    }

    @Test
    fun musicReadyStatePaintsCanvasWithoutTrackCard() {
        val music = GeneratorExpression.MusicRegion(
            x = 0, y = 220, width = 360, height = 60,
            mode = "text",
            type = GeneratorExpression.TextType("StudySerif", 14, 400, true, 16),
            artwork = null,
        )
        val state = loadedState(expressionV3(musicRegion = music), track = snapshot())
        composeRule.setContent { CollagePresentationHost(state, "collage", onClose = {}) }
        composeRule.onNodeWithTag("collage_canvas").assertExists()
        composeRule.onNodeWithTag("capsule_track_card").assertDoesNotExist()
    }

    @Test
    fun decodeFailureRendersUnsupportedNoticeAndNoCanvas() {
        val state = loadedState(
            expressionV3(),
            decoder = Ber1PhotoDecoder { null },
        )
        composeRule.setContent { CollagePresentationHost(state, "collage", onClose = {}) }
        composeRule.onNodeWithTag("capsule_unsupported_notice").assertExists()
        composeRule.onNodeWithTag("capsule_unsupported_reason").assertExists()
        composeRule.onNodeWithTag("collage_canvas").assertDoesNotExist()
    }

    @Test
    fun prepareRejectionRendersNoticeAndReleasesPixels() {
        val music = GeneratorExpression.MusicRegion(
            x = 0, y = 220, width = 360, height = 60,
            mode = "text",
            type = GeneratorExpression.TextType("StudySans", 14, 400, false, 16),
            artwork = null,
        )
        // A sealed music region with no track snapshot is a prepare rejection.
        val state = loadedState(expressionV3(musicRegion = music))
        composeRule.setContent { CollagePresentationHost(state, "collage", onClose = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capsule_unsupported_notice").assertExists()
        composeRule.onNodeWithTag("collage_canvas").assertDoesNotExist()
        assertTrue("prepare rejection must release decoded pixels", state.bitmaps.isEmpty())
    }

    @Test
    fun thumbnailWithoutVerifiedCoverPaintsDegradedTextLayout() {
        val thumbnail = GeneratorExpression.MusicRegion(
            x = 0, y = 220, width = 360, height = 60,
            mode = "thumbnail",
            type = GeneratorExpression.TextType("StudySans", 14, 400, false, 16),
            artwork = artwork(),
        )
        val state = loadedState(expressionV3(musicRegion = thumbnail), track = snapshot())
        composeRule.setContent { CollagePresentationHost(state, "collage", onClose = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("collage_canvas").assertExists()
        composeRule.onNodeWithTag("capsule_unsupported_notice").assertDoesNotExist()
        composeRule.onNodeWithTag("capsule_track_card").assertDoesNotExist()
    }

    @Test
    fun verifiedArtworkPaintsCanvas() {
        val thumbnail = GeneratorExpression.MusicRegion(
            x = 0, y = 220, width = 360, height = 60,
            mode = "thumbnail",
            type = GeneratorExpression.TextType("StudySans", 14, 400, false, 16),
            artwork = artwork(),
        )
        val state = loadedState(
            expressionV3(musicRegion = thumbnail),
            track = snapshot(),
            artworkFetcher = ArtworkFetcher { ArtworkFetchResult.Verified(ByteArray(4)) },
        )
        composeRule.setContent { CollagePresentationHost(state, "collage", onClose = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("collage_canvas", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("collage_canvas_content", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("capsule_unsupported_notice").assertDoesNotExist()
    }

    @Test
    fun noteMismatchRendersNoticeAndReleasesPixels() {
        val expression = expressionV3(
            note = "sealed",
            noteRegion = GeneratorExpression.NoteRegion(0, 400, 360, 200),
        )
        val state = loadedState(expression, note = "different")
        composeRule.setContent { CollagePresentationHost(state, "collage", onClose = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capsule_unsupported_notice").assertExists()
        composeRule.onNodeWithTag("collage_canvas").assertDoesNotExist()
        assertTrue(state.bitmaps.isEmpty())
    }

    @Test
    fun closeButtonInvokesCallbackAndReleases() {
        val state = loadedState(expressionV3())
        var closed = false
        composeRule.setContent { CollagePresentationHost(state, "collage", onClose = { closed = true }) }
        composeRule.onNodeWithTag("capsule_collage_close").performClick()
        assertTrue("close callback must fire", closed)
        assertTrue("bitmaps must be released", state.bitmaps.isEmpty())
        assertTrue(state.status === CollagePresentationState.Status.Loading)
    }
}
