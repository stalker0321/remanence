package app.postmark.memory.capture

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import postmark.core.recognition.QualityReason

/**
 * Regression proof for the first-camera-entry crash (Create flow exited
 * exactly when the camera should open): the surface must compose an adapter's
 * preview BEFORE calling bind() - on the FIRST camera entry and on EVERY new
 * bind epoch (retake) - because [CameraXStillCameraAdapter.bind] requires the
 * hosted PreviewView to exist. The replaced adapter must be released exactly
 * once. The strict fake fails loudly on any bind-before-compose recurrence;
 * the permissive [FakeStillCameraAdapter] silently tolerated it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CaptureSurfaceBindingOrderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var controller: CaptureAttemptController

    /** The most recently created strict adapter, as the production factory would expose it. */
    private val live = AtomicReference<StrictFakeStillCameraAdapter?>(null)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        controller = CaptureAttemptController()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                CaptureAttemptSurface(
                    title = "postcard front",
                    controller = controller,
                    shutterTag = "binding_race_shutter",
                    retakeTag = "binding_race_retake",
                    onBeginAttempt = { true },
                    onDelivered = {},
                    onRetake = { controller.startRetake() },
                    adapterFactory = {
                        StrictFakeStillCameraAdapter().also { live.set(it) }
                    },
                    requestPermissionOnAttach = false,
                )
            }
        }
    }

    /** Resolves the permission exactly like a remembered system grant. */
    private fun grantPermission() {
        composeRule.runOnIdle {
            controller.onPermissionResolved(CapturePermissionStep.Granted)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun firstCameraEntryBindsOnlyAfterItsPreviewIsComposed() {
        setContent()

        // First entry into the camera: the strict fake's bind() throws unless
        // its preview was composed in the same commit that produced it.
        grantPermission()

        val first = live.get()
        assertNotNull("adapter must exist during composition", first)
        assertTrue(first!!.previewComposed)
        assertEquals(1, first.bindCalls)
        assertEquals(0, first.releaseCalls)

        // Completing the async binding makes the real shutter available.
        composeRule.runOnIdle { first.emitReady() }
        composeRule.waitForIdle()

        assertEquals(CaptureAttemptPhase.Ready, controller.phase)
        composeRule.onNodeWithTag("binding_race_shutter").assertIsDisplayed()
        composeRule.onNodeWithTag("postcard_guide_overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("postcard_guide_instruction").assertIsDisplayed()
    }

    @Test
    fun retakeStartsANewBindEpochAndReleasesTheOldAdapterExactlyOnce() {
        setContent()
        grantPermission()

        val first = live.get()!!
        composeRule.runOnIdle { first.emitReady() }
        composeRule.waitForIdle()
        assertEquals(CaptureAttemptPhase.Ready, controller.phase)

        // Production-shaped rejection: an attempt is begun, then rejected.
        composeRule.runOnIdle {
            controller.beginAttempt()
            controller.reject(setOf(QualityReason.TOO_BLURRY))
        }
        composeRule.waitForIdle()

        // Leaving LivePreviewContent releases exactly this adapter.
        composeRule.onNodeWithTag("capture_terminal_panel").assertIsDisplayed()
        assertEquals(1, first.releaseCalls)

        // Retake: a NEW epoch must produce a NEW adapter whose preview is
        // again composed before its bind().
        composeRule.onNodeWithTag("binding_race_retake").performClick()
        composeRule.waitForIdle()

        val second = live.get()
        assertNotNull(second)
        assertNotSame(first, second)
        assertTrue(second!!.previewComposed)
        assertEquals(1, second.bindCalls)
        assertEquals(1, first.releaseCalls)
        assertEquals(CaptureAttemptPhase.Binding, controller.phase)

        composeRule.runOnIdle { second.emitReady() }
        composeRule.waitForIdle()
        assertEquals(CaptureAttemptPhase.Ready, controller.phase)
        composeRule.onNodeWithTag("binding_race_shutter").assertIsDisplayed()
    }

    @Test
    fun queuedReadyFromResetBindingIsInertWithoutHidingCurrentStateBugs() {
        setContent()
        grantPermission()
        val first = live.get()!!

        composeRule.runOnIdle { controller.reset() }
        composeRule.waitForIdle()
        first.emitQueuedReadyAfterRelease()
        composeRule.waitForIdle()

        assertEquals(CapturePermissionStep.NotRequested, controller.permission)
        assertEquals(null, controller.phase)
    }
}
