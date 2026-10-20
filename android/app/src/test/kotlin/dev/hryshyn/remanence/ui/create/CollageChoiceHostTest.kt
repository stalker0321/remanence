package dev.hryshyn.remanence.ui.create

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CollageCropSource
import dev.hryshyn.remanence.ui.collage.StyleRegistry
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun collageChoiceJpeg(): ByteArray {
    val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(0xFF336699.toInt())
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
    bitmap.recycle()
    return output.toByteArray()
}

/**
 * Sender collage chooser host: the first candidate displays as soon as it is
 * ready (no freeze), rendering through the shared CollageCanvas, and a missing
 * authoritative analysis shows typed copy instead of a grid.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollageChoiceHostTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val contentIds = listOf("p0", "p1", "p2")

    private fun input() = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 1L,
        photos = contentIds.mapIndexed { index, id ->
            GeneratorExpression.PhotoRef(id, index, 1000, 1000, ('a' + index).toString().repeat(64))
        },
        note = null,
        music = null,
    )

    // Source bytes and the crop provider have stable identities, just like
    // the retained production preview. Recomposition must not restart work.
    private val retainedSources by lazy {
        contentIds.map { GeneratorPreviewSource(it, collageChoiceJpeg(), 8, 8) }
    }
    private fun sources() = retainedSources

    private val retainedCropSource by lazy {
        CollageCropSource { requests ->
            requests.mapIndexed { index, request ->
                request.contentId to GeneratorExpression.CropWindow(index * 100, 0, 5000, 10000)
            }.toMap()
        }
    }
    private fun cropSource() = retainedCropSource

    private fun awaitCandidates(session: CollageChoiceSession) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            !session.isPreparing && session.candidates.isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    @Test fun photoHotspotsHaveDistinctLocalizedNamesAndSelectTheirOwnPhoto() = hotspotLabels("Crop photo", "of")
    @Test @Config(qualifiers = "ru")
    fun russianPhotoHotspots() = hotspotLabels("Кадрировать фото", "из")
    @Test @Config(qualifiers = "uk")
    fun ukrainianPhotoHotspots() = hotspotLabels("Кадрувати фото", "з")

    private fun hotspotLabels(prefix: String, separator: String) {
        val session = CollageChoiceSession()
        val tapped = mutableListOf<String>()
        val input = input()
        val sources = sources()
        val crops = cropSource()
        composeRule.setContent {
            CollageChoiceHost(session, input, sources, null, null, crops, onSlotTap = { tapped += it })
        }
        awaitCandidates(session)
        val placements = requireNotNull(session.displayed).expression.placements
        placements.forEachIndexed { index, placement ->
            composeRule.onNodeWithTag("crop_hotspot_$index")
                .assertContentDescriptionEquals("$prefix ${index + 1} $separator ${placements.size}").performClick()
            assertEquals(placement.contentId, tapped.last())
        }
    }

    @Test
    fun firstCandidateDisplaysWithoutFreeze() {
        val session = CollageChoiceSession()
        val input = input()
        val sources = sources()
        val crops = cropSource()
        composeRule.setContent {
            CollageChoiceHost(session, input, sources, null, null, crops)
        }
        awaitCandidates(session)

        // Ready candidates show the first sheet immediately; nothing is
        // selected or frozen by displaying it.
        composeRule.onNodeWithTag("collage_select_hint").assertDoesNotExist()
        composeRule.onNodeWithTag("collage_canvas").assertExists()
        composeRule.onNodeWithTag("collage_frozen").assertDoesNotExist()
        assertEquals(0, session.displayIndex)
        assertTrue(session.selectedIndex == null)
    }

    @Test
    fun explicitSelectionShowsSharedCanvas() {
        val session = CollageChoiceSession()
        val input = input()
        val sources = sources()
        val crops = cropSource()
        composeRule.setContent {
            CollageChoiceHost(session, input, sources, null, null, crops)
        }
        awaitCandidates(session)

        composeRule.runOnIdle { session.select(0) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("collage_canvas").assertExists()
    }

    @Test
    fun displayedCanvasHasNonZeroHeightUnderVerticalScroll() {
        val session = CollageChoiceSession()
        composeRule.setContent {
            Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                CollageChoiceHost(session, input(), sources(), null, null, cropSource())
            }
        }
        awaitCandidates(session)

        // The displayed sheet is present without any explicit selection.
        composeRule.onNodeWithTag("collage_select_hint").assertDoesNotExist()

        val height = composeRule.onNodeWithTag("collage_canvas").fetchSemanticsNode().size.height
        assertTrue("sealed-aspect canvas height must be > 0 under verticalScroll, was $height", height > 0)
        composeRule.onNodeWithTag("collage_canvas_content", useUnmergedTree = true).assertExists()
    }

    @Test
    fun displayedCandidateRendersNonPaperPixelsAndNextChangesThem() {
        val session = CollageChoiceSession()
        composeRule.setContent {
            Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                CollageChoiceHost(session, input(), sources(), null, null, cropSource())
            }
        }
        awaitCandidates(session)

        val first = renderedContentPixels()
        val firstNonPaper = first.count { it != paperOfDisplayed(session) }
        assertTrue("candidate 0 must render photographic pixels, got $firstNonPaper", firstNonPaper >= 1000)

        composeRule.onNodeWithTag("collage_next").performScrollTo()
        composeRule.onNodeWithTag("collage_next").performClick()
        composeRule.waitForIdle()
        assertEquals(1, session.displayIndex)
        assertTrue(session.selectedIndex == null)

        val second = renderedContentPixels()
        val secondNonPaper = second.count { it != paperOfDisplayed(session) }
        assertTrue("candidate 1 must render photographic pixels, got $secondNonPaper", secondNonPaper >= 1000)
        assertTrue("Next must change composition pixels", !first.contentEquals(second))
    }

    @Test
    fun nextAfterFitSurvivesResizeRearm() {
        // B3: overflow re-arms on displayIndex and viewport width, not just
        // structure. Wide (fits) -> Next taps keep full labels; shrink ->
        // scroll fallback re-engages with labels intact (no clip); expand
        // -> plain-Row taps work again.
        val session = CollageChoiceSession()
        var panelWidth by mutableStateOf(360.dp)
        composeRule.setContent {
            Column(Modifier.width(panelWidth).verticalScroll(rememberScrollState())) {
                CollageChoiceHost(session, input(), sources(), null, null, cropSource())
            }
        }
        composeRule.waitUntil(timeoutMillis = 10_000) { session.candidates.isNotEmpty() }
        composeRule.waitForIdle()

        fun selectWidth() =
            composeRule.onNodeWithTag("collage_select").fetchSemanticsNode().boundsInRoot.width
        val natural = selectWidth()
        assertTrue("Select natural width, was $natural", natural >= 80f)

        // Next at fit width: taps land, labels keep natural widths.
        val steps = minOf(2, session.candidates.lastIndex)
        repeat(steps) {
            composeRule.onNodeWithTag("collage_next").performScrollTo()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("collage_next").performClick()
            composeRule.waitForIdle()
        }
        assertEquals(steps, session.displayIndex)
        val afterNext = selectWidth()
        assertEquals(natural, afterNext, 1f)
        val afterNextHeight = composeRule.onNodeWithTag("collage_select")
            .fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            "Select stays one line after Next, height $afterNextHeight",
            afterNextHeight < 80f,
        )

        // Shrink below natural: overflow re-arms, Select reachable scrolled.
        composeRule.runOnIdle { panelWidth = 250.dp }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("collage_select").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("collage_select").assertIsDisplayed()
        val shrunk = selectWidth()
        assertEquals(natural, shrunk, 1f)
        val shrunkHeight = composeRule.onNodeWithTag("collage_select")
            .fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            "Select stays one line when scrolled, height $shrunkHeight",
            shrunkHeight < 80f,
        )

        // Expand back: plain Row again, direct tap works without scrolling.
        composeRule.runOnIdle { panelWidth = 360.dp }
        composeRule.waitForIdle()
        if (session.displayIndex < session.candidates.lastIndex) {
            val before = session.displayIndex
            composeRule.onNodeWithTag("collage_next").performClick()
            composeRule.waitForIdle()
            assertEquals(before + 1, session.displayIndex)
        }
    }

    @Test
    fun gatedRebuildRepostsProofForFirstSelect() {
        // Discriminating regression for the equal-rebuild silence
        // (scout-select-drawable): a retained candidate report fires,
        // THEN a gated async builder completes with value-equal
        // candidates and the proofs clear; the host must re-report
        // (build-generation key) so the FIRST displayed Select succeeds
        // without Next. Without the key this test fails (proof never
        // returns, Select refused). The VM store/clear rules are mimicked
        // faithfully (ready-only store, clear on replace); all mounting,
        // building, reporting and clicking go through real composition.
        val session = CollageChoiceSession()
        val proofs = mutableMapOf<String, Unit>()
        var accepted by mutableStateOf(false)
        session.onCandidatesReplaced = { proofs.clear() }
        var useGated = false
        var gatedResult: List<dev.hryshyn.remanence.ui.collage.CollageCandidate>? = null
        val gate = CompletableDeferred<Unit>()
        var showHost by mutableStateOf(true)
        composeRule.setContent {
            if (showHost) {
                CollageChoiceHost(
                    session,
                    input(),
                    sources(),
                    null,
                    null,
                    cropSource(),
                    candidateBuilder = {
                        if (!useGated) {
                            null
                        } else {
                            gate.await()
                            gatedResult
                        }
                    },
                    onSelect = { index ->
                        if (session.select(index) &&
                            session.selected?.let { proofs.containsKey(it.candidateId) } == true
                        ) {
                            accepted = true
                        }
                    },
                    onPrepared = { id, ready, _ ->
                        if (ready) {
                            proofs[id] = Unit
                        }
                    },
                    prepareInputs = PrepareInputs(null, null, null),
                )
            }
        }
        // Phase 1: ungated V3 path builds real candidates; retained report
        // fires before anything else can clear it.
        composeRule.waitUntil(timeoutMillis = 10_000) { session.candidates.isNotEmpty() }
        composeRule.waitForIdle()
        val firstId = requireNotNull(session.displayed?.candidateId)
        composeRule.waitUntil(timeoutMillis = 10_000) { proofs.containsKey(firstId) }
        // Row fits at this width (plain Row, no scroller): click directly.
        composeRule.onNodeWithTag("collage_select").performClick()
        composeRule.waitForIdle()
        assertTrue("baseline Select must be accepted", accepted)
        accepted = false
        val retained = session.candidates.toList()

        // Phase 2: explicitly request a fresh build, then remount with its
        // gated builder held. A same-input remount alone now revalidates the
        // authored cohort; this test still exercises VALUE-EQUAL replacement.
        composeRule.runOnIdle {
            useGated = true
            gatedResult = retained
            showHost = false
            session.clear()
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { showHost = true }
        composeRule.waitForIdle()
        // Preparation invalidates retained candidates and proofs together.
        assertTrue(session.isPreparing)
        assertTrue(session.candidates.isEmpty())
        assertTrue(proofs.isEmpty())
        composeRule.onNodeWithTag("collage_select").assertDoesNotExist()
        composeRule.runOnIdle { gate.complete(Unit) }
        composeRule.waitUntil(timeoutMillis = 10_000) { session.candidates.isNotEmpty() }
        composeRule.waitForIdle()

        // ...value-equal candidates, same first id...
        assertEquals(retained.size, session.candidates.size)
        assertEquals(firstId, session.displayed?.candidateId)
        // ...then the host re-reports, so FIRST Select (no Next) succeeds.
        composeRule.waitUntil(timeoutMillis = 10_000) { proofs.containsKey(firstId) }
        composeRule.onNodeWithTag("collage_select").performClick()
        composeRule.waitForIdle()
        assertTrue("first Select after equal rebuild must be accepted", accepted)
    }

    @Test
    fun unavailableAnalysisShowsNoticeWithoutCanvas() {
        val session = CollageChoiceSession()
        val input = input()
        val sources = sources()
        val unavailable = CollageCropSource { null }
        composeRule.setContent {
            CollageChoiceHost(session, input, sources, null, null, unavailable)
        }
        composeRule.waitUntil(timeoutMillis = 10_000) { !session.isPreparing && session.notice != null }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("collage_notice").assertExists()
        composeRule.onNodeWithTag("collage_canvas").assertDoesNotExist()
        composeRule.onNodeWithTag("collage_select_hint").assertDoesNotExist()
    }

    @Test
    fun selectFreezesDisplayedCandidateAndNavigateInvalidates() {
        val session = CollageChoiceSession()
        val selected = mutableListOf<Int>()
        val navigated = mutableListOf<Unit>()
        composeRule.setContent {
            CollageChoiceHost(
                session, input(), sources(), null, null, cropSource(),
                onSelect = { selected += it },
                onNavigate = { navigated += Unit },
            )
        }
        awaitCandidates(session)

        // The displayed first candidate can be frozen without prior browsing.
        composeRule.onNodeWithTag("collage_select").assertIsEnabled()
        composeRule.onNodeWithTag("collage_select").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(0), selected)
        // Moving the cursor clears the highlight and reports navigation so
        // the caller invalidates the older freeze.
        composeRule.onNodeWithTag("collage_next").performClick()
        composeRule.waitForIdle()
        assertEquals(1, session.displayIndex)
        assertTrue(session.selectedIndex == null)
        assertEquals(1, navigated.size)
        composeRule.onNodeWithTag("collage_select").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(0, 1), selected)
    }

    @Test
    fun standaloneHostKeepsBrowseCounterWhenOwnerSelects() {
        val session = CollageChoiceSession()
        composeRule.setContent {
            CollageChoiceHost(session, input(), sources(), null, null, cropSource())
        }
        awaitCandidates(session)

        // Browse state: N/total counter, no selected label.
        composeRule.onNodeWithTag("collage_nav_counter").assertExists()
        composeRule.onNodeWithTag("collage_selected_label").assertDoesNotExist()

        composeRule.runOnIdle { session.select(0) }
        composeRule.waitForIdle()

        // Selection presentation belongs to the enclosing creation flow;
        // the standalone host keeps its browse controls intact.
        composeRule.onNodeWithTag("collage_selected_label").assertDoesNotExist()
        composeRule.onNodeWithTag("collage_nav_counter").assertExists()
    }

    @Test
    fun rejectedPrepareNeverEntersBrowseList() {
        // Hand-sealed V4 pulse band too narrow for its 57px cover: prepare
        // rejects deterministically with real fonts; Select must stay
        // disabled and onSelect must never fire.
        val candidate = rejectedMusicCandidate()
        val session = CollageChoiceSession()
        val selected = mutableListOf<Int>()
        val coverBytes = collageChoiceJpeg()
        val coverBitmap = android.graphics.BitmapFactory
            .decodeByteArray(coverBytes, 0, coverBytes.size).asImageBitmap()
        val track = dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1.parse(
            trackId = "be30e36b-1111-4111-8111-000000000001",
            title = "Love",
            artistDisplay = "MowMowMow",
            version = null,
            durationMs = 253000L,
        )
        composeRule.setContent {
            CollageChoiceHost(
                session, input(), sources(), null, track, cropSource(),
                artworkBitmap = coverBitmap,
                candidateBuilder = { listOf(candidate) },
                onSelect = { selected += it },
            )
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) { !session.isPreparing && session.notice != null }
        assertTrue(session.candidates.isEmpty())
        composeRule.onNodeWithTag("collage_notice").assertExists()
        composeRule.onNodeWithTag("collage_canvas").assertDoesNotExist()
        composeRule.onNodeWithTag("collage_select").assertDoesNotExist()
        composeRule.waitForIdle()
        assertTrue(selected.isEmpty())
    }

    @Test
    fun previewMusicOpensServicePickerWhilePhotoCropAndSelectionRemainIndependent() {
        val base = rejectedMusicCandidate()
        val expression = base.expression.copy(
            musicRegion = requireNotNull(base.expression.musicRegion).copy(width = 340),
        )
        val candidate = base.copy(
            candidateId = dev.hryshyn.remanence.core.model.GeneratorBer1Provider.candidateIdFor(expression),
            expression = expression,
        )
        val track = dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1.parse(
            "be30e36b-1111-4111-8111-000000000001", "Love", "MowMowMow", null, 253000L,
        )
        val bytes = collageChoiceJpeg()
        val cover = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
        val session = CollageChoiceSession()
        val urls = mutableListOf<String>()
        val crops = mutableListOf<String>()
        composeRule.setContent {
            val action = dev.hryshyn.remanence.ui.capsule.music.rememberTrackServiceAction(track) {
                urls += it
                true
            }
            Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                CollageChoiceHost(
                    session, input(), sources(), null, track, cropSource(),
                    artworkBitmap = cover, candidateBuilder = { listOf(candidate) },
                    onSlotTap = { crops += it }, onMusicClick = action, musicActionLabel = "Open Love",
                )
            }
        }
        awaitCandidates(session)
        composeRule.onNodeWithTag("capsule_music_region").performScrollTo().performClick()
        composeRule.onNodeWithTag("capsule_track_service_picker").assertIsDisplayed()
        val spotify = dev.hryshyn.remanence.ui.capsule.music.StreamingService.SPOTIFY
        composeRule.onNodeWithTag(spotify.testTag).performClick()
        assertEquals(listOf(spotify.searchUrl(track.title, track.artistDisplay)), urls)
        assertTrue(crops.isEmpty())
        assertTrue(session.selectedIndex == null)
        composeRule.onNodeWithTag("crop_hotspot_0").performScrollTo().performClick()
        assertEquals(listOf(contentIds.first()), crops)
        assertTrue(session.selectedIndex == null)
    }

    @Test
    fun smallViewportKeepsEntireCanvasInsideHorizontalBounds() {
        val session = CollageChoiceSession()
        composeRule.setContent {
            Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                CollageChoiceHost(session, input(), sources(), null, null, cropSource())
            }
        }
        awaitCandidates(session)

        // The whole sheet fits the bounded width: no horizontal overflow,
        // the canvas is fully inside the root (vertical scroll, not crop).
        val bounds = composeRule.onNodeWithTag("collage_canvas").fetchSemanticsNode().boundsInRoot
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("canvas height must be > 0, was ${bounds.height}", bounds.height > 0f)
        assertTrue(
            "canvas must not overflow horizontally: $bounds not in $root",
            bounds.left >= root.left - 1f && bounds.right <= root.right + 1f,
        )
    }

    @Test
    fun frozenCandidateIdRendersWhenProvided() {
        val session = CollageChoiceSession()
        composeRule.setContent {
            CollageChoiceHost(
                session, input(), sources(), null, null, cropSource(),
                frozenCandidateId = { "ber1-frozen" },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("collage_frozen").assertExists()
    }

    @Test
    fun mixedBuildKeepsFirstDrawablePermutationOfEachVariant() {
        val rejected = rejectedMusicCandidate()
        val fittingExpression = rejected.expression.copy(
            musicRegion = requireNotNull(rejected.expression.musicRegion).copy(width = 340),
        )
        val fitting = rejected.copy(
            candidateId = dev.hryshyn.remanence.core.model.GeneratorBer1Provider.candidateIdFor(fittingExpression),
            expression = fittingExpression,
        )
        val permutationExpression = fittingExpression.copy(
            branchId = "other-assignment",
            placements = fittingExpression.placements.mapIndexed { index, placement ->
                placement.copy(x = (2 - index) * 120)
            },
        )
        val permutation = fitting.copy(
            candidateId = dev.hryshyn.remanence.core.model.GeneratorBer1Provider.candidateIdFor(permutationExpression),
            branchId = permutationExpression.branchId,
            expression = permutationExpression,
        )
        val session = CollageChoiceSession()
        val track = dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1.parse(
            trackId = "be30e36b-1111-4111-8111-000000000001", title = "Love",
            artistDisplay = "MowMowMow", version = null, durationMs = 253000L,
        )
        val cover = android.graphics.BitmapFactory.decodeByteArray(
            collageChoiceJpeg(), 0, collageChoiceJpeg().size,
        ).asImageBitmap()
        val selected = mutableListOf<Int>()
        composeRule.setContent {
            CollageChoiceHost(
                session, input(), sources(), null, track, cropSource(),
                artworkBitmap = cover, candidateBuilder = { listOf(rejected, fitting, permutation) },
                onSelect = { selected += it },
            )
        }
        composeRule.waitUntil(10_000) { !session.isPreparing && session.candidates.isNotEmpty() }
        composeRule.waitForIdle()
        assertEquals(listOf(fitting.candidateId), session.candidates.map { it.candidateId })
        composeRule.onNodeWithTag("collage_canvas").assertExists()
        composeRule.onNodeWithTag("collage_notice").assertDoesNotExist()
        composeRule.onNodeWithTag("collage_next").assertIsNotEnabled()
        composeRule.onNodeWithTag("collage_select").assertIsEnabled().performClick()
        assertEquals(listOf(0), selected)
    }

    private fun rejectedMusicCandidate(): dev.hryshyn.remanence.ui.collage.CollageCandidate {
        val style = requireNotNull(StyleRegistry.style("signal"))
        val expression = GeneratorExpression.ResolvedExpression(
            canvasVersion = 1,
            grammarId = "6",
            grammarVersion = 3,
            branchId = "hinge",
            input = input(),
            placements = contentIds.mapIndexed { index, id ->
                GeneratorExpression.Placement(
                    id, index * 120, 0, 120, 213,
                    GeneratorExpression.CropWindow(0, 0, 10000, 10000),
                    null, null,
                )
            },
            noteTreatment = "regular-region",
            diagnostics = emptyList(),
            fontVersion = dev.hryshyn.remanence.ui.collage.FontRegistry.VERSION,
            paletteVersion = dev.hryshyn.remanence.ui.collage.CollageRenderer.PALETTE_REGISTRY_VERSION,
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V4,
            noteRegion = null,
            style = GeneratorExpression.StyleBinding(
                style.id, style.version, requireNotNull(StyleRegistry.digest(style.id)),
            ),
            noteType = GeneratorExpression.TextType("StudySans", 22, 400, false, 25),
            musicRegion = GeneratorExpression.MusicRegion(
                x = 10, y = 500, width = 60, height = 76,
                mode = "pulse",
                type = GeneratorExpression.TextType("Narrow", 24, 700, false, 26),
                artwork = GeneratorExpression.ArtworkRef(
                    provider = "catalog-sample",
                    url = "https://art.example.org/cover/ab12.webp",
                    sha256 = ByteArray(32) { it.toByte() },
                    mimeType = "image/webp",
                    width = 8,
                    height = 8,
                ),
            ),
            provenance = null,
            decor = null,
        )
        return dev.hryshyn.remanence.ui.collage.CollageCandidate(
            candidateId = dev.hryshyn.remanence.core.model.GeneratorBer1Provider.candidateIdFor(expression),
            styleId = "signal",
            branchId = "hinge",
            expression = expression,
        )
    }

    private fun renderedContentPixels(): IntArray {
        // PixelCopy has no real Surface producer on Robolectric (see
        // HoldVisualReviewTest): draw the measured native View tree directly.
        composeRule.onNodeWithTag("collage_canvas").performScrollTo()
        composeRule.waitForIdle()
        val node = composeRule.onNodeWithTag("collage_canvas").fetchSemanticsNode()
        var pixels = IntArray(0)
        composeRule.runOnIdle {
            val view = composeRule.activity.window.decorView
            val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(full))
            val left = node.positionInRoot.x.toInt().coerceIn(0, full.width - 1)
            val top = node.positionInRoot.y.toInt().coerceIn(0, full.height - 1)
            val right = (left + node.size.width).coerceIn(left + 1, full.width)
            val bottom = (top + node.size.height).coerceIn(top + 1, full.height)
            val out = IntArray((right - left) * (bottom - top))
            full.getPixels(out, 0, right - left, left, top, right - left, bottom - top)
            pixels = out
        }
        return pixels
    }

    private fun paperOfDisplayed(session: CollageChoiceSession): Int {
        val styleId = requireNotNull(session.displayed).styleId
        val paper = requireNotNull(StyleRegistry.style(styleId)).palette.paper
        return 0xFF000000.toInt() or paper.substring(1).toInt(16)
    }
}
