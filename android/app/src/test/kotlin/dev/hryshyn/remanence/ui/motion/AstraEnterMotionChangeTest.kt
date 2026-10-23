package dev.hryshyn.remanence.ui.motion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AstraEnterMotionChangeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun removingAnimationsDuringArrivalImmediatelyRevealsContent() {
        verifyPreferenceChange("enter")
    }

    @Test fun contextPreferenceChangeDoesNotReplayTheSameRoute() {
        verifyPreferenceChange("context")
    }

    @Test fun stepPreferenceChangeDoesNotReplayTheSameStep() {
        verifyPreferenceChange("step")
    }

    private fun verifyPreferenceChange(surface: String) {
        val motion = mutableStateOf(AstraMotionSpec.Resolved.FULL)
        val step = mutableStateOf(0)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.size(96.dp).background(Color.White)) {
                val probe: @androidx.compose.runtime.Composable () -> Unit = {
                    Box(Modifier.size(48.dp).background(Color.Red).testTag("probe"))
                }
                when (surface) {
                    "context" -> AstraContextArrival(motion.value, contentKey = "unchanged", content = probe)
                    "step" -> AstraStepArrival(motion.value, stepKey = step.value, content = probe)
                    else -> AstraEnterTransition(motion.value, content = probe)
                }
            }
        }
        if (surface == "step") {
            rule.mainClock.advanceTimeBy(32)
            rule.runOnUiThread { step.value++ }
        }
        rule.mainClock.advanceTimeBy(32)
        assertTrue(opacity() < 1f)
        rule.runOnUiThread { motion.value = AstraMotionSpec.Resolved.INSTANT }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(1f, opacity(), .01f)
        // Returning to full motion must not replay an already completed arrival.
        rule.runOnUiThread { motion.value = AstraMotionSpec.Resolved.FULL }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(1f, opacity(), .01f)
    }

    private fun opacity(): Float {
        val bounds = rule.onNodeWithTag("probe").fetchSemanticsNode().boundsInWindow
        var green = 255
        rule.runOnUiThread {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                green = AndroidColor.green(bitmap.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt()))
            } finally { bitmap.recycle() }
        }
        return 1f - green / 255f
    }
}
