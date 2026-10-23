package dev.hryshyn.remanence.ui.create

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.capsule.mediaPng
import dev.hryshyn.remanence.ui.collage.CropTransform
import dev.hryshyn.remanence.ui.collage.NormalizedCrop
import dev.hryshyn.remanence.ui.hold.HoldTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CropMediaAccessibilityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var correction by mutableStateOf<CreateViewModel.CropCorrection?>(null)
    private val base = NormalizedCrop(.2, .1, .4, .6)
    private fun window() = requireNotNull(CropEditorGeometry.window(base, CropTransform(
        correction?.zoom?.toDouble() ?: 1.0, correction?.panX?.toDouble() ?: 0.0,
        correction?.panY?.toDouble() ?: 0.0)))
    private fun action(key: Int) {
        val actions = rule.onNodeWithTag("crop_frame").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(6, actions.size)
        assertEquals(6, actions.map { it.label }.toSet().size)
        rule.runOnIdle { assertTrue(actions.single { it.label == rule.activity.getString(key) }.action()) }
        rule.waitForIdle()
        val w = window()
        assertTrue(w.x >= -1e-6 && w.y >= -1e-6 && w.x + w.width <= 1.000001 && w.y + w.height <= 1.000001)
    }
    @Test @Config(qualifiers = "en-w320dp-h640dp-xhdpi") fun en320Font18() = verify("en-320-f18", 1.8f)
    @Test fun en390Font15() = verify("en-390-f15", 1.5f)
    @Test @Config(qualifiers = "ru-w320dp-h640dp-xhdpi") fun ru320Font15() = verify("ru-320-f15", 1.5f)
    @Test @Config(qualifiers = "ru-w390dp-h844dp-xhdpi") fun ru390Font18() = verify("ru-390-f18", 1.8f)
    @Test @Config(qualifiers = "uk-w320dp-h640dp-xhdpi") fun uk320Font18() = verify("uk-320-f18", 1.8f)
    @Test @Config(qualifiers = "uk-w390dp-h844dp-xhdpi") fun uk390Font15() = verify("uk-390-f15", 1.5f)

    private fun verify(name: String, font: Float) {
        var resets = 0; var cancels = 0; var dones = 0
        val source = GeneratorPreviewSource("synthetic", mediaPng(120, 80, 0xff4e8c75.toInt()), 120, 80)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font)) {
                HoldTheme {
                    CropStageContent(source, 1f, GeneratorExpression.CropWindow(2000, 1000, 4000, 6000),
                        correction, onUpdate = { z, x, y -> correction = CreateViewModel.CropCorrection(source.contentId, z, x, y) },
                        onReset = { resets++; correction = null }, onDone = { dones++ }, onBack = { cancels++ },
                        modifier = Modifier.fillMaxSize())
                }
            }
        }
        val root = rule.onNodeWithTag("crop_stage").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("crop_back", "crop_reset", "crop_done", "crop_frame")) {
            val node = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
            assertTrue("$tag inside viewport $name", root.contains(node.boundsInRoot.topLeft) &&
                node.boundsInRoot.bottom <= root.bottom + 1 && node.boundsInRoot.right <= root.right + 1)
            assertTrue("nonempty $tag", node.boundsInRoot.width > 0 && node.boundsInRoot.height > 0)
        }
        val state = rule.onNodeWithTag("crop_frame").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertEquals(rule.activity.getString(R.string.media_crop_position, 20, 10, 40, 60), state)
        capture(name)
        val initial = window()
        action(R.string.media_crop_right); assertTrue(window().x > initial.x)
        action(R.string.media_crop_left); assertEquals(initial.x, window().x, 1e-5)
        action(R.string.media_crop_down); assertTrue(window().y > initial.y)
        action(R.string.media_crop_up); assertEquals(initial.y, window().y, 1e-5)
        action(R.string.media_crop_zoom_in); assertTrue(window().width < initial.width)
        action(R.string.media_crop_zoom_out); assertEquals(initial.width, window().width, 1e-5)
        repeat(25) { action(R.string.media_crop_left); action(R.string.media_crop_zoom_in) }
        assertEquals(1.25, correction!!.zoom.toDouble(), 1e-6)
        rule.onNodeWithTag("crop_reset").performClick(); assertEquals(initial, window())
        rule.onNodeWithTag("crop_frame").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag("crop_frame").performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle(); assertTrue(window().x > initial.x)
        val width = window().width
        rule.onNodeWithTag("crop_frame").performKeyInput { pressKey(Key.Equals) }
        rule.waitForIdle(); assertTrue(window().width < width)
        rule.onNodeWithTag("crop_done").performClick()
        rule.onNodeWithTag("crop_back").performClick()
        assertEquals(1, resets); assertEquals(1, dones); assertEquals(1, cancels)
    }
    private fun capture(name: String) {
        val tree = rule.onRoot(useUnmergedTree = true).printToString()
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val dir = File("build/t389-review").apply { mkdirs() }
            File(dir, "crop-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(dir, "crop-$name-semantics.txt").writeText(tree)
            bitmap.recycle()
        }
    }
}
