package dev.hryshyn.remanence.ui.collage

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorExpression
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T049 defect-2 regression on the real bundled font metrics: the exact T034
 * three-photo set with an attached real track and either an empty or an
 * ordinary note must keep offering real candidates instead of failing the
 * whole provider.
 *
 * The causal branch is the music title/artist measurement: the `poster` style
 * seals a two-line music block at 24px / line 27 inside the frozen 48px music
 * box, so it genuinely does not fit and is omitted per style (the frozen engine
 * records exactly this as `Type does not fit`), while `press` (20px/line 23 ->
 * 43px) and `blue-letter` (22px/line 25 -> 47px) still seal. Crop/solver
 * omissions stay a fail-closed whole-result rejection.
 *
 * Real font metrics require `@GraphicsMode(NATIVE)`; the bundled `res/font`
 * faces make the measurements deterministic.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollageMusicGridAcceptanceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val contentIds = listOf("p0", "p1", "p2")
    private var lastMeasurer: CollageTextMeasurer? = null

    private fun input(note: String? = null) = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 1L,
        photos = contentIds.mapIndexed { index, id ->
            GeneratorExpression.PhotoRef(id, index, 1000, 1000, ('a' + index).toString().repeat(64))
        },
        note = note,
        music = null,
    )

    private fun track(title: String, artist: String) = CapsuleTrackSnapshotV1.parse(
        trackId = "be30e36b-1111-4111-8111-000000000001",
        title = title,
        artistDisplay = artist,
        version = null,
        durationMs = 253000L,
    )

    private fun cropSource(): CollageCropSource = CollageCropSource { requests ->
        requests.associate { it.contentId to GeneratorExpression.CropWindow(0, 0, 10000, 10000) }
    }

    private fun provide(note: String?, track: CapsuleTrackSnapshotV1?): CollageProviderResult {
        var result: CollageProviderResult? = null
        composeRule.setContent {
            val measurer = rememberCollageTextMeasurer()
            lastMeasurer = measurer
            result = runBlocking {
                CollageCandidateProvider(cropSource(), measurer).candidates(input(note), note, track)
            }
        }
        composeRule.waitForIdle()
        return requireNotNull(result)
    }

    private fun offeredStyles(note: String?, track: CapsuleTrackSnapshotV1?): List<String> {
        val result = provide(note, track)
        assertTrue("expected Candidates, got $result", result is CollageProviderResult.Candidates)
        return (result as CollageProviderResult.Candidates).candidates.map { it.styleId }
    }

    @Test
    fun withoutMusicAllThreeBundledStylesAreOffered() {
        assertEquals(listOf("press", "blue-letter", "poster"), offeredStyles(null, null))
    }

    @Test
    fun t034TripleWithRealTrackAndEmptyNoteRetainsCandidates() {
        val styles = offeredStyles(null, track("Love", "MowMowMow"))
        assertTrue("must retain at least one real candidate", styles.isNotEmpty())
        assertEquals(listOf("press", "blue-letter"), styles)
    }

    @Test
    fun t034TripleWithRealTrackAndOrdinaryNoteRetainsCandidates() {
        assertEquals(listOf("press", "blue-letter"), offeredStyles("T049qa1225", track("Love", "MowMowMow")))
    }

    @Test
    fun shorterArtistControlAlsoRetainsCandidates() {
        assertEquals(listOf("press", "blue-letter"), offeredStyles("x", track("Love", "Nach")))
    }

    @Test
    fun everyOfferedMusicCandidateFitsItsSealedRegion() {
        val result = provide(null, track("Love", "MowMowMow"))
        assertTrue("expected Candidates, got $result", result is CollageProviderResult.Candidates)
        val measurer = requireNotNull(lastMeasurer)
        val candidates = (result as CollageProviderResult.Candidates).candidates
        assertTrue(candidates.isNotEmpty())
        for (candidate in candidates) {
            val region = requireNotNull(candidate.expression.musicRegion)
            val type = region.type
            val measurement = measurer.measure(
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
                "${candidate.styleId} music must fit its sealed region",
                !measurement.hasVisualOverflow &&
                    measurement.width in 1..region.width &&
                    measurement.height in 1..region.height,
            )
        }
    }
}
