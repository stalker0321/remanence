package dev.hryshyn.remanence.ui.capsule

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.crypto.ManifestVoice
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.BranchProvenance
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import dev.hryshyn.remanence.ui.collage.FontRegistry
import dev.hryshyn.remanence.ui.collage.SongBand
import dev.hryshyn.remanence.ui.collage.StyleRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ADR-021 receiving side: an open card with a voice note holds its sound for
 * as long as it is open, makes the whole song band one button that plays and
 * pauses it under a pause glyph, and zeroes the sound when it closes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapsuleVoiceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val contentIds = listOf("p0", "p1", "p2")
    private val peaks = IntArray(SongBand.VOICE_PEAKS) { (it * 53) % 256 }

    private fun expression() = GeneratorExpression.ResolvedExpression(
        canvasVersion = 1,
        grammarId = "1",
        grammarVersion = 3,
        branchId = "hinge",
        input = GeneratorExpression.GeneratorInput(
            ownerId = "owner-1",
            epoch = 7L,
            photos = contentIds.mapIndexed { index, id ->
                GeneratorExpression.PhotoRef(id, index, 1000, 1000, ('a' + index).toString().repeat(64))
            },
            note = null,
            music = null,
        ),
        placements = contentIds.mapIndexed { index, id ->
            GeneratorExpression.Placement(id, index * 120, 0, 120, 213, GeneratorExpression.CropWindow(0, 0, 10000, 10000), null, null)
        },
        noteTreatment = "regular-region",
        diagnostics = emptyList(),
        fontVersion = FontRegistry.VERSION,
        paletteVersion = CollageRenderer.PALETTE_REGISTRY_VERSION,
        expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V4,
        noteRegion = null,
        style = GeneratorExpression.StyleBinding("signal", requireNotNull(StyleRegistry.style("signal")).version, requireNotNull(StyleRegistry.digest("signal"))),
        noteType = GeneratorExpression.TextType("StudySans", 22, 400, false, 25),
        musicRegion = GeneratorExpression.MusicRegion(
            x = 16, y = 539, width = 328, height = 64, mode = "voice-plate",
            type = GeneratorExpression.TextType("StudySans", 20, 400, false, 23),
            artwork = null,
        ),
        provenance = BranchProvenance.marksFor(12, "score-3-equal", "alice"),
        decor = listOf(GeneratorExpression.SceneDecor(GeneratorExpression.DecorKind.CARRIER, 6, 5, 349, 627)),
    )

    private fun voice() = ManifestVoice(UUID.fromString("b1000000-0000-4000-8000-000000000009"), 42_400, ByteArray(peaks.size) { peaks[it].toByte() })

    private fun state(sound: () -> ByteArray?) = CollagePresentationState(
        expression = expression(),
        loadPhoto = { it.toByteArray() },
        voice = voice(),
        loadVoice = sound,
        decoder = { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap() },
    ).also { runBlocking { it.load() } }

    private class FakePlayer : VoicePlayer {
        override var playing by mutableStateOf(false)
        var released = false

        override fun toggle() {
            playing = !playing
        }

        override fun release() {
            released = true
        }
    }

    @Test
    fun anOpenVoiceCardHoldsItsSoundAndTheBandIsTheButtonUntilItCloses() {
        val sound = byteArrayOf(0, 0, 0, 16) + "ftypM4A ".toByteArray()
        val state = state { sound }
        assertTrue(state.status is CollagePresentationState.Status.Ready)
        composeRule.setContent { CollagePresentationHost(state, "collage", onClose = {}) }
        composeRule.onNodeWithTag("capsule_voice_band").assertContentDescriptionEquals("listen to the voice note")
        composeRule.onNodeWithTag("capsule_voice_pause", useUnmergedTree = true).assertDoesNotExist()
        state.close()
        assertTrue(sound.all { it == 0.toByte() })
    }

    @Test
    fun aVoiceNoteWhoseSoundIsMissingDoesNotOpen() {
        val state = state { null }
        assertTrue(state.status is CollagePresentationState.Status.Unsupported)
    }

    @Test
    fun theBandPlaysAndPausesUnderAPauseGlyph() {
        val scene = (
            CollageRenderer.prepare(
                expression(), null, null,
                contentIds.associateWith { CollageRenderer.SourcePhoto(1000, 1000) },
                object : CollageRenderer.TextMeasurer {
                    override fun measure(request: CollageRenderer.TextMeasureRequest) =
                        CollageRenderer.TextMeasurement(minOf(request.maxWidth, request.text.length * 8), request.line, false)
                },
                null,
                SongBand.VoiceWords(peaks, 42_400),
            ) as CollageRenderer.SceneResult.Ready
            ).scene
        val player = FakePlayer()
        composeRule.setContent { Box(Modifier.size(360.dp, 640.dp)) { VoiceBand(scene, player) } }
        val band = composeRule.onNodeWithTag("capsule_voice_band")
        band.performClick()
        assertTrue(player.playing)
        band.assertContentDescriptionEquals("pause the voice note")
        composeRule.onNodeWithTag("capsule_voice_pause", useUnmergedTree = true).assertExists()
        band.performClick()
        assertEquals(false, player.playing)
        composeRule.onNodeWithTag("capsule_voice_pause", useUnmergedTree = true).assertDoesNotExist()
        band.assertContentDescriptionEquals("listen to the voice note")
    }
}
