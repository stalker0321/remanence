package dev.hryshyn.remanence.ui.collage

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorExpression
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * T154 spine music repair on real bundled-font metrics at density 1.
 *
 * The frozen spine is TWO nowrap nodes stacked on the reading axis
 * (`.music-label` at the sealed face, `.music .track` at 11px), not one
 * joined two-line run — the joined run always exceeded the one-line cap
 * and rejected every normal track. These tests pin prepare acceptance for
 * the exact grammar-6 spine fixture, the fail-closed long-title reject,
 * the separately measured/painted artist face, the shared budgets and
 * placements, and the bounded DEBUG arm diagnostic (reason/numeric only).
 *
 * Real font metrics require `@GraphicsMode(NATIVE)`; the bundled
 * `res/font` faces keep the measurements deterministic. Each test sets
 * content exactly once.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollageSpineMusicTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val contentIds = listOf("p0", "p1", "p2")

    private var densitySeen = 0f

    private fun input() = GeneratorExpression.GeneratorInput(
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
    )

    private fun placement(id: String, x: Int) = GeneratorExpression.Placement(
        id, x, 0, 120, 213,
        GeneratorExpression.CropWindow(0, 0, 10000, 10000),
        null, null,
        GeneratorExpression.PhotoShape.RECT,
    )

    private fun styleBinding(styleId: String): GeneratorExpression.StyleBinding {
        val style = requireNotNull(StyleRegistry.style(styleId))
        return GeneratorExpression.StyleBinding(
            styleId = style.id,
            styleVersion = style.version,
            digest = requireNotNull(StyleRegistry.digest(style.id)),
        )
    }

    /** Valid full fixture: contract V4, grammar 6, spine 48x591, no note. */
    private fun spineExpression(): GeneratorExpression.ResolvedExpression =
        GeneratorExpression.ResolvedExpression(
            canvasVersion = 1,
            grammarId = "6",
            grammarVersion = 3,
            branchId = "hinge",
            input = input(),
            placements = listOf(
                placement("p0", 0),
                placement("p1", 120),
                placement("p2", 240),
            ),
            noteTreatment = "regular-region",
            diagnostics = emptyList(),
            fontVersion = FontRegistry.VERSION,
            paletteVersion = CollageRenderer.PALETTE_REGISTRY_VERSION,
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V4,
            noteRegion = null,
            style = styleBinding("signal"),
            noteType = GeneratorExpression.TextType("StudySans", 22, 400, false, 25),
            // Frozen g6 no-note spine: bottom 615 -> Rect(156, 0, 48, 591)
            // (`expansion.js:37-40`); signal seals Narrow 24/700 line 27.
            musicRegion = GeneratorExpression.MusicRegion(
                x = 156, y = 0, width = 48, height = 591,
                mode = "spine",
                type = GeneratorExpression.TextType("Narrow", 24, 700, false, 27),
                artwork = null,
            ),
            provenance = BranchProvenance.marksFor(12, "score-3-equal", "alice"),
            decor = listOf(
                GeneratorExpression.SceneDecor(
                    GeneratorExpression.DecorKind.CARRIER, 6, 5, 349, 627,
                ),
            ),
        )

    private fun track(title: String, artist: String) = CapsuleTrackSnapshotV1.parse(
        trackId = "be30e36b-1111-4111-8111-000000000001",
        title = title,
        artistDisplay = artist,
        version = null,
        durationMs = 253000L,
    )

    private fun sources(): Map<String, CollageRenderer.SourcePhoto> =
        contentIds.associateWith { CollageRenderer.SourcePhoto(1000, 1000) }

    private fun images(): Map<String, androidx.compose.ui.graphics.ImageBitmap> =
        contentIds.associateWith {
            // Must cover the sealed source rect (0,0,1000,1000) or the
            // painter fails closed with no content node.
            android.graphics.Bitmap.createBitmap(1000, 1000, android.graphics.Bitmap.Config.ARGB_8888)
                .asImageBitmap()
        }

    private fun musicFitLogs(): List<ShadowLog.LogItem> =
        ShadowLog.getLogs().filter { it.tag == "CollageMusicFit" }

    @Test
    fun validV4SpineFixtureValidatesAndPreparesReadyWithRealFonts() {
        val expression = spineExpression()
        assertTrue(
            "full spine fixture must validateResolved",
            GeneratorExpression.validateResolved(expression) is GeneratorExpression.InputValidation.Valid,
        )
        var result: CollageRenderer.SceneResult? = null
        composeRule.setContent {
            densitySeen = LocalDensity.current.density
            val measurer = rememberCollageTextMeasurer()
            result = remember(measurer, expression) {
                CollageRenderer.prepare(expression, null, track("Love", "MowMowMow"), sources(), measurer)
            }
        }
        composeRule.waitForIdle()
        val scene = (requireNotNull(result) as CollageRenderer.SceneResult.Ready).scene
        assertEquals(1f, densitySeen, 0f)
        val music = requireNotNull(scene.music)
        assertTrue(music.vertical)
        assertEquals("spine", music.mode)
        assertEquals("Love", music.title)
        assertEquals("MowMowMow", music.artist)
        // Reason-only diagnostic: no log on the Ready path.
        assertTrue("Ready must not emit music-fit logs", musicFitLogs().isEmpty())
    }

    @Test
    fun overlongTitleRejectsFailClosedAndLogsNumericArmOnly() {
        val longTitle = "Love".repeat(25) // 100 chars = MAX_TITLE_CHARS
        var result: CollageRenderer.SceneResult? = null
        composeRule.setContent {
            val measurer = rememberCollageTextMeasurer()
            result = remember(measurer) {
                CollageRenderer.prepare(
                    spineExpression(),
                    null,
                    track(longTitle, "MowMowMow"),
                    sources(),
                    measurer,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(
            "music does not fit its sealed region",
            (requireNotNull(result) as CollageRenderer.SceneResult.Rejected).reason,
        )
        val logs = musicFitLogs()
        assertTrue("reject must emit exactly one bounded music-fit log", logs.size == 1)
        val message = logs.first().msg
        assertTrue(message, message.contains("reject arm=spine"))
        assertTrue(message, message.contains("contract=4"))
        assertTrue(message, message.contains("grammar=6"))
        assertTrue(message, message.contains("branch=hinge"))
        assertTrue(message, message.contains("mode=spine"))
        assertTrue(message, message.contains("region=156,0,48,591"))
        assertTrue(message, message.contains("type=Narrow/24/700/roman/line27"))
        assertTrue(message, message.contains("measured=title="))
        assertTrue(message, message.contains("artist="))
        // Reason/numeric only: no raw title, artist, or note bytes.
        assertFalse(message, message.contains(longTitle))
        assertFalse(message, message.contains("MowMowMow"))
        assertFalse(message, message.contains("Love"))
    }

    @Test
    fun spineRunsUseSealedLabelAndCssArtistFaceWithSharedBudgets() {
        var title: TextLayoutResult? = null
        var artist: TextLayoutResult? = null
        var fit: CollageRenderer.SpineCopyFit? = null
        var place: CollageRenderer.SpineCopyPlacements? = null
        var music: CollageRenderer.SceneMusic? = null
        composeRule.setContent {
            val measurer = rememberCollageTextMeasurer()
            val result = remember(measurer) {
                CollageRenderer.prepare(
                    spineExpression(),
                    null,
                    track("Love", "MowMowMow"),
                    sources(),
                    measurer,
                )
            }
            val scene = (result as? CollageRenderer.SceneResult.Ready)?.scene
            val sealed = scene?.music
            if (sealed != null) {
                music = sealed
                val layouts = remember(measurer, scene) {
                    measurer.layoutSpine(sealed, scene.grammarId)
                }
                title = layouts.title
                artist = layouts.artist
                val band = V5Decor.bandLayout(scene.grammarId, "spine", sealed.region.height)
                fit = CollageRenderer.spineCopyFit(
                    sealed.region,
                    band,
                    layouts.title?.let {
                        CollageRenderer.TextMeasurement(it.size.width, it.size.height, it.hasVisualOverflow)
                    },
                    layouts.artist?.let {
                        CollageRenderer.TextMeasurement(it.size.width, it.size.height, it.hasVisualOverflow)
                    },
                )
                place = CollageRenderer.spineCopyPlacements(
                    region = sealed.region,
                    band = band,
                    titleAdvance = layouts.title!!.size.width,
                    titleLineBox = layouts.title!!.size.height,
                    artistLineBox = layouts.artist!!.size.height,
                )
            }
        }
        composeRule.waitForIdle()

        val musicScene = requireNotNull(music) { "prepare must be Ready" }
        val titleLayout = requireNotNull(title)
        val artistLayout = requireNotNull(artist)
        // Label stays at the sealed face (Narrow 24/700, line 27); artist
        // gets its own 11px/line 13 track face
        // (`.g6 .music-spine .track{font-size:11px}` +
        // `.music .track{line-height:1.15}` -> ceil(11*1.15)).
        assertEquals(24f, titleLayout.layoutInput.style.fontSize.value, 0f)
        assertEquals(700, titleLayout.layoutInput.style.fontWeight?.weight ?: 0)
        assertEquals(27f, titleLayout.layoutInput.style.lineHeight.value, 0f)
        assertEquals(11f, artistLayout.layoutInput.style.fontSize.value, 0f)
        assertEquals(13f, artistLayout.layoutInput.style.lineHeight.value, 0f)
        // Single-line measured box of the bundled 11px face at density 1
        // (the layout reports the font box; the 13px CSS line box stays the
        // style fact above — exact line-box draw parity is Not verified).
        assertEquals(11, artistLayout.size.height)
        assertTrue(artistLayout.size.width >= 1)
        assertTrue(artistLayout.size.height <= 42)
        assertFalse(artistLayout.hasVisualOverflow)

        val band = V5Decor.bandLayout("6", "spine", 591)
        assertEquals(531, CollageRenderer.spineReadingBudget(musicScene.region, band))
        assertEquals(42, CollageRenderer.spineCrossBudget(musicScene.region, band))
        val copyFit = requireNotNull(fit)
        assertTrue(copyFit.fits)
        assertEquals(
            titleLayout.size.width + CollageRenderer.SPINE_COPY_GAP + artistLayout.size.width,
            copyFit.usedReading,
        )
        assertTrue(copyFit.usedReading <= 531)

        val placements = requireNotNull(place)
        // Reading starts after padTop 8 + play 34 + band gap 10; artist
        // follows the title advance + 6px copy gap.
        assertEquals(52, placements.title.top)
        assertEquals(
            52 + titleLayout.size.width + CollageRenderer.SPINE_COPY_GAP,
            placements.artist.top,
        )
        assertEquals(placements.title.right, placements.artist.right)
        assertTrue(placements.title.right in 156..204)
        assertTrue(placements.artist.right in 156..204)
    }

    @Test
    fun joinedTwoLineRunAlwaysViolatesTheOneLineCap() {
        // The pre-fix gate measured `title + "\n" + artist` in one run and
        // rejected when its height exceeded `musicType.line` — with real
        // fonts two line boxes can never satisfy a one-line cap, which is
        // why every normal spine track rejected.
        var overflowed = false
        var overLine = false
        composeRule.setContent {
            val measurer = rememberCollageTextMeasurer()
            val result = remember(measurer) {
                CollageRenderer.prepare(
                    spineExpression(),
                    null,
                    track("Love", "MowMowMow"),
                    sources(),
                    measurer,
                )
            }
            val music = (result as? CollageRenderer.SceneResult.Ready)?.scene?.music
            if (music != null) {
                val joined = remember(measurer) {
                    measurer.measure(
                        CollageRenderer.TextMeasureRequest(
                            text = "Love\nMowMowMow",
                            family = music.family,
                            size = music.size,
                            weight = music.weight,
                            italic = music.italic,
                            line = music.line,
                            maxWidth = music.region.height,
                            maxHeight = music.region.width - 2 * 3,
                        ),
                    )
                }
                overflowed = joined.hasVisualOverflow || joined.height > music.region.width - 2 * 3
                overLine = joined.height > music.line
            }
        }
        composeRule.waitForIdle()
        assertTrue("joined two-line run overflows the cross budget", overflowed)
        assertTrue("joined two-line run violates the old one-line cap", overLine)
    }

    @Test
    fun canvasPaintsTheSpineSceneWithTheSharedDrawGeometry() {
        var ready = false
        composeRule.setContent {
            val measurer = rememberCollageTextMeasurer()
            val result = remember(measurer) {
                CollageRenderer.prepare(
                    spineExpression(),
                    null,
                    track("Love", "MowMowMow"),
                    sources(),
                    measurer,
                )
            }
            val scene = (result as? CollageRenderer.SceneResult.Ready)?.scene
            ready = scene != null
            if (scene != null) {
                CollageCanvas(scene, images(), "collage", textMeasurer = measurer)
            }
        }
        composeRule.waitForIdle()
        assertTrue("prepare must be Ready for the canvas regression", ready)
        composeRule.onNodeWithTag("collage_canvas_content", useUnmergedTree = true).assertExists()
        // The draw arm must consume the shared placements helper, so pixels
        // cannot drift from the prepare gate (source-level pin, same
        // convention as CollageCanvasTest's colour pin).
        val source = moduleFile(
            "src/main/kotlin/dev/hryshyn/remanence/ui/collage/CollageCanvas.kt",
        ).readText().replace(Regex("\\s+"), " ")
        assertTrue(source.contains("CollageRenderer.spineCopyPlacements("))
        assertTrue(source.contains("CollageRenderer.spineCopyFit("))
    }

    private fun moduleFile(relative: String): File {
        val prefixes = listOf("", "app/", "android/app/")
        var directory: File? = File("").absoluteFile
        repeat(6) {
            val base = directory
            if (base != null) {
                for (prefix in prefixes) {
                    val candidate = File(base, prefix + relative)
                    if (candidate.exists()) return candidate
                }
            }
            directory = directory?.parentFile
        }
        return File(relative)
    }
}
