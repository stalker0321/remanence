package dev.hryshyn.remanence.ui.scan

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.capture.CaptureAttemptPhase
import dev.hryshyn.remanence.capture.CapturePermissionStep
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.scan.ScanSessionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val boundary = CancelBoundary()
    /**
     * Recording Main: unconfined immediacy (executes inline) with an
     * OBSERVABLE dispatch boundary, so the withContext resume hop can be
     * intercepted deterministically. UnconfinedTestDispatcher cannot be
     * wrapped by delegation (its dispatch is yield-internal), hence inline
     * execution here instead of delegate.dispatch.
     */
    private val mainDispatcher = RecordingMain(boundary)
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

    private fun newViewModel(
        processor: StillProcessor = Accepting(),
    ): ScanViewModel = ScanViewModel(
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
        frontProcessor = processor,
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

    /**
     * M2-F0-07 cancellation boundary proof: the worker RETURNS Accepted, and
     * the delivery job is cancelled only when the withContext resume
     * continuation is dispatched - demonstrably after the return, and before
     * the resume executes any handoff. The abandoned accepted buffer never
     * reaches queuedStill/captureSession yet must still be zeroized.
     *
     * The [RecordingMain] wrapper below observes the dispatcher boundary
     * itself (not worker internals): the hook fires on the resume dispatch
     * if and only if the worker already logged its return, then cancels
     * before delegating to the real dispatcher. The ordered [CancelBoundary]
     * event log makes the causality auditable instead of hand-waved.
     */
    private class CancelBoundary {
        val events = mutableListOf<String>()
        var workerReturned = false
        var deliveryJob: Job? = null
        var hookArmed = false
    }

    /**
     * Main dispatcher wrapper: forces every Main hop through an OBSERVABLE
     * dispatch boundary while executing inline (unconfined immediacy), and
     * fires the cancellation hook exactly when the resume continuation is
     * dispatched after the worker return - before the resume itself executes.
     */
    private class RecordingMain(
        private val boundary: CancelBoundary,
    ) : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (boundary.hookArmed && boundary.workerReturned) {
                boundary.hookArmed = false
                boundary.events += "resume-dispatched(workerReturned=true)"
                (boundary.deliveryJob ?: error("delivery job not armed")).cancel()
                boundary.events += "delivery-cancelled-before-resume-executes"
            } else {
                boundary.events += "main-dispatch(workerReturned=${boundary.workerReturned})"
            }
            block.run()
        }
    }

    /** Returns the held Accepted and logs return completion before returning. */
    private class BoundaryAccepting(
        val accepted: ProcessedStill.Accepted,
        private val boundary: CancelBoundary,
    ) : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill {
            boundary.events += "process-enter"
            val result = accepted
            boundary.workerReturned = true
            boundary.events += "process-return"
            return result
        }
    }

    @Test
    fun cancelAfterProcessorReturnWipesAcceptedBytesBeforeHandoff() {
        val staged = scanSynthetic()
        assertTrue("synthetic fingerprint must be non-empty", staged.serializedBytes.isNotEmpty())
        assertTrue(
            "precondition: staged bytes are live",
            staged.serializedBytes.any { it != 0.toByte() },
        )
        val vm = newViewModel(BoundaryAccepting(staged, boundary))
        bind(vm.frontAttempt)

        assertTrue(vm.beginFrontCapture())
        val jpeg = ByteArray(8) { (it + 1).toByte() }
        boundary.events.clear()
        boundary.hookArmed = true
        val scopeJob = vm.viewModelScope.coroutineContext[Job] ?: error("no scope job")
        val before = scopeJob.children.toList()
        vm.deliverFrontJpeg(jpeg)
        assertEquals(
            "processing must be parked mid-flight",
            CaptureAttemptPhase.Processing,
            vm.frontAttempt.phase,
        )
        boundary.deliveryJob = (scopeJob.children.toList() - before.toSet()).single()

        // Run the parked worker to completion. withContext can only resume
        // afterwards, and the armed hook cancels the delivery exactly on
        // that resume dispatch - before the resume executes any handoff.
        cpuDispatcher.scheduler.advanceUntilIdle()

        // Causality proof: strictly ordered markers. The resume dispatch is
        // observed only after the worker return, and the cancel strictly
        // before the resume body runs.
        assertEquals(
            listOf(
                "main-dispatch(workerReturned=false)",
                "process-enter",
                "process-return",
                "resume-dispatched(workerReturned=true)",
                "delivery-cancelled-before-resume-executes",
            ),
            boundary.events.toList(),
        )
        assertTrue("worker return was observed before the cancel", boundary.workerReturned)
        assertFalse("resume hook fired exactly once", boundary.hookArmed)
        assertTrue(
            "cancelled accepted bytes must be zeroized",
            staged.serializedBytes.all { it == 0.toByte() },
        )
        assertTrue(
            "delivered jpeg must be zeroized",
            jpeg.all { it == 0.toByte() },
        )
        assertNull(vm.captureSession.front)
        assertEquals(ScanSessionState.AWAITING_FRONT, vm.captureSession.state)
        assertEquals(CaptureAttemptPhase.Binding, vm.frontAttempt.phase)
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
    }
}
