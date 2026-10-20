package dev.hryshyn.remanence.ui.capsule

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.ui.hold.HoldTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** Synthetic Robolectric windows and semantics, not device pixels or timing. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapsuleOriginalPagerStabilityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun pagingLandscapeToPortraitKeepsCloseButtonAndStageStill() = verifyPaging("en")

    @Test @Config(qualifiers = "ru-w360dp-h640dp-xhdpi")
    fun russianPagingKeepsCloseButtonAndStageStill() = verifyPaging("ru")

    @Test @Config(qualifiers = "uk-w360dp-h640dp-xhdpi")
    fun ukrainianPagingKeepsCloseButtonAndStageStill() = verifyPaging("uk")

    @Test @Config(qualifiers = "ru-w360dp-h640dp-xhdpi-night")
    fun largeRussianTextWithSystemDarkKeepsCloseReachable() = verifyPaging("ru-large-night", 1.5f)

    private fun verifyPaging(locale: String, fontScale: Float = 1f) {
        val ids = listOf("wide", "tall")
        val images = listOf(320 to 160, 160 to 320).mapIndexed { index, (width, height) ->
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(if (index == 0) 0xFF59649B.toInt() else 0xFFC7BDA8.toInt())
            }.asImageBitmap()
        }
        var validations = 0
        var dismissals = 0
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                HoldTheme {
                    CapsulePhotoDialog(ids, ids.zip(images).toMap(), "wide",
                        validateAccess = { validations++ }, onAccessLost = {},
                        onDismiss = { dismissals++ })
                }
            }
        }
        rule.onNodeWithTag("capsule_photo_close").assertIsDisplayed()
        val beforeClose = rule.onNodeWithTag("capsule_photo_close").fetchSemanticsNode().boundsInRoot
        val beforeStage = rule.onNodeWithTag("capsule_photo_pager").fetchSemanticsNode().boundsInRoot
        capture("original-$locale-wide")
        rule.onNodeWithTag("capsule_photo_pager").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("capsule_photo_page_count").assertTextEquals("2 / 2")
        rule.onNodeWithTag("capsule_photo_close").assertIsDisplayed()
        capture("original-$locale-tall")
        assertEquals(beforeStage, rule.onNodeWithTag("capsule_photo_pager").fetchSemanticsNode().boundsInRoot)
        assertEquals(beforeClose, rule.onNodeWithTag("capsule_photo_close").fetchSemanticsNode().boundsInRoot)
        assertTrue("page changes must still validate access", validations >= 2)
        rule.onNodeWithTag("capsule_photo_close").performClick()
        assertEquals(1, dismissals)
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val hierarchy = rule.onNodeWithTag("capsule_photo_dialog", useUnmergedTree = true).printToString()
        rule.runOnIdle {
            val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val dir = File("build/t360-review").apply { mkdirs() }
                File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(dir, "$name-semantics.txt").writeText(hierarchy)
            } finally { bitmap.recycle() }
        }
    }
}
