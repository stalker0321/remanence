package dev.hryshyn.remanence.ui.scan

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.capture.CaptureAttemptPhase
import dev.hryshyn.remanence.capture.CaptureAttemptController
import dev.hryshyn.remanence.capture.CapturePermissionStep
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.recognition.FrozenMatcherS3Contract
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.core.recognition.StrictCombinedDecision
import dev.hryshyn.remanence.core.recognition.StrictCombinedEvidence
import dev.hryshyn.remanence.core.recognition.StrictCombinedRejectReason
import dev.hryshyn.remanence.scan.ScanSessionState
import dev.hryshyn.remanence.scan.matcher.MatcherShadowConfig
import dev.hryshyn.remanence.scan.matcher.MatcherShadowFrame
import dev.hryshyn.remanence.scan.matcher.MatcherShadowInput
import dev.hryshyn.remanence.scan.matcher.MatcherShadowLocalizerOutcome
import dev.hryshyn.remanence.scan.matcher.MatcherShadowOutcome
import dev.hryshyn.remanence.scan.matcher.MatcherShadowRunner
import dev.hryshyn.remanence.scan.matcher.MatcherShadowSink
import dev.hryshyn.remanence.scan.matcher.MatcherShadowDiagnostics
import dev.hryshyn.remanence.scan.matcher.MatcherShadowStages
import dev.hryshyn.remanence.scan.matcher.MatcherShadowGateDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T118 shadow wiring proofs against the real ScanViewModel state machine:
 *
 * 1. v1 results are identical with the shadow absent, disabled, and enabled
 *    (the switch changes nothing observable in the v1 chain).
 * 2. Even a shadow KEEP decision cannot grant, open, or choose - the v1
 *    outcome and terminal state are untouched.
 * 3. Missing catalog is bounded and non-authoritative for v1.
 * 4. Reject/stale deliveries wipe the shadow snapshot without running it.
 * 5. After a run/reset there is no live shadow work and every snapshot is
 *    wiped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanViewModelMatcherShadowTest {

    private val eagerDispatcher = UnconfinedTestDispatcher()

    private lateinit var database: RemanenceLocalDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(eagerDispatcher)
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

    // ----------------------------------------------------------------------
    // Fakes (hermetic: no OpenCV/ONNX/native anywhere in this file)
    // ----------------------------------------------------------------------

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

        override suspend fun setPreferredOrigin(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    private class AcceptedProcessor : StillProcessor {
        private val profile = RecognitionProfile.postcardSiftRootSiftV1()
        override fun process(jpegBytes: ByteArray): ProcessedStill = ProcessedStill.Accepted(
            profileId = profile.profileId,
            serializedBytes = dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture.bytes(7),
        )
    }

    private class RejectedProcessor : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill =
            ProcessedStill.Rejected(setOf(dev.hryshyn.remanence.core.recognition.QualityReason.TOO_BLURRY))
    }

    private class RecordingSink : MatcherShadowSink {
        val entries = mutableListOf<MatcherShadowDiagnostics>()
        override fun report(diagnostics: MatcherShadowDiagnostics) {
            entries += diagnostics
        }
    }

    /** Adversarial hermetic stages: everything the shadow could "want". */
    private class AdversarialStages(
        var catalogBytes: () -> ByteArray? = { null },
        var gateResult: StrictCombinedDecision =
            StrictCombinedDecision.Keep,
    ) : MatcherShadowStages {
        var decodeCalls = 0
        var gateCalls = 0

        override fun decode(jpeg: ByteArray): MatcherShadowFrame? {
            decodeCalls++
            return MatcherShadowFrame(ByteArray(3 * 8 * 6) { 7.toByte() }, width = 8, height = 6)
        }

        override fun localize(
            frameBgr: ByteArray,
            width: Int,
            height: Int,
        ): MatcherShadowLocalizerOutcome = MatcherShadowLocalizerOutcome.Proposal

        override fun catalogIndex(): ByteArray? = catalogBytes.invoke()

        override fun gate(
            baselineAccepted: Boolean,
            evidence: StrictCombinedEvidence?,
        ): StrictCombinedDecision {
            gateCalls++
            return gateResult
        }
    }

    private class RecordingShadowRunner(
        config: MatcherShadowConfig,
        stages: MatcherShadowStages,
        sink: MatcherShadowSink,
    ) : MatcherShadowRunner(config, stages, sink) {
        val inputs = mutableListOf<MatcherShadowInput>()
        override fun snapshot(jpeg: ByteArray): MatcherShadowInput? =
            super.snapshot(jpeg)?.also { inputs += it }
    }

    // ----------------------------------------------------------------------
    // Harness
    // ----------------------------------------------------------------------

    private fun viewModel(shadow: MatcherShadowRunner? = null): ScanViewModel = ScanViewModel(
        persistence = NoPersistence(),
        database = database,
        profile = RecognitionProfile.postcardSiftRootSiftV1(),
        identityProvider = { null },
        trustedSenderKeys = dev.hryshyn.remanence.identity.DirectorySenderKeyStore(
            directoryFetch = { error("verification unreachable without identity") },
            ownAccount = { null },
        ),
        presentationGrants = dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority(
            dev.hryshyn.remanence.core.recognition.ScanGrantManager(clockMillis = { 0L }),
        ),
        frontProcessor = AcceptedProcessor(),
        candidateIndexProvider = { ScanCandidateIndex.EMPTY },
        incomingPresentationPreparation = null,
        cpuDispatcher = eagerDispatcher,
        ioDispatcher = eagerDispatcher,
        matcherShadow = shadow,
    )

    private fun readyCameras(vm: ScanViewModel) {
        val controller: CaptureAttemptController = vm.frontAttempt
        controller.onPermissionResult(granted = true, canAskAgain = false)
        assertEquals(CaptureAttemptPhase.Binding, controller.phase)
        controller.onPreviewBound()
        assertEquals(CapturePermissionStep.Granted, controller.permission)
    }

    private data class V1Observation(
        val matchState: ScanMatchUiState,
        val terminal: ScanTerminalState,
        val session: ScanSessionState,
        val attemptPhase: CaptureAttemptPhase?,
        val v1Diagnostic: String,
    )

    private fun drive(vm: ScanViewModel): V1Observation {
        readyCameras(vm)
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("jpeg-front".toByteArray())
        return V1Observation(
            matchState = vm.matchState.value,
            terminal = vm.terminal.value,
            session = vm.captureSession.state,
            attemptPhase = vm.frontAttempt.phase,
            v1Diagnostic = RecognitionDiagnostics.state.value,
        )
    }

    // ----------------------------------------------------------------------
    // 1. v1 identity: absent vs disabled vs enabled
    // ------------------------------------------------------------------

    @Test
    fun v1ResultsAreIdenticalWithShadowAbsentDisabledAndEnabled() {
        // Absent (null parameter - the production default).
        val absent = drive(viewModel(shadow = null))

        // Disabled by the local switch: no snapshot, nothing runs.
        val disabledSink = RecordingSink()
        val disabledRunner = RecordingShadowRunner(
            MatcherShadowConfig.off(),
            AdversarialStages(),
            disabledSink,
        )
        val disabled = drive(viewModel(shadow = disabledRunner))

        // Enabled with the most favorable adversarial stages possible.
        val enabledSink = RecordingSink()
        val enabledRunner = RecordingShadowRunner(
            MatcherShadowConfig.telemetryOnly(),
            AdversarialStages(catalogBytes = { FrozenMatcherS3Contract.buildIndex(emptyList()) }),
            enabledSink,
        )
        val enabled = drive(viewModel(shadow = enabledRunner))

        assertEquals(absent, disabled)
        assertEquals(absent, enabled)

        // The switch, not v1, is what differs.
        assertEquals(0, disabledSink.entries.size)
        assertEquals(0, disabledRunner.inputs.size)
        assertTrue(enabledRunner.inputs.isNotEmpty())
        assertEquals(1, enabledSink.entries.size)
        assertEquals(MatcherShadowOutcome.COMPLETED_DISCARDED, enabledSink.entries.single().outcome)

        // Every snapshot was consumed and wiped by the enabled run.
        assertTrue(enabledRunner.inputs.all { it.isWiped })
    }

    // ----------------------------------------------------------------------
    // 2. Shadow output cannot grant/open/choose
    // ------------------------------------------------------------------

    @Test
    fun shadowKeepDecisionCannotGrantOpenOrChoose() {
        val sink = RecordingSink()
        val runner = RecordingShadowRunner(
            MatcherShadowConfig.telemetryOnly(),
            AdversarialStages(
                catalogBytes = { FrozenMatcherS3Contract.buildIndex(emptyList()) },
                gateResult = StrictCombinedDecision.Keep,
            ),
            sink,
        )
        val vm = viewModel(shadow = runner)
        val observed = drive(vm)

        // The shadow ran to completion with a KEEP verdict...
        assertEquals(1, sink.entries.size)
        val diagnostics = sink.entries.single()
        assertEquals(MatcherShadowOutcome.COMPLETED_DISCARDED, diagnostics.outcome)
        assertEquals(MatcherShadowGateDecision.KEEP, diagnostics.gate)
        assertEquals(1, runner.inputs.size)

        // ...and v1 (with an EMPTY local index) is exactly what it always was:
        // no grant, no chooser, no recapture, terminal still Idle.
        assertEquals(ScanMatchUiState.IndexUnavailable, observed.matchState)
        assertEquals(ScanTerminalState.Idle, observed.terminal)
        assertEquals(ScanSessionState.READY_FOR_MATCHING, observed.session)
        assertEquals(CaptureAttemptPhase.Accepted, observed.attemptPhase)
        // No manual chooser was offered either: the chooser surface is only
        // ever entered from a live ScanMatchUiState.Chooser row set.
        assertTrue(observed.matchState !is ScanMatchUiState.Chooser)
        assertNotNull(observed.matchState)
    }

    // ----------------------------------------------------------------------
    // 3. Missing catalog is non-authoritative for v1
    // ------------------------------------------------------------------

    @Test
    fun missingCatalogIsBoundedAndNonAuthoritativeForV1() {
        val baseline = drive(viewModel(shadow = null))

        val sink = RecordingSink()
        val runner = RecordingShadowRunner(
            MatcherShadowConfig.telemetryOnly(),
            AdversarialStages(catalogBytes = { null }), // production shape: no S5 delivery
            sink,
        )
        val withMissingCatalog = drive(viewModel(shadow = runner))

        assertEquals(baseline, withMissingCatalog)
        assertEquals(1, sink.entries.size)
        assertEquals(MatcherShadowOutcome.UNAVAILABLE_CATALOG_MISSING, sink.entries.single().outcome)
        assertTrue(runner.inputs.all { it.isWiped })
    }

    // ----------------------------------------------------------------------
    // 4. Reject / stale deliveries wipe the snapshot without running
    // ------------------------------------------------------------------

    @Test
    fun rejectedDeliveryWipesSnapshotWithoutRunningTheShadow() {
        val sink = RecordingSink()
        val runner = RecordingShadowRunner(
            MatcherShadowConfig.telemetryOnly(),
            AdversarialStages(),
            sink,
        )
        val vm = ScanViewModel(
            persistence = NoPersistence(),
            database = database,
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            identityProvider = { null },
            trustedSenderKeys = dev.hryshyn.remanence.identity.DirectorySenderKeyStore(
                directoryFetch = { error("verification unreachable without identity") },
                ownAccount = { null },
            ),
            presentationGrants = dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority(
                dev.hryshyn.remanence.core.recognition.ScanGrantManager(clockMillis = { 0L }),
            ),
            frontProcessor = RejectedProcessor(),
            candidateIndexProvider = { ScanCandidateIndex.EMPTY },
            incomingPresentationPreparation = null,
            cpuDispatcher = eagerDispatcher,
            ioDispatcher = eagerDispatcher,
            matcherShadow = runner,
        )
        readyCameras(vm)
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("jpeg-front".toByteArray())

        // v1 rejected the capture; the shadow snapshot was taken but never
        // ran and was wiped on the reject path.
        assertEquals(1, runner.inputs.size)
        assertTrue("reject path must wipe the shadow snapshot", runner.inputs.single().isWiped)
        assertTrue(sink.entries.isEmpty())
        assertEquals(ScanTerminalState.Idle, vm.terminal.value)
    }

    @Test
    fun staleDeliveryAfterResetWipesSnapshotWithoutRunningTheShadow() {
        // Paused pipeline: delivery is queued work until the scheduler runs,
        // so a reset can land in between (the FIX-STATE-10 shape).
        val paused = StandardTestDispatcher()
        Dispatchers.setMain(paused)
        val sink = RecordingSink()
        val runner = RecordingShadowRunner(
            MatcherShadowConfig.telemetryOnly(),
            AdversarialStages(),
            sink,
        )
        val vm = viewModel(shadow = runner)
        readyCameras(vm)
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("jpeg-front".toByteArray())
        // Reset BEFORE the delivery coroutine ever runs.
        vm.resetSession()
        paused.scheduler.advanceUntilIdle()

        assertEquals(1, runner.inputs.size)
        assertTrue("stale path must wipe the shadow snapshot", runner.inputs.single().isWiped)
        assertTrue("stale path must not run the shadow", sink.entries.isEmpty())
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
        assertEquals(ScanSessionState.AWAITING_FRONT, vm.captureSession.state)
        Dispatchers.setMain(eagerDispatcher)
    }

    // ----------------------------------------------------------------------
    // 5. No live shadow work after run/reset; buffers wiped
    // ------------------------------------------------------------------

    @Test
    fun resetAfterShadowRunLeavesNoLiveShadowJobAndAllSnapshotsWiped() {
        val sink = RecordingSink()
        val runner = RecordingShadowRunner(
            MatcherShadowConfig.telemetryOnly(),
            AdversarialStages(catalogBytes = { FrozenMatcherS3Contract.buildIndex(emptyList()) }),
            sink,
        )
        val vm = viewModel(shadow = runner)
        drive(vm)
        assertEquals(1, sink.entries.size)

        val scopeJob = vm.viewModelScope.coroutineContext[Job] ?: error("no scope job")
        assertTrue("no live shadow work after the run", scopeJob.children.none { it.isActive })
        assertTrue(runner.inputs.all { it.isWiped })

        vm.resetSession()
        cpuIdle()
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
        assertEquals(ScanTerminalState.Idle, vm.terminal.value)
        assertTrue("no live shadow work after reset", scopeJob.children.none { it.isActive })
        assertTrue(runner.inputs.all { it.isWiped })
        assertNull(vm.captureSession.front)
    }

    private fun cpuIdle() = eagerDispatcher.scheduler.advanceUntilIdle()
}
