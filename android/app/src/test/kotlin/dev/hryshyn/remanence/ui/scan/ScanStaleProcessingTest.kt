package dev.hryshyn.remanence.ui.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.capture.CaptureAttemptPhase
import dev.hryshyn.remanence.capture.CapturePermissionStep
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.scan.ScanSessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.recognition.RecognitionProfile

/**
 * FIX-STATE-10 regression: a FRONT processing result that resumes AFTER
 * resetSession()/beginSession() is fully inert - it can neither write the
 * queued still, nor advance captureSession out of AWAITING_FRONT, nor start
 * matching or grants. Deterministic via a paused StandardTestDispatcher: the
 * pipeline is literally QUEUED work until the test advances it.
 */
private fun scanSynthetic(): ProcessedStill.Accepted {
    val profile = RecognitionProfile.mvpOrbV1()
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
    return ProcessedStill.Accepted(
        profileId = profile.profileId,
        serializedBytes = dev.hryshyn.remanence.core.recognition.FingerprintCodec.serialize(
            dev.hryshyn.remanence.core.recognition.PostcardFingerprint(
                profileId = profile.profileId,
                canonicalWidthPx = profile.capture.canonicalLongEdgePx,
                canonicalHeightPx = 1000,
                coarseHash64 = 4L,
                keypoints = keypoints,
                descriptors = List(64) { i ->
                    ByteArray(32) { ((it * 3 + i * 19) and 0xFF).toByte() }
                },
                quality = dev.hryshyn.remanence.core.recognition.ExtractionQuality(200.0, 90.0, 0.01, 0.85),
            ),
        ),
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanStaleProcessingTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    /** Paused executor: pipeline work runs only on advanceUntilIdle(). */
    private val cpuDispatcher = StandardTestDispatcher()

    private lateinit var database: RemanenceLocalDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
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

    private class Accepting : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill = scanSynthetic()
    }


    private class NoPersistence : SealedFingerprintPersistence {
        override suspend fun persist(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String = "fp"

        override suspend fun hasBaseline(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ): Boolean = false

        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        override suspend fun setPreferredOrigin(capsuleId: String, origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    private fun newViewModel(): ScanViewModel = ScanViewModel(
        persistence = NoPersistence(),
        database = database,
        profile = RecognitionProfile.mvpOrbV1(),
        identityProvider = { null },
        trustedSenderKeys = dev.hryshyn.remanence.identity.DirectorySenderKeyStore(
            directoryFetch = { error("verification unreachable in this test") },
            ownAccount = { null },
        ),
        presentationGrants = dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority(
            dev.hryshyn.remanence.core.recognition.ScanGrantManager(clockMillis = { 0L }),
        ),
        frontProcessor = Accepting(),
        candidateIndexProvider = { ScanCandidateIndex.EMPTY },
        incomingPresentationPreparation = null,
        cpuDispatcher = cpuDispatcher,
        ioDispatcher = cpuDispatcher,
    ).also { it.beginSession(1L) }

    private fun bind(attempt: dev.hryshyn.remanence.capture.CaptureAttemptController) {
        // Permission survives resets; only a truly unresolved one is asked.
        if (attempt.permission != CapturePermissionStep.Granted &&
            attempt.permission != CapturePermissionStep.DeniedRetryable
        ) {
            attempt.onPermissionResult(granted = true, canAskAgain = false)
        }
        if (attempt.phase is CaptureAttemptPhase.Binding) attempt.onPreviewBound()
        assertEquals(CapturePermissionStep.Granted, attempt.permission)
    }

    /**
     * THE FRONT-only contract: begin a FRONT capture, park its pipeline
     * mid-processing, reset/re-enter the flow, finish the OLD work - and the
     * fresh session proves untouched.
     */
    private fun staleOutcomeIsInert(invalidate: (ScanViewModel) -> Unit) {
        val vm = newViewModel()
        bind(vm.frontAttempt)

        // Begin the FRONT capture whose processing will be parked.
        assertTrue(vm.beginFrontCapture())
        val deliveryGenerationBeforeReset = vm.deliveryGenerationForDiagnostics()

        vm.deliverFrontJpeg("stale-front".toByteArray())
        assertEquals(
            "processing must be parked mid-flight",
            CaptureAttemptPhase.Processing,
            vm.frontAttempt.phase,
        )

        // The user resets / re-enters while processing is still parked.
        invalidate(vm)
        assertTrue(vm.deliveryGenerationForDiagnostics() > deliveryGenerationBeforeReset)
        assertEquals(ScanSessionState.AWAITING_FRONT, vm.captureSession.state)
        assertNull(vm.captureSession.front)

        // NOW the abandoned pipeline finishes with ACCEPTANCE...
        cpuDispatcher.scheduler.advanceUntilIdle()

        // ...and the fresh session is completely untouched.
        assertEquals(ScanSessionState.AWAITING_FRONT, vm.captureSession.state)
        assertNull(vm.captureSession.front)
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
        assertEquals(ScanTerminalState.Idle, vm.terminal.value)

        // The stale result also cannot be smuggled in by a later delivery:
        // the fresh FRONT flow starts over cleanly and works end to end.
        bind(vm.frontAttempt)
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("fresh-front".toByteArray())
        cpuDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ScanSessionState.READY_FOR_MATCHING, vm.captureSession.state)
        assertEquals(CaptureAttemptPhase.Accepted, vm.frontAttempt.phase)
    }

    @Test
    fun staleFrontProcessingAfterResetIsInert() =
        staleOutcomeIsInert { it.resetSession() }

    @Test
    fun staleFrontProcessingAfterNewEpochBeginSessionIsInert() =
        staleOutcomeIsInert { it.beginSession(epoch = 2L) }
}
