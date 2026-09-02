package dev.hryshyn.remanence.capture

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CapturePreviewLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun normalPortraitKeepsPreviewAndGuideAtThreeByFour() {
        val bounds = mountSurface(
            screenWidthDp = 360,
            screenHeightDp = 800,
            hostWidthDp = 360,
            hostHeightDp = 800,
        )

        assertRatio(bounds.preview.width, bounds.preview.height, 3f / 4f)
        assertSameBounds(bounds.preview, bounds.guide)
        assertSameBounds(bounds.preview, bounds.camera)
    }

    @Test
    fun widePortraitUsesThe520DpBudgetWithoutBreakingTheRatio() {
        val preview = capturePreviewSize(600.dp, 520.dp, 3f / 4f)

        assertEquals(390f, preview.width.value, 0.5f)
        assertEquals(520f, preview.height.value, 0.5f)
        assertRatio(preview.width.value, preview.height.value, 3f / 4f)
    }

    @Test
    fun shortPortraitKeepsThreeByFour() {
        val bounds = mountSurface(
            screenWidthDp = 320,
            screenHeightDp = 480,
            hostWidthDp = 288,
            hostHeightDp = 480,
        )

        assertRatio(bounds.preview.width, bounds.preview.height, 3f / 4f)
        assertSameBounds(bounds.preview, bounds.guide)
        assertSameBounds(bounds.preview, bounds.camera)
    }

    @Test
    fun landscapeConfigurationKeepsFourByThreeWithoutChangingSharedSurface() {
        val bounds = mountSurface(
            screenWidthDp = 800,
            screenHeightDp = 360,
            hostWidthDp = 800,
            hostHeightDp = 360,
        )

        assertRatio(bounds.preview.width, bounds.preview.height, 4f / 3f)
        assertSameBounds(bounds.preview, bounds.guide)
        assertSameBounds(bounds.preview, bounds.camera)
    }

    @Test
    fun sizingHelperShrinksWidthOnlyWhenHeightCapBinds() {
        assertEquals(520f, capturePreviewMaxHeight(800.dp).value, 0.001f)
        assertEquals(326.4f, capturePreviewMaxHeight(480.dp).value, 0.001f)

        val uncapped = capturePreviewSize(200.dp, 320.dp, 3f / 4f)
        assertEquals(200f, uncapped.width.value, 0.001f)
        assertEquals(200f / (3f / 4f), uncapped.height.value, 0.001f)

        val capped = capturePreviewSize(600.dp, 520.dp, 3f / 4f)
        assertEquals(390f, capped.width.value, 0.001f)
        assertEquals(520f, capped.height.value, 0.001f)
    }

    private data class SurfaceBounds(
        val preview: androidx.compose.ui.geometry.Rect,
        val guide: androidx.compose.ui.geometry.Rect,
        val camera: androidx.compose.ui.geometry.Rect,
    )

    private fun mountSurface(
        screenWidthDp: Int,
        screenHeightDp: Int,
        hostWidthDp: Int,
        hostHeightDp: Int,
    ): SurfaceBounds {
        val controller = CaptureAttemptController()
        val live = AtomicReference<FakeStillCameraAdapter?>(null)
        val configuration = Configuration().apply {
            this.screenWidthDp = screenWidthDp
            this.screenHeightDp = screenHeightDp
        }

        composeRule.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MaterialTheme {
                    Box(
                        modifier = Modifier
                            .size(hostWidthDp.dp, hostHeightDp.dp)
                            .testTag("capture_test_host"),
                    ) {
                        CaptureAttemptSurface(
                            title = "postcard front",
                            controller = controller,
                            shutterTag = "capture_test_shutter",
                            retakeTag = "capture_test_retake",
                            onBeginAttempt = { true },
                            onDelivered = {},
                            onRetake = {},
                            adapterFactory = { FakeStillCameraAdapter().also { live.set(it) } },
                            requestPermissionOnAttach = false,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            controller.onPermissionResolved(CapturePermissionStep.Granted)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { live.get()?.emitReady() }
        composeRule.waitForIdle()

        val preview = composeRule.onNodeWithTag("capture_preview")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val guide = composeRule.onNodeWithTag("postcard_guide_overlay")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val camera = composeRule.onNodeWithTag("fake_camera_preview")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        return SurfaceBounds(preview, guide, camera)
    }

    private fun assertRatio(width: Float, height: Float, expected: Float) {
        assertTrue("expected positive bounds, got ${width}x${height}", width > 0f && height > 0f)
        assertEquals(expected, width / height, 0.01f)
    }

    private fun assertSameBounds(
        first: androidx.compose.ui.geometry.Rect,
        second: androidx.compose.ui.geometry.Rect,
    ) {
        assertEquals(first.left, second.left, 0.5f)
        assertEquals(first.top, second.top, 0.5f)
        assertEquals(first.right, second.right, 0.5f)
        assertEquals(first.bottom, second.bottom, 0.5f)
    }
}
