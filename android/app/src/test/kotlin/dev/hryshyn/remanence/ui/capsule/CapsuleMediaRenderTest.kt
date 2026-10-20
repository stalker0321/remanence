package dev.hryshyn.remanence.ui.capsule

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
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

    private fun showBer1(withTrack: Boolean = true, launch: (String) -> Boolean = { false }) {
        val state = Ber1PresentationState(testV2Expression(), { byteArrayOf(it.toByte()) },
            decoder = Ber1PhotoDecoder { bytes ->
                Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(listOf(0xffd05b49.toInt(), 0xff4e8c75.toInt(), 0xff526fbd.toInt())[bytes[0].toInt()])
                }.asImageBitmap()
            }, track = track.takeIf { withTrack })
        runBlocking { state.load() }
        rule.setContent { HoldTheme {
            scope = rememberCoroutineScope()
            Ber1PresentationHost(state, {}, openTrackUrl = launch, exportFactory = { validate ->
                rememberCapsuleImageExport(validate).also { export = it }
            })
        } }
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
        val tree = rule.onRoot(useUnmergedTree = true).printToString()
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
