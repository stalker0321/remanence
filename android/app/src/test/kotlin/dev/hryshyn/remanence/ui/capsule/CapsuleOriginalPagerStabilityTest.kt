package dev.hryshyn.remanence.ui.capsule

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.Key
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

    @Test @Config(qualifiers = "ru-w360dp-h640dp-night-xhdpi")
    fun largeRussianTextWithSystemDarkKeepsCloseReachable() = verifyPaging("ru-large-night", 1.5f)

    @Test @Config(qualifiers = "en-w320dp-h640dp-xhdpi")
    fun narrowEnglishFont18() = verifyPaging("en-320-f18", 1.8f)
    @Test @Config(qualifiers = "ru-w390dp-h844dp-xhdpi")
    fun russianFont18() = verifyPaging("ru-390-f18", 1.8f)
    @Test @Config(qualifiers = "uk-w320dp-h640dp-xhdpi")
    fun narrowUkrainianFont15() = verifyPaging("uk-320-f15", 1.5f)
    @Test @Config(qualifiers = "uk-w640dp-h360dp-land-xhdpi")
    fun landscapeOriginalsFitAndControlsStayInsideWindow() = verifyPaging("uk-landscape", 1.5f)

    private fun verifyPaging(locale: String, fontScale: Float = 1f) {
        val ids = listOf("wide", "tall")
        val images = listOf(320 to 160, 160 to 320).mapIndexed { index, (width, height) ->
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(if (index == 0) 0xFF59649B.toInt() else 0xFFC7BDA8.toInt())
                // Four different corner canaries: a cropped original loses one.
                for (y in 0 until 10) for (x in 0 until 10) {
                    setPixel(x, y, 0xffff0000.toInt())
                    setPixel(width - 1 - x, y, 0xff00ff00.toInt())
                    setPixel(x, height - 1 - y, 0xff0000ff.toInt())
                    setPixel(width - 1 - x, height - 1 - y, 0xffffff00.toInt())
                }
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
        rule.onNodeWithTag("capsule_photo_close").assertIsFocused()
        rule.onNodeWithTag("capsule_photo_previous").assertIsNotEnabled()
        rule.onNodeWithTag("capsule_photo_next").assertIsEnabled()
        val beforeClose = rule.onNodeWithTag("capsule_photo_close").fetchSemanticsNode().boundsInRoot
        val beforeStage = rule.onNodeWithTag("capsule_photo_pager").fetchSemanticsNode().boundsInRoot
        capture("original-$locale-wide", 2f)
        rule.onNodeWithTag("capsule_photo_pager").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("capsule_photo_page_count").assertTextEquals("2 / 2")
        rule.onNodeWithTag("capsule_photo_close").assertIsDisplayed()
        capture("original-$locale-tall", .5f)
        assertEquals(beforeStage, rule.onNodeWithTag("capsule_photo_pager").fetchSemanticsNode().boundsInRoot)
        assertEquals(beforeClose, rule.onNodeWithTag("capsule_photo_close").fetchSemanticsNode().boundsInRoot)
        assertTrue("page changes must still validate access", validations >= 2)
        rule.onNodeWithTag("capsule_photo_next").assertIsNotEnabled()
        rule.onNodeWithTag("capsule_photo_previous").performClick()
        rule.onNodeWithTag("capsule_photo_page_count").assertTextEquals("1 / 2")
        rule.onNodeWithTag("capsule_photo_close").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        rule.onNodeWithTag("capsule_photo_close").performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithTag("capsule_photo_page_count").assertTextEquals("2 / 2")
        rule.onNodeWithTag("capsule_photo_close").performClick()
        assertEquals(1, dismissals)
    }

    private fun capture(name: String, aspect: Float) {
        rule.waitForIdle()
        val hierarchy = rule.onNodeWithTag("capsule_photo_dialog", useUnmergedTree = true).printToString()
        rule.runOnIdle {
            val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val dir = File("build/t389-review").apply { mkdirs() }
                File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(dir, "$name-semantics.txt").writeText(hierarchy)
                val colors = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(colors, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val markers = listOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffff00.toInt())
                markers.forEach { color -> assertTrue("Fit must preserve all four corners: $name $color", colors.count { it == color } > 2) }
                val x = mutableListOf<Int>(); val y = mutableListOf<Int>()
                colors.forEachIndexed { index, color -> if (color in markers) { x += index % bitmap.width; y += index / bitmap.width } }
                assertEquals("uncut original aspect $name", aspect,
                    (x.max() - x.min() + 1f) / (y.max() - y.min() + 1f), .04f)
                File(dir, "$name-facts.txt").writeText("window=${bitmap.width}x${bitmap.height}\n" +
                    "photoBounds=${x.min()},${y.min()},${x.max()},${y.max()} aspect=$aspect\n" +
                    markers.joinToString("\n") { "corner=$it pixels=${colors.count { p -> p == it }}" })
            } finally { bitmap.recycle() }
        }
    }
}
