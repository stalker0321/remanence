package dev.hryshyn.remanence.session

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.capture.CapturePermissionStep
import dev.hryshyn.remanence.capture.FakeStillCameraAdapter
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import dev.hryshyn.remanence.ui.scan.ScanScreen
import dev.hryshyn.remanence.ui.scan.ScanViewModel
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.recognition.QualityReason
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.ui.scan.ScanCandidateIndex

/**
 * FIX-STATE-14 regression: THE production root hosts the REAL scan surface
 * (ScanScreen + CaptureAttemptSurface) in a 320x480dp viewport. Simultaneously:
 * the header's "Back to Home" stays visible and actionable, the flow body
 * receives only the remaining height, the viewfinder keeps a deterministic
 * positive area, and the terminal Retake recovery is reachable through a real
 * scroll inside that bounded region.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w320dp-h480dp")
class RootScanFlowLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var database: RemanenceLocalDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
    }

    private class RejectingProcessor : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill =
            ProcessedStill.Rejected(setOf(QualityReason.TOO_BLURRY))
    }

    private class NoPersistence : SealedFingerprintPersistence {
        override suspend fun persist(
            capsuleId: String,
            side: dev.hryshyn.remanence.core.data.db.FingerprintSide,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String = "fp"

        override suspend fun hasBaseline(
            capsuleId: String,
            side: dev.hryshyn.remanence.core.data.db.FingerprintSide,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ): Boolean = false

        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        override suspend fun setPreferredPair(capsuleId: String, origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            side: dev.hryshyn.remanence.core.data.db.FingerprintSide,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    private fun newScanViewModel(): ScanViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ScanViewModel(
            persistence = NoPersistence(),
            database = database,
            profile = RecognitionProfile.mvpOrbV1(),
            identityProvider = { null },
            // Unreachable here (no identity), but THE trusted boundary is
            // required explicitly by the API.
            trustedSenderKeys = dev.hryshyn.remanence.identity.DirectorySenderKeyStore(
                directoryFetch = { error("verification must be unreachable without identity") },
                ownAccount = { null },
            ),
            presentationGrants = dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority(
                dev.hryshyn.remanence.core.recognition.ScanGrantManager(clockMillis = { 0L }),
            ),
            frontProcessor = RejectingProcessor(),
            backProcessor = RejectingProcessor(),
            candidateIndexProvider = { ScanCandidateIndex.EMPTY },
            incomingPresentationPreparation = null,
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun rootKeepsHeaderVisibleWhileScanBodyScrollsItsRecoveryIntoView() {
        val vm = newScanViewModel()
        val live = AtomicReference<FakeStillCameraAdapter?>(null)
        var exited = false
        composeRule.setContent {
            MaterialTheme {
                RootScreen(
                    authState = AuthUiState.Authenticated(userId = "u", handle = "mykola"),
                    destination = AppDestination.Scan,
                    authenticationContent = {},
                    homeContent = {},
                    createContent = {},
                    scanContent = {
                        ScanScreen(
                            viewModel = vm,
                            adapterFactory = { FakeStillCameraAdapter().also { live.set(it) } },
                            requestPermissionOnAttach = false,
                        )
                    },
                    onExitFlow = { exited = true },
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            vm.frontAttempt.onPermissionResolved(CapturePermissionStep.Granted)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { live.get()?.emitReady() }
        composeRule.waitForIdle()

        // 1) The header owns its space and its action is present; the flow
        //    body starts BELOW it and the viewfinder has a DETERMINISTIC
        //    positive area with the shutter directly beneath.
        val headerBottom = composeRule.onNodeWithTag("flow_exit_scan")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.bottom
        val previewBounds = composeRule.onNodeWithTag("capture_preview")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "preview must have positive height >= MIN(" +
                dev.hryshyn.remanence.capture.CAPTURE_PREVIEW_MIN_HEIGHT + "), was ${previewBounds.height}",
            previewBounds.height >= dev.hryshyn.remanence.capture.CAPTURE_PREVIEW_MIN_HEIGHT.value,
        )
        assertTrue(
            "body must start below the header; headerBottom=$headerBottom previewTop=${previewBounds.top}",
            previewBounds.top >= headerBottom - 1f,
        )
        val shutterBounds = composeRule.onNodeWithTag("capture_shutter_front")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "shutter must sit below the nonzero preview",
            shutterBounds.top >= previewBounds.bottom - 1f,
        )

        // 2) Drive one capture attempt into rejection through the camera seam.
        composeRule.onNodeWithTag("scan_screen_scroll")
            .performScrollToNode(hasTestTag("capture_shutter_front"))
        composeRule.onNodeWithTag("capture_shutter_front").performClick()
        composeRule.runOnIdle { live.get()!!.deliverFrame("frame".toByteArray()) }
        composeRule.waitForIdle()

        // 3) The rejection panel replaces the camera; Retake is reachable by
        //    scrolling INSIDE the flow body while the header stays visible.
        composeRule.onNodeWithTag("scan_screen_scroll")
            .performScrollToNode(hasTestTag("capture_retake_front"))
        composeRule.onNodeWithTag("quality_reason_TOO_BLURRY").assertIsDisplayed()

        val viewportHeight = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.height
        val retakeBounds = composeRule.onNodeWithTag("capture_retake_front")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "retake must be fully inside the viewport; retake=$retakeBounds viewport=$viewportHeight",
            retakeBounds.top >= 0f && retakeBounds.bottom <= viewportHeight + 1f,
        )

        // Header STILL displayed after all scrolling - its action really exits.
        composeRule.onNodeWithText("Back to Home")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertTrue("exit must fire", exited) }
    }

}
