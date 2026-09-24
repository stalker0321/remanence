package dev.hryshyn.remanence.ui.scan

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.capture.CaptureAttemptController
import dev.hryshyn.remanence.capture.CaptureAttemptPhase
import dev.hryshyn.remanence.capture.CaptureAttemptSurface
import dev.hryshyn.remanence.capture.CaptureDisplayDecoder
import dev.hryshyn.remanence.capture.CapturePermissionStep
import dev.hryshyn.remanence.capture.FakeStillCameraAdapter
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.recognition.QualityReason
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.session.SessionBoundary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Capture-once UX: after ONE photo the surface holds that captured still
 * while Processing (with the move-phone copy) and the camera is released
 * immediately — the live preview must be gone and no second capture may
 * fire. The display frame derives from the delivered JPEG before its
 * zeroization, never enters recognition/storage/payloads, and is cleared on
 * every terminal, stale, cancel, retake, reset, and teardown path.
 *
 * Tag-based, locale-independent. NOT run in this tick (no Gradle execution
 * per the task brief; window 10 owns Gradle).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ScanCaptureOnceTest {

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

    private class AcceptedProcessor : StillProcessor {
        private val profile = RecognitionProfile.postcardSiftRootSiftV1()
        override fun process(jpegBytes: ByteArray): ProcessedStill = ProcessedStill.Accepted(
            profileId = profile.profileId,
            serializedBytes = dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture.bytes(7),
        )
    }

    private class NoPersistence : SealedFingerprintPersistence {
        override suspend fun persist(
            capsuleId: String,
            origin: FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String = "fp-1"

        override suspend fun hasBaseline(
            capsuleId: String,
            origin: FingerprintOrigin,
        ): Boolean = false

        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        override suspend fun setPreferredOrigin(capsuleId: String, origin: FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            origin: FingerprintOrigin,
        ) = Unit
    }

    private val decodedInputs = mutableListOf<ByteArray>()

    private fun fakeDecoder(): CaptureDisplayDecoder = CaptureDisplayDecoder { bytes ->
        decodedInputs += bytes.copyOf()
        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
    }

    private fun viewModel(
        processor: StillProcessor,
        cpuDispatcher: kotlinx.coroutines.CoroutineDispatcher = testDispatcher,
        sessionBoundary: SessionBoundary? = null,
    ): ScanViewModel {
        decodedInputs.clear()
        return ScanViewModel(
            persistence = NoPersistence(),
            database = database,
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            identityProvider = { null },
            trustedSenderKeys = dev.hryshyn.remanence.identity.DirectorySenderKeyStore(
                directoryFetch = { error("verification must be unreachable without identity") },
                ownAccount = { null },
            ),
            presentationGrants = dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority(
                dev.hryshyn.remanence.core.recognition.ScanGrantManager(clockMillis = { 0L }),
            ),
            frontProcessor = processor,
            candidateIndexProvider = { ScanCandidateIndex.EMPTY },
            incomingPresentationPreparation = null,
            captureDisplayDecoder = fakeDecoder(),
            sessionBoundary = sessionBoundary,
            cpuDispatcher = cpuDispatcher,
            ioDispatcher = testDispatcher,
        )
    }

    /**
     * Deterministic Robolectric-safe still fixture: the platform
     * Bitmap.createBitmap path is fully shadowed, while the compose
     * ImageBitmap(width, height) factory hits unshadowed setup (NPE before
     * setHasAlpha). The asImageBitmap wrapper only holds the reference.
     */
    private fun syntheticStill(): ImageBitmap =
        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).asImageBitmap()

    /**
     * Drives permission, bind, shutter, and delivery exactly as the
     * production surface does. The surface's own onDelivered moves the
     * controller to Processing.
     */
    private fun driveToProcessing(
        controller: CaptureAttemptController,
        adapter: FakeStillCameraAdapter,
    ) {
        composeRule.runOnIdle { controller.onPermissionResolved(CapturePermissionStep.Granted) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { adapter.emitReady() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("shutter_once").performClick()
        composeRule.waitForIdle()
        assertEquals(1, adapter.captureCalls)
        composeRule.runOnIdle { adapter.deliverFrame("frame".toByteArray()) }
        composeRule.waitForIdle()
        assertEquals(CaptureAttemptPhase.Processing, controller.phase)
    }

    @Test
    fun processingHoldsStillAndReleasesCameraAfterOneCapture() {
        val controller = CaptureAttemptController()
        val adapter = FakeStillCameraAdapter()
        composeRule.setContent {
            MaterialTheme {
                CaptureAttemptSurface(
                    controller = controller,
                    shutterTag = "shutter_once",
                    retakeTag = "retake_once",
                    onBeginAttempt = { controller.beginAttempt(); true },
                    onDelivered = { controller.markProcessing() },
                    onRetake = {},
                    adapterFactory = { adapter },
                    requestPermissionOnAttach = false,
                    processingStill = syntheticStill(),
                )
            }
        }
        driveToProcessing(controller, adapter)
        // markProcessing was already applied by onDelivered; the extra call
        // is inert and proves stale deliveries change nothing.
        assertEquals(false, controller.markProcessing())
        assertEquals(CaptureAttemptPhase.Processing, controller.phase)

        // The accepted still is held with the move-phone copy; the live
        // preview (and its camera binding) is gone after exactly one capture.
        composeRule.onNodeWithTag("capture_processing_still").assertIsDisplayed()
        composeRule.onNodeWithTag("capture_still_hold_copy").assertIsDisplayed()
        composeRule.onNodeWithTag("capture_processing_spinner").assertIsDisplayed()
        composeRule.onAllNodesWithTag("capture_preview").assertCountEquals(0)
        composeRule.onAllNodesWithTag("fake_camera_preview").assertCountEquals(0)
        composeRule.onNodeWithTag("shutter_once").assertIsNotEnabled()
        assertEquals(1, adapter.captureCalls)
        assertEquals(1, adapter.releaseCalls)
    }

    @Test
    fun processingWithoutStillUsesNeutralFallbackAndReleasesCamera() {
        val controller = CaptureAttemptController()
        val adapter = FakeStillCameraAdapter()
        composeRule.setContent {
            MaterialTheme {
                CaptureAttemptSurface(
                    controller = controller,
                    shutterTag = "shutter_once",
                    retakeTag = "retake_once",
                    onBeginAttempt = { controller.beginAttempt(); true },
                    onDelivered = { controller.markProcessing() },
                    onRetake = {},
                    adapterFactory = { adapter },
                    requestPermissionOnAttach = false,
                    processingStill = null,
                )
            }
        }
        driveToProcessing(controller, adapter)
        assertEquals(CaptureAttemptPhase.Processing, controller.phase)

        // Undecodable frame: neutral fallback — same status and move-phone
        // copy, no live preview anywhere, camera still released on accept.
        composeRule.onAllNodesWithTag("capture_preview").assertCountEquals(0)
        composeRule.onAllNodesWithTag("fake_camera_preview").assertCountEquals(0)
        composeRule.onNodeWithTag("capture_still_hold_copy").assertIsDisplayed()
        composeRule.onNodeWithTag("capture_processing_spinner").assertIsDisplayed()
        composeRule.onAllNodesWithTag("capture_processing_still").assertCountEquals(0)
        composeRule.onNodeWithTag("shutter_once").assertIsNotEnabled()
        assertEquals(1, adapter.captureCalls)
        assertEquals(1, adapter.releaseCalls)
    }

    @Test
    fun displayStillDerivesBeforeZeroizationAndClearsOnTerminal() {
        val vm = viewModel(RejectingProcessor())
        vm.frontAttempt.onPermissionResolved(CapturePermissionStep.Granted)
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())

        val input = "jpeg-front".toByteArray()
        vm.deliverFrontJpeg(input)

        // Terminal rejection reached synchronously under the test dispatcher.
        assertTrue(vm.frontAttempt.phase is CaptureAttemptPhase.Rejected)
        // The decoder saw the live bytes before zeroization...
        assertEquals(1, decodedInputs.size)
        assertTrue(input.all { it == 0.toByte() })
        // ...but the terminal path cleared the display frame: nothing survives.
        assertNull(vm.frontDisplayStill.value)

        // Retake returns to a clean binding with no frame retained.
        vm.retakeFront()
        assertEquals(CaptureAttemptPhase.Binding, vm.frontAttempt.phase)
        assertNull(vm.frontDisplayStill.value)
    }

    @Test
    fun displayStillClearsOnAcceptAndReset() {
        val vm = viewModel(AcceptedProcessor())
        vm.frontAttempt.onPermissionResolved(CapturePermissionStep.Granted)
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())

        vm.deliverFrontJpeg("jpeg-front".toByteArray())

        // Accept is terminal even if the session handoff below disagrees;
        // either way the display frame must be gone before Matching.
        assertEquals(1, decodedInputs.size)
        assertNull(vm.frontDisplayStill.value)
        assertTrue(vm.frontAttempt.phase !is CaptureAttemptPhase.Processing)

        vm.resetSession()
        assertNull(vm.frontDisplayStill.value)
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
    }

    private class GatedProcessor(
        val gate: CountDownLatch,
        val released: CountDownLatch = CountDownLatch(1),
    ) : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill {
            try {
                // Bounded wait so a gate failure still terminates instead of
                // hanging the suite forever.
                gate.await(10, TimeUnit.SECONDS)
                return ProcessedStill.Rejected(setOf(QualityReason.TOO_BLURRY))
            } finally {
                released.countDown()
            }
        }
    }

    @Test
    fun heldProcessingStillEndToEndThenClearsOnTerminal() {
        val gate = CountDownLatch(1)
        val vm = viewModel(GatedProcessor(gate), cpuDispatcher = Dispatchers.Default)
        val adapter = FakeStillCameraAdapter()
        composeRule.setContent {
            MaterialTheme {
                ScanScreen(
                    viewModel = vm,
                    adapterFactory = { adapter },
                    requestPermissionOnAttach = false,
                )
            }
        }
        composeRule.runOnIdle { vm.frontAttempt.onPermissionResolved(CapturePermissionStep.Granted) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { adapter.emitReady() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capture_shutter_front").performClick()
        composeRule.waitForIdle()
        assertEquals(1, adapter.captureCalls)

        val input = "jpeg-front".toByteArray()
        composeRule.runOnIdle { adapter.deliverFrame(input) }

        // Phase held at Processing while the gated pipeline waits: the VM
        // still is published before processing begins...
        composeRule.waitUntil(5_000) { vm.frontAttempt.phase is CaptureAttemptPhase.Processing }
        composeRule.waitUntil(5_000) { vm.frontDisplayStill.value != null }
        composeRule.waitForIdle()

        // ...the surface holds it with no live preview anywhere, the camera
        // is released after exactly one capture, and the shutter is dead.
        composeRule.onNodeWithTag("capture_processing_still").assertIsDisplayed()
        composeRule.onNodeWithTag("capture_still_hold_copy").assertIsDisplayed()
        composeRule.onAllNodesWithTag("capture_preview").assertCountEquals(0)
        composeRule.onAllNodesWithTag("fake_camera_preview").assertCountEquals(0)
        composeRule.onNodeWithTag("capture_shutter_front").assertIsNotEnabled()
        assertEquals(1, adapter.captureCalls)
        assertEquals(1, adapter.releaseCalls)
        assertEquals(1, decodedInputs.size)

        // Release the processor: terminal rejection clears the frame and the
        // existing zeroization wipes the delivered bytes.
        gate.countDown()
        composeRule.waitUntil(5_000) { vm.frontAttempt.phase is CaptureAttemptPhase.Rejected }
        composeRule.waitUntil(5_000) { vm.frontDisplayStill.value == null }
        composeRule.waitForIdle()

        assertNull(vm.frontDisplayStill.value)
        assertTrue(input.all { it == 0.toByte() })
        composeRule.onAllNodesWithTag("capture_processing_still").assertCountEquals(0)
        composeRule.onNodeWithTag("capture_retake_front").assertIsDisplayed()
    }

    @Test
    fun accountBoundaryDuringProcessingClearsStillAndStaleCannotRepublish() {
        val gate = CountDownLatch(1)
        val released = CountDownLatch(1)
        val boundary = SessionBoundary()
        val vm = viewModel(
            GatedProcessor(gate, released),
            cpuDispatcher = Dispatchers.Default,
            sessionBoundary = boundary,
        )
        vm.frontAttempt.onPermissionResolved(CapturePermissionStep.Granted)
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())
        val input = "jpeg-front".toByteArray()
        vm.deliverFrontJpeg(input)

        // Published BEFORE processing begins (the processor is still gated).
        composeRule.waitUntil(5_000) { vm.frontDisplayStill.value != null }
        assertEquals(CaptureAttemptPhase.Processing, vm.frontAttempt.phase)

        // True account-boundary invalidation mid-processing: new epoch,
        // attempt back to Binding, frame cleared.
        boundary.invalidate()
        composeRule.waitForIdle()
        assertNull(vm.frontDisplayStill.value)
        assertEquals(CaptureAttemptPhase.Binding, vm.frontAttempt.phase)

        // The stale pipeline drains without republishing anything: no new
        // terminal, no frame, and the delivered bytes are still zeroized.
        gate.countDown()
        assertTrue("stale pipeline did not drain", released.await(5, TimeUnit.SECONDS))
        composeRule.waitForIdle()
        assertNull(vm.frontDisplayStill.value)
        assertEquals(CaptureAttemptPhase.Binding, vm.frontAttempt.phase)
        assertTrue(input.all { it == 0.toByte() })
    }
}
