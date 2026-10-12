package dev.hryshyn.remanence.probe

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hryshyn.remanence.capture.*
import dev.hryshyn.remanence.ui.collage.*
import dev.hryshyn.remanence.ui.capsule.CapsuleSheetFrame
import dev.hryshyn.remanence.ui.capsule.rememberCapsuleImageExport
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.ui.locale.AppLocale
import dev.hryshyn.remanence.ui.settings.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real UI/GPU synthetic fixtures, not Google sync or physical recognition evidence. */
@RunWith(AndroidJUnit4::class)
class UiPolishDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val evidence get() = File(instrumentation.targetContext.getExternalFilesDir(null), "t175-fixture-evidence").apply { mkdirs() }

    @Test fun consentSmallViewportLargeTypePreservesGatesAndNavigation() {
        var agreed by mutableStateOf(false); var continued = 0; var back = 0
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.7f)) {
            HoldTheme { Box(Modifier.width(320.dp).height(560.dp)) {
                BetaTermsPage(agreed, false, true, false, { agreed = it }, { back++ }, {}, {}, {}, { continued++ })
            } }
        } }
        rule.onNodeWithTag("beta_terms_continue").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithTag("beta_terms_link").performScrollTo().assertIsDisplayed().assertWidthIsAtLeast(250.dp)
        capture("consent-large-font-before")
        rule.onNodeWithTag("beta_terms_agreement").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("beta_terms_continue").assertIsEnabled().performClick()
        rule.onNodeWithTag("access_back").assertIsDisplayed().performClick()
        assertEquals(1, continued); assertEquals(1, back)
        capture("consent-large-font-agreed")
    }

    @Test fun settingsOverviewSeparatesValuesFromDetailsAndActions() {
        var locale by mutableStateOf(AppLocale.SYSTEM)
        rule.setContent { HoldTheme { SettingsScreen("qa_fixture", locale, { locale = it }, true, {}, {}) } }
        rule.onNodeWithTag("settings_recovery_status").assertDoesNotExist()
        rule.onNodeWithTag("language_option_russian").assertDoesNotExist()
        rule.onNodeWithTag("language_switch_row").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("settings-overview")
        rule.onNodeWithTag("language_switch_row").performClick()
        rule.onNodeWithTag("language_option_russian").assertIsDisplayed().performClick()
        assertEquals(AppLocale.RUSSIAN, locale)
        rule.onNodeWithTag("language_select_group").assertDoesNotExist()
        rule.onNodeWithTag("settings_access_row").performScrollTo().performClick()
        rule.onNodeWithTag("settings_recovery_status").assertIsDisplayed()
        capture("settings-access-detail")
    }

    @Test fun processingFrameHasNoJpegInBothCreateAndReceiveTreatment() {
        val controller = CaptureAttemptController()
        controller.onPermissionResolved(CapturePermissionStep.Granted)
        controller.onPreviewBound(); controller.beginAttempt(); assertTrue(controller.markProcessing())
        var sender by mutableStateOf(false)
        rule.setContent { HoldTheme { CaptureAttemptSurface(controller, "shutter", "retake", { false }, {}, {},
            requestPermissionOnAttach = false, shutterLabel = if (sender) "Use postcard" else null) } }
        rule.onNodeWithTag("capture_processing_still").assertDoesNotExist()
        val frame = rule.onNodeWithTag("capture_processing_neutral").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("shutter").assertIsNotEnabled()
        capture("receive-neutral-processing")
        rule.runOnIdle { sender = true }
        assertEquals(frame, rule.onNodeWithTag("capture_processing_neutral").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithTag("capture_processing_still").assertDoesNotExist()
        capture("create-neutral-processing")
    }

    @Test fun coverRemainsUnobscuredAndTextFallbackKeepsMusicAction() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        val artwork = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        var cover by mutableStateOf(true); var clicks = 0
        val rect = CollageRenderer.RectI(0, 500, 360, 80)
        val music = CollageRenderer.SceneMusic("505", "Arctic Monkeys", "505\nArctic Monkeys", rect,
            "StudySans", 14, 400, false, 18, requireNotNull(FontRegistry.resourceFor("StudySans",400,false)),
            0xFF333951.toInt(), 0xFFF4F2EC.toInt(),
            mode = "thumbnail", copyRegion = CollageRenderer.RectI(60,507,290,66), playRegion = CollageRenderer.RectI(8,520,36,36))
        val base = CollageRenderer.Scene(360,640,"g1", paper=0xFFF4F2EC.toInt(), ink=0xFF333951.toInt(),
            sound=0xFF333951.toInt(), soundInk=0xFFF4F2EC.toInt(),
            photos=listOf(CollageRenderer.ScenePhoto("p0",CollageRenderer.RectI(0,0,360,400),CollageRenderer.RectI(0,0,200,200))),
            note=null,music=music)
        try {
            rule.setContent { HoldTheme {
              val export = rememberCapsuleImageExport {}
              CapsuleSheetFrame(export, true, 360f/640f, {}, closeTag = "fixture_close") { sheetModifier ->
                CollageCanvas(base.copy(artwork=if(cover) CollageRenderer.SceneArtwork(rect,0xFF333951.toInt()) else null),
                    mapOf("p0" to bitmap.asImageBitmap()), "Synthetic music capsule",
                    modifier=sheetModifier,
                    artwork=if(cover) artwork.asImageBitmap() else null,
                    onMusicClick={clicks++}, musicActionLabel="Listen to 505 by Arctic Monkeys")
              }
            } }
            rule.onNodeWithTag("collage_music_play_cue",useUnmergedTree=true).assertDoesNotExist()
            rule.onNodeWithTag("capsule_music_region").assertIsDisplayed()
            val canvas = rule.onNodeWithTag("collage_canvas").captureToImage()
            val pixels = canvas.toPixelMap()
            val scale = canvas.width / 360f
            // The real GPU must paint the unobscured cover, not merely expose
            // a semantics node for an invalid synthetic cover/mode pair.
            assertEquals(android.graphics.Color.MAGENTA, pixels[(24*scale).toInt(), (540*scale).toInt()].toArgb())
            File(evidence, "music-cover-canvas-pixelcopy.png").outputStream().use {
                canvas.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            capture("music-with-cover")
            // Verify static pixels before clicking: the normal press ripple
            // temporarily darkens even an unobscured cover.
            rule.onNodeWithTag("capsule_music_region").performClick()
            assertEquals(1,clicks)
            rule.runOnIdle { cover=false }
            rule.onNodeWithTag("collage_music_play_cue",useUnmergedTree=true).assertExists()
            rule.mainClock.advanceTimeBy(600)
            capture("music-without-cover")
            val fallback = rule.onNodeWithTag("collage_canvas").captureToImage()
            File(evidence, "music-no-cover-canvas-pixelcopy.png").outputStream().use {
                fallback.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            rule.onNodeWithTag("capsule_music_region").performClick()
            assertEquals(2,clicks)
        } finally {
            // Renderer owns a reference until composition ends; never recycle mid-frame.
        }
    }

    private fun capture(name:String) {
        rule.waitForIdle()
        // Android's native PixelCopy waits for the actual window draw. Raw
        // UiAutomation screenshots can lag a frame on the headless QA AVD.
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(evidence,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}
