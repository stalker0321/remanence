package dev.hryshyn.remanence.ui.collage

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorExpression
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T150 device-music-fit reproduction (read-only evidence
 * `tasks/T150/evidence/qa-0a32b71/15_selected.png`): the exact on-device
 * inputs — 4 photos, note `For you, with love.`, attached
 * `Love / MowMowMow` — through the production V3 provider with the REAL
 * bundled fonts. If every style's music text overflows its sealed region
 * here, the device `music does not fit its sealed region` rejection is a
 * genuine measurement outcome (not a display bug); if all fit, the cause
 * lies elsewhere (V4 path or different state) and this test documents that.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollageMusicDeviceReproTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val contentIds = listOf("p0", "p1", "p2", "p3")

    private fun input() = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 1L,
        photos = contentIds.mapIndexed { index, id ->
            GeneratorExpression.PhotoRef(id, index, 1000, 1000, ('a' + index).toString().repeat(64))
        },
        note = "For you, with love.",
        music = null,
    )

    private fun track() = CapsuleTrackSnapshotV1.parse(
        trackId = "be30e36b-1111-4111-8111-000000000001",
        title = "Love",
        artistDisplay = "MowMowMow",
        version = null,
        durationMs = 253000L,
    )

    @Test
    fun deviceInputsSealMusicCandidatesWithRealFonts() {
        var result: CollageProviderResult? = null
        var measurer: CollageTextMeasurer? = null
        composeRule.setContent {
            measurer = rememberCollageTextMeasurer()
            result = runBlocking {
                CollageCropSource { requests ->
                    requests.associate {
                        it.contentId to GeneratorExpression.CropWindow(0, 0, 10000, 10000)
                    }
                }.let { crops ->
                    CollageCandidateProvider(crops, measurer!!)
                        .candidates(input(), "For you, with love.", track())
                }
            }
        }
        composeRule.waitForIdle()
        val candidates = result as? CollageProviderResult.Candidates
        assertTrue(
            "device inputs must keep at least one music candidate (got $result); " +
                "a Rejected/Unavailable here reproduces the on-device music-fit rejection",
            candidates != null && candidates.candidates.isNotEmpty(),
        )
        val real = requireNotNull(measurer)
        for (candidate in candidates!!.candidates) {
            val region = requireNotNull(candidate.expression.musicRegion)
            val type = region.type
            val measurement = real.measure(
                CollageRenderer.TextMeasureRequest(
                    text = "Love\nMowMowMow",
                    family = type.family,
                    size = type.size,
                    weight = type.weight,
                    italic = type.italic,
                    line = type.line,
                    maxWidth = region.width,
                    maxHeight = region.height,
                ),
            )
            assertTrue(
                "${candidate.styleId} music must fit ${region.width}x${region.height} " +
                    "(measured ${measurement.width}x${measurement.height} " +
                    "overflow=${measurement.hasVisualOverflow})",
                !measurement.hasVisualOverflow &&
                    measurement.width in 1..region.width &&
                    measurement.height in 1..region.height,
            )
        }
    }
}
