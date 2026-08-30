package dev.hryshyn.remanence.ui.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.capture.CaptureAttemptController
import dev.hryshyn.remanence.capture.CaptureAttemptPhase
import dev.hryshyn.remanence.capture.CapturePermissionStep
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.scan.ScanSessionState
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.RecognitionProfile

/**
 * FIX-REVIEW-01 regression: the PRODUCTION ScanViewModel wiring enters an
 * honest capture state - FRONT first, then BACK, and only a complete capture
 * pair advances to matching. reset returns the whole flow to FRONT. The ORB
 * processors are faked at the existing [StillProcessor] seam; capture session,
 * match state machine, and ViewModel are the real production objects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanEntryFlowTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class NoPersistence : SealedFingerprintPersistence {
        override suspend fun persist(
            capsuleId: String,
            side: dev.hryshyn.remanence.core.data.db.FingerprintSide,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String = "fp-1"

        override suspend fun hasBaseline(
            capsuleId: String,
            side: dev.hryshyn.remanence.core.data.db.FingerprintSide,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ): Boolean = false

        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        override suspend fun setPreferredPair(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            side: dev.hryshyn.remanence.core.data.db.FingerprintSide,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    /** Accepts every still with a real serialized fingerprint of the given side. */
    private class AcceptedProcessor(private val side: FingerprintSide) : StillProcessor {
        private val profile = RecognitionProfile.mvpOrbV1()
        override fun process(jpegBytes: ByteArray): ProcessedStill = ProcessedStill.Accepted(
            profileId = profile.profileId,
            serializedBytes = serializedSynthetic(profile, side),
        )
    }

    private lateinit var database: RemanenceLocalDatabase

    private fun viewModel(): ScanViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return ScanViewModel(
            persistence = NoPersistence(),
            database = database,
            profile = RecognitionProfile.mvpOrbV1(),
            identityProvider = { null },
            // FIX-REVIEW2-04: unreachable here (no identity), but the API
            // requires THE trusted boundary explicitly.
            trustedSenderKeys = dev.hryshyn.remanence.identity.DirectorySenderKeyStore(
                directoryFetch = { error("verification must be unreachable without identity") },
                ownAccount = { null },
            ),
            presentationGrants = dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority(
                dev.hryshyn.remanence.core.recognition.ScanGrantManager(clockMillis = { 0L }),
            ),
            frontProcessor = AcceptedProcessor(FingerprintSide.FRONT),
            backProcessor = AcceptedProcessor(FingerprintSide.BACK),
            candidateIndexProvider = { ScanCandidateIndex.EMPTY },
            incomingPresentationPreparation = null,
            // FIX-STATE-01: delivery completes synchronously under the test
            // dispatcher so assertions are deterministic.
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )
    }

    /**
     * FIX-STATE-01: drives THE authoritative capture controllers exactly as
     * the production surface does - permission, bind, shutter, delivery.
     */
    private fun readyCameras(vm: ScanViewModel) {
        listOf(vm.frontAttempt, vm.backAttempt).forEach { controller: CaptureAttemptController ->
            controller.onPermissionResult(granted = true, canAskAgain = false)
            assertEquals(CaptureAttemptPhase.Binding, controller.phase)
            controller.onPreviewBound()
            assertEquals(CapturePermissionStep.Granted, controller.permission)
        }
    }

    private fun deliverFront(vm: ScanViewModel) {
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("jpeg-front".toByteArray())
    }

    private fun deliverBack(vm: ScanViewModel) {
        assertTrue(vm.beginBackCapture())
        vm.deliverBackJpeg("jpeg-back".toByteArray())
    }

    @Test
    fun entryIsFrontCaptureThenBackThenMatchingAndResetReturnsToFront() = runBlocking {
        val vm = viewModel()

        // First entry: honest capture state with the FRONT camera reachable -
        // never Matching text without captures.
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
        assertEquals(ScanSessionState.AWAITING_FRONT, vm.captureSession.state)

        readyCameras(vm)
        deliverFront(vm)

        assertEquals(ScanSessionState.AWAITING_BACK, vm.captureSession.state)
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)

        deliverBack(vm)

        // Both sides exist, so matching actually ran (empty index => guidance).
        assertNotNull(vm.captureSession.front)
        assertNotNull(vm.captureSession.back)
        assertEquals(ScanSessionState.READY_FOR_MATCHING, vm.captureSession.state)
        assertEquals(ScanMatchUiState.RecaptureGuidance(failedAttempts = 1), vm.matchState.value)

        // Explicit restart returns the WHOLE flow to FRONT.
        vm.resetSession()

        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
        assertEquals(ScanSessionState.AWAITING_FRONT, vm.captureSession.state)
        assertNull(vm.captureSession.front)
        assertNull(vm.captureSession.back)

        database.close()
    }

    @Test
    fun staleAsyncMatchResultCannotOverwriteAFreshCaptureFlow() = runBlocking {
        val vm = viewModel()

        readyCameras(vm)
        deliverFront(vm)
        deliverBack(vm)
        assertEquals(ScanMatchUiState.RecaptureGuidance(failedAttempts = 1), vm.matchState.value)

        // A reset discards the finished generation; a later stale write (the
        // chooser path shares the guard) cannot resurrect old results.
        vm.resetSession()
        vm.onChooserSelected(UUID.randomUUID().toString())

        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
        assertEquals(ScanTerminalState.Idle, vm.terminal.value)

        database.close()
    }

    private companion object {
        fun serializedSynthetic(
            profile: RecognitionProfile,
            side: FingerprintSide,
        ): ByteArray {
            val keypoints = List(64) {
                dev.hryshyn.remanence.core.recognition.FingerprintKeypoint(
                    xNormalized = (it % 8) / 8.0,
                    yNormalized = (it / 8) / 8.0,
                    scaleNormalized = 1.0,
                    angleCentiDegrees = 0,
                    responseQuantized = it,
                    octave = 0,
                )
            }
            val fingerprint = dev.hryshyn.remanence.core.recognition.PostcardFingerprint(
                profileId = profile.profileId,
                side = side,
                canonicalWidthPx = profile.capture.canonicalLongEdgePx,
                canonicalHeightPx = 1000,
                coarseHash64 = 7L,
                keypoints = keypoints,
                descriptors = List(64) { i -> ByteArray(32) { ((i * 13 + it) and 0xFF).toByte() } },
                quality = dev.hryshyn.remanence.core.recognition.ExtractionQuality(200.0, 90.0, 0.01, 0.85),
            )
            return dev.hryshyn.remanence.core.recognition.FingerprintCodec.serialize(fingerprint)
        }
    }
}
