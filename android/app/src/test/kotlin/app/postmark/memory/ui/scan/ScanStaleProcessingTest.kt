package app.postmark.memory.ui.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.postmark.memory.capture.CaptureAttemptPhase
import app.postmark.memory.capture.CapturePermissionStep
import app.postmark.memory.capture.ProcessedStill
import app.postmark.memory.capture.StillProcessor
import app.postmark.memory.scan.ScanSessionState
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
import postmark.core.data.db.PostmarkLocalDatabase
import postmark.core.data.fingerprints.SealedFingerprintPersistence
import postmark.core.recognition.FingerprintSide
import postmark.core.recognition.RecognitionProfile

/**
 * FIX-STATE-10 regression: a processing result that resumes AFTER
 * resetSession()/beginSession() is fully inert - it can neither write the
 * queued still, nor advance captureSession out of AWAITING_FRONT, nor start
 * matching or grants. Deterministic via a paused StandardTestDispatcher: the
 * pipeline is literally QUEUED work until the test advances it.
 */
private fun scanSynthetic(side: FingerprintSide): ProcessedStill.Accepted {
    val profile = RecognitionProfile.mvpOrbV1()
    val keypoints = List(64) {
        postmark.core.recognition.FingerprintKeypoint(
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
        serializedBytes = postmark.core.recognition.FingerprintCodec.serialize(
            postmark.core.recognition.PostcardFingerprint(
                profileId = profile.profileId,
                side = side,
                canonicalWidthPx = profile.capture.canonicalLongEdgePx,
                canonicalHeightPx = 1000,
                coarseHash64 = 4L,
                keypoints = keypoints,
                descriptors = List(64) { i ->
                    ByteArray(32) { ((it * 3 + i * 19) and 0xFF).toByte() }
                },
                quality = postmark.core.recognition.ExtractionQuality(200.0, 90.0, 0.01, 0.85),
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

    private lateinit var database: PostmarkLocalDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PostmarkLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
    }

    private class Accepting(private val side: FingerprintSide) : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill = scanSynthetic(side)
    }


    private class NoPersistence : SealedFingerprintPersistence {
        override suspend fun persist(
            capsuleId: String,
            side: postmark.core.data.db.FingerprintSide,
            origin: postmark.core.data.db.FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String = "fp"

        override suspend fun hasBaseline(
            capsuleId: String,
            side: postmark.core.data.db.FingerprintSide,
            origin: postmark.core.data.db.FingerprintOrigin,
        ): Boolean = false

        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        override suspend fun setPreferredPair(capsuleId: String, origin: postmark.core.data.db.FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            side: postmark.core.data.db.FingerprintSide,
            origin: postmark.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    private fun newViewModel(): ScanViewModel = ScanViewModel(
        persistence = NoPersistence(),
        database = database,
        profile = RecognitionProfile.mvpOrbV1(),
        identityProvider = { null },
        trustedSenderKeys = app.postmark.memory.identity.DirectorySenderKeyStore(
            directoryFetch = { error("verification unreachable in this test") },
            ownAccount = { null },
        ),
        grantsClockMillis = { 0L },
        frontProcessor = Accepting(FingerprintSide.FRONT),
        backProcessor = Accepting(FingerprintSide.BACK),
        candidateIndexProvider = { emptyList() },
        cpuDispatcher = cpuDispatcher,
        ioDispatcher = cpuDispatcher,
    ).also { it.beginSession(1L) }

    private fun bind(attempt: app.postmark.memory.capture.CaptureAttemptController) {
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
     * THE shared contract for both sides: begin a capture, park its pipeline
     * mid-processing, reset/re-enter the flow, finish the OLD work - and the
     * fresh session proves untouched.
     */
    private fun staleOutcomeIsInert(side: FingerprintSide, invalidate: (ScanViewModel) -> Unit) {
        val vm = newViewModel()
        bind(vm.frontAttempt)
        bind(vm.backAttempt)

        // Reach the awaited state for this side legally.
        if (side == FingerprintSide.BACK) {
            assertTrue(vm.beginFrontCapture())
            vm.deliverFrontJpeg("front".toByteArray())
            cpuDispatcher.scheduler.advanceUntilIdle() // completes within THIS generation
            assertEquals(ScanSessionState.AWAITING_BACK, vm.captureSession.state)
        }

        // Begin the capture whose processing will be parked.
        val began = if (side == FingerprintSide.FRONT) vm.beginFrontCapture() else vm.beginBackCapture()
        assertTrue(began)
        val deliveryGenerationBeforeReset = vm.deliveryGenerationForDiagnostics()

        if (side == FingerprintSide.FRONT) {
            vm.deliverFrontJpeg("stale-front".toByteArray())
        } else {
            vm.deliverBackJpeg("stale-back".toByteArray())
        }
        assertEquals(
            "processing must be parked mid-flight",
            CaptureAttemptPhase.Processing,
            (if (side == FingerprintSide.FRONT) vm.frontAttempt else vm.backAttempt).phase,
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
        assertNull(vm.captureSession.back)
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
        assertEquals(ScanTerminalState.Idle, vm.terminal.value)

        // The stale result also cannot be smuggled in by a later delivery:
        // the fresh FRONT flow starts over cleanly and works end to end.
        bind(vm.frontAttempt)
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("fresh-front".toByteArray())
        cpuDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ScanSessionState.AWAITING_BACK, vm.captureSession.state)
        assertEquals(CaptureAttemptPhase.Accepted, vm.frontAttempt.phase)
    }

    @Test
    fun staleFrontProcessingAfterResetIsInert() =
        staleOutcomeIsInert(FingerprintSide.FRONT) { it.resetSession() }

    @Test
    fun staleFrontProcessingAfterNewEpochBeginSessionIsInert() =
        staleOutcomeIsInert(FingerprintSide.FRONT) { it.beginSession(epoch = 2L) }

    @Test
    fun staleBackProcessingAfterResetIsInertAndNeverStartsMatching() =
        staleOutcomeIsInert(FingerprintSide.BACK) { it.resetSession() }
}
