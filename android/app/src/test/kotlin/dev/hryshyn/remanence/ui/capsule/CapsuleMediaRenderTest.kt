package dev.hryshyn.remanence.ui.capsule

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.data.network.ArtworkFetcher
import dev.hryshyn.remanence.core.data.network.ArtworkFetchResult
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import dev.hryshyn.remanence.ui.collage.rememberCollageTextMeasurer
import dev.hryshyn.remanence.ui.hold.HoldTheme
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual native-graphics Compose layer + PNG encoder. NOT native share/device proof. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapsuleMediaRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val track = CapsuleTrackSnapshotV1.parse(
        "be30e36b-1111-4111-8111-000000000001", "Synthetic Song", "QA Artist", null, 120000L,
    )
    private lateinit var export: CapsuleImageExport
    private lateinit var scope: CoroutineScope

    @Test fun v3TextExport() = modern(3, false, false, "v3-text")
    @Test fun v3CoverExport() = modern(3, true, true, "v3-cover")
    @Test fun v3MissingCoverDegradesWithoutChangingExport() = modern(3, true, false, "v3-no-cover")
    @Test fun v4TextExport() = modern(4, false, false, "v4-text")
    @Test fun v4CoverExport() = modern(4, true, true, "v4-cover")
    @Test fun v4MissingCoverDegradesWithoutChangingExport() = modern(4, true, false, "v4-no-cover")
    @Test fun v3WithoutTrackExport() = modern(3, false, false, "v3-no-track", hasTrack = false)
    @Test fun v4WithoutTrackExport() = modern(4, false, false, "v4-no-track", hasTrack = false)
    @Test @Config(qualifiers = "ru-w320dp-h640dp-xhdpi")
    fun narrowRussianLargeText() = modern(4, true, true, "v4-ru-320-font18", fontScale = 1.8f)
    @Test @Config(qualifiers = "uk-w390dp-h844dp-xhdpi")
    fun ukrainianLargeText() = modern(3, true, false, "v3-uk-390-font15", fontScale = 1.5f)
    @Test @Config(qualifiers = "en-w640dp-h360dp-land-xhdpi")
    fun landscapeCover() = modern(4, true, true, "v4-landscape-cover")
    @Test @Config(qualifiers = "ru-w640dp-h360dp-land-xhdpi")
    fun landscapeNoCoverLargeText() = modern(3, true, false, "v3-landscape-no-cover-font15", fontScale = 1.5f)

    private fun modern(version: Int, descriptor: Boolean, verified: Boolean, name: String,
        fontScale: Float = 1f, hasTrack: Boolean = true) {
        val cover = mediaPng(80, 80, MEDIA_COVER_COLOR)
        val expression = mediaExpression(version, hasTrack, cover.takeIf { descriptor })
        val photos = expression.input.photos.associate { p -> p.contentId to
            mediaPng(p.widthPx, p.heightPx, mediaPhotoColors[p.ordinal]) }
        val state = CollagePresentationState(expression,
            loadPhoto = { photos.getValue(it).copyOf() }, track = track.takeIf { hasTrack },
            artworkFetcher = if (verified) ArtworkFetcher { ArtworkFetchResult.Verified(cover.copyOf()) } else null)
        runBlocking { state.load() }
        var launchSucceeds = true
        val urls = mutableListOf<String>()
        lateinit var scene: CollageRenderer.Scene
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                HoldTheme {
                    scope = rememberCoroutineScope()
                    val measurer = rememberCollageTextMeasurer()
                    val ready = state.status as? CollagePresentationState.Status.Ready
                    if (ready != null) {
                    val prepared = CollageRenderer.prepare(expression, null, state.track,
                        ready.bitmaps.mapValues { CollageRenderer.SourcePhoto(it.value.width, it.value.height) },
                        measurer, ready.artwork?.let { CollageRenderer.SourcePhoto(it.width, it.height) })
                    check(prepared is CollageRenderer.SceneResult.Ready) { prepared.toString() }
                    scene = prepared.scene
                    }
                    CollagePresentationHost(state, "Synthetic postcard", {}, openTrackUrl = {
                        urls += it; launchSucceeds
                    }, exportFactory = { rememberCapsuleImageExport(it).also { value -> export = value } })
                }
            }
        }
        rule.onNodeWithTag("capsule_share").assertIsDisplayed()
        rule.onNodeWithTag("capsule_collage_close").assertIsDisplayed()
        val before = png("$name-clean")
        val bounds = rule.onNodeWithTag("capsule_sheet").fetchSemanticsNode().boundsInRoot
        assertEquals(bounds.width.toInt(), before.width)
        assertEquals(bounds.height.toInt(), before.height)
        val pixels = IntArray(before.width * before.height)
        before.getPixels(pixels, 0, before.width, 0, 0, before.width, before.height)
        for (color in mediaPhotoColors) assertTrue("photo pixels absent in $name", pixels.count { it == color } > 20)
        assertEquals("verified cover pixels only", verified, pixels.any { it == MEDIA_COVER_COLOR })
        // Every pixel at a removed cue's centre is the authored band, not its play control.
        if (!verified && hasTrack) {
            val music = requireNotNull(scene.music)
            val cue = requireNotNull(music.playRegion)
            val x = ((cue.x + cue.width / 2f) * before.width / scene.canvasWidth).toInt()
            val y = ((cue.y + cue.height / 2f) * before.height / scene.canvasHeight).toInt()
            assertEquals("export must omit the visual play cue", music.background, before.getPixel(x, y))
            rule.onNodeWithTag("collage_music_play_cue", useUnmergedTree = true).assertIsDisplayed()
        } else rule.onNodeWithTag("collage_music_play_cue", useUnmergedTree = true).assertDoesNotExist()
        File(output(), "$name-facts.txt").writeText("export=${before.width}x${before.height}\n" +
            "sheet=$bounds\nversion=$version track=$hasTrack descriptor=$descriptor verified=$verified\n" +
            mediaPhotoColors.joinToString("\n") { "photoColor=$it pixels=${pixels.count { p -> p == it }}" } +
            "\ncoverPixels=${pixels.count { it == MEDIA_COVER_COLOR }}\n")
        if (hasTrack) {
            rule.onNodeWithTag("capsule_track_open").assertIsDisplayed().assertTextEquals(
                rule.activity.getString(R.string.track_open_action, track.title, track.artistDisplay))
            rule.onNodeWithTag("capsule_track_open").performClick()
            rule.onNodeWithTag("capsule_track_open_spotify").performClick()
            assertTrue(before.sameAs(png("$name-player-success")))
            launchSucceeds = false
            rule.onNodeWithTag("capsule_track_open").performClick()
            rule.onNodeWithTag("capsule_track_open_spotify").performClick()
            rule.onNodeWithTag("capsule_track_no_browser").assertIsDisplayed()
            assertTrue("failed player must not change PNG", before.sameAs(png("$name-player-failure")))
            assertEquals(2, urls.size)
            assertEquals(urls[0], urls[1])
            rule.onNodeWithTag("capsule_track_error_close").performClick()
        } else rule.onNodeWithTag("capsule_track_open").assertDoesNotExist()
        rule.runOnIdle { state.close() }
        rule.onNodeWithTag("collage_canvas").assertDoesNotExist()
    }

    private fun showBer1(withTrack: Boolean = true, fontScale: Float = 1f,
        trackSnapshot: CapsuleTrackSnapshotV1 = track, launch: (String) -> Boolean = { false }) {
        val state = Ber1PresentationState(testV2Expression(), { byteArrayOf(it.toByte()) },
            decoder = Ber1PhotoDecoder { bytes ->
                Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(listOf(0xffd05b49.toInt(), 0xff4e8c75.toInt(), 0xff526fbd.toInt())[bytes[0].toInt()])
                }.asImageBitmap()
            }, track = trackSnapshot.takeIf { withTrack })
        runBlocking { state.load() }
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { HoldTheme {
            scope = rememberCoroutineScope()
            Ber1PresentationHost(state, {}, openTrackUrl = launch, exportFactory = { validate ->
                rememberCapsuleImageExport(validate).also { export = it }
            })
        } } }
    }

    @Test fun legacyTrackLabelEn() = trackLabel("en")
    @Test @Config(qualifiers = "ru-w320dp-h640dp-xhdpi") fun legacyTrackLabelRu() = trackLabel("ru")
    @Test @Config(qualifiers = "uk-w390dp-h844dp-xhdpi") fun legacyTrackLabelUk() = trackLabel("uk")
    private fun trackLabel(locale: String) {
        showBer1()
        render("ber1-$locale-before")
        rule.onNodeWithTag("capsule_track_open").assertTextEquals(
            rule.activity.getString(R.string.track_open_action, track.title, track.artistDisplay))
    }

    @Test fun legacyExportPixelsDoNotChangeAfterFailedPlayerLaunch() {
        showBer1()
        val before = png("ber1-before-player")
        rule.onNodeWithTag("capsule_track_open").performClick()
        rule.onNodeWithTag("capsule_track_open_spotify").performClick()
        rule.onNodeWithTag("capsule_track_no_browser").assertIsDisplayed()
        val after = png("ber1-after-failed-player")
        assertTrue("F06: player failure must not change authored PNG pixels", before.sameAs(after))
    }

    @Test fun legacyExportWithoutTrackHasAuthoredBoundsAndPhotoPixels() {
        showBer1(false)
        val bitmap = png("ber1-no-track")
        val bounds = rule.onNodeWithTag("capsule_sheet").fetchSemanticsNode().boundsInRoot
        assertEquals(bounds.width.toInt(), bitmap.width)
        assertEquals(bounds.height.toInt(), bitmap.height)
        assertTrue(bitmap.height > bitmap.width)
        val colors = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(colors, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (photo in listOf(0xffd05b49.toInt(), 0xff4e8c75.toInt(), 0xff526fbd.toInt())) {
            assertTrue("all three actual photo colors must be exported", colors.count { it == photo } > 1000)
        }
    }

    @Test @Config(qualifiers = "ru-w640dp-h360dp-land-xhdpi")
    fun legacyLandscapeLargeFontKeepsPhotosAndSuccessfulExport() {
        showBer1(fontScale = 1.8f, launch = { true })
        rule.onNodeWithTag("capsule_track_open").assertIsDisplayed()
        val before = png("ber1-landscape-ru-f18")
        val pixels = IntArray(before.width * before.height)
        before.getPixels(pixels, 0, before.width, 0, 0, before.width, before.height)
        mediaPhotoColors.forEach { c -> assertTrue("each legacy photo must occupy at least 1% of the exported sheet, not a tiny colour speck",
            pixels.count { it == c } >= pixels.size / 100) }
        rule.onNodeWithTag("capsule_track_open").performClick()
        rule.onNodeWithTag("capsule_track_open_spotify").performClick()
        assertTrue(before.sameAs(png("ber1-landscape-player-success")))
    }

    @Test @Config(qualifiers = "ru-w640dp-h360dp-land-xhdpi")
    fun longestLegalTrackLabelDoesNotConsumeTheSheet() {
        val long = CapsuleTrackSnapshotV1.parse("be30e36b-1111-4111-8111-000000000001",
            "Synthetic ".repeat(10), "QA Artist ".repeat(20), null, 120000L)
        showBer1(fontScale = 1.8f, trackSnapshot = long)
        rule.onNodeWithTag("capsule_sheet").assertIsDisplayed()
        rule.onNodeWithTag("capsule_track_open").assertIsDisplayed().assertTextEquals(
            rule.activity.getString(R.string.track_open_action, long.title, long.artistDisplay))
        val image = png("ber1-long-track-landscape")
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        mediaPhotoColors.forEach { c -> assertTrue("track text must leave at least 1% of the sheet for each photo",
            pixels.count { it == c } >= pixels.size / 100) }
        val action = rule.onNodeWithTag("capsule_track_open").fetchSemanticsNode().boundsInRoot
        assertTrue(action.bottom <= rule.activity.window.decorView.height)
    }

    private fun png(name: String): Bitmap {
        render("$name-host") // explicitly draw the real host; the content modifier records its layer
        val result = CompletableDeferred<ByteArray>()
        rule.runOnIdle { scope.launch {
            try { result.complete(export.capture()) } catch (failure: Throwable) { result.completeExceptionally(failure) }
        } }
        rule.waitUntil(15_000) { result.isCompleted }
        val bytes = runBlocking { result.await() }
        File(output(), "$name.png").writeBytes(bytes)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private fun output() = File("build/t389-review").apply { mkdirs() }
    private fun render(name: String) {
        rule.waitForIdle()
        val tree = rule.onNodeWithTag("capsule_sheet", useUnmergedTree = true).printToString()
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(output(), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(output(), "$name-semantics.txt").writeText(tree)
            bitmap.recycle()
        }
    }
}
