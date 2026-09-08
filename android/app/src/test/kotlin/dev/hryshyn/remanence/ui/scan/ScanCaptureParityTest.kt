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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.ui.create.SenderIdentitySnapshot
import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec
import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprint
import dev.hryshyn.remanence.core.model.SiftRootSiftKeypoint
import dev.hryshyn.remanence.core.recognition.IndexedCandidate
import dev.hryshyn.remanence.core.recognition.LocalMatchEngine
import dev.hryshyn.remanence.core.recognition.QualityReason
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.core.recognition.ScanFlowResult

/**
 * M2-F0-07 parity regression: the FRONT-only scan flow satisfies the SAME
 * capture contract as create - exceptions never hang, repeated rejections are
 * retriable, matching runs only after one accepted FRONT, and reset wipes the
 * FRONT AND invalidates in-flight work so stale callbacks are inert.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanCaptureParityTest {

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

    /** Scripted outcomes; one entry consumes one delivered still. */
    private class ScriptedProcessor(
        vararg script: Any,
    ) : StillProcessor {
        private val queue = ArrayDeque(script.toList())
        val calls = mutableListOf<Any>()

        override fun process(jpegBytes: ByteArray): ProcessedStill {
            val next = queue.removeFirstOrNull() ?: error("unexpected delivery")
            calls += next
            return when (next) {
                is ProcessedStill -> next
                is String -> {
                    throw IllegalStateException(next)
                }
                else -> error("bad script")
            }
        }
    }

    private fun synthetic(seed: Long = 7L): ProcessedStill.Accepted {
        val profile = RecognitionProfile.postcardSiftRootSiftV1()
        val keypoints = List(64) {
            SiftRootSiftKeypoint(
                xMicro = Math.rint((it % 8) / 8.0 * SiftRootSiftFingerprintCodec.MICRO_UNITS).toInt(),
                yMicro = Math.rint((it / 8) / 8.0 * SiftRootSiftFingerprintCodec.MICRO_UNITS).toInt(),
                scaleMicro = SiftRootSiftFingerprintCodec.MICRO_UNITS,
                angleCentiDegrees = 0,
                responseQuantized = it,
                octave = 0,
            )
        }
        return ProcessedStill.Accepted(
            profileId = profile.profileId,
            serializedBytes = SiftRootSiftFingerprintCodec.serialize(
                SiftRootSiftFingerprint(
                    profileId = profile.profileId,
                    canonicalWidthPx = profile.capture.canonicalLongEdgePx,
                    canonicalHeightPx = 1000,
                    coarseHash64 = seed,
                    keypoints = keypoints,
                    quantizedSiftDescriptors = List(64) { i ->
                        ByteArray(SiftRootSiftFingerprintCodec.DESCRIPTOR_BYTES) {
                            ((it * 7 + i * 13 + seed.toInt() * 29).coerceIn(1, 255)).toByte()
                        }
                    },
                ),
            ),
        )
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

    private fun viewModel(
        front: StillProcessor,
        candidateIndexProvider: suspend (UserId) -> ScanCandidateIndex = { ScanCandidateIndex.EMPTY },
        identityProvider: suspend () -> SenderIdentitySnapshot? = { null },
    ): ScanViewModel = ScanViewModel(
        persistence = NoPersistence(),
        database = database,
        profile = RecognitionProfile.postcardSiftRootSiftV1(),
        identityProvider = identityProvider,
        trustedSenderKeys = dev.hryshyn.remanence.identity.DirectorySenderKeyStore(
            directoryFetch = { error("verification must be unreachable in this test") },
            ownAccount = { null },
        ),
        presentationGrants = dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority(
            dev.hryshyn.remanence.core.recognition.ScanGrantManager(clockMillis = { 0L }),
        ),
        frontProcessor = front,
        matcher = deterministicMatcher(),
        candidateIndexProvider = candidateIndexProvider,
        incomingPresentationPreparation = null,
        cpuDispatcher = testDispatcher,
        ioDispatcher = testDispatcher,
    )

    /** Keeps routing assertions independent of native OpenCV availability. */
    private fun deterministicMatcher() = dev.hryshyn.remanence.core.recognition.SiftRootSiftMatcherPort {
            query, reference ->
        val queryUsable = query.quantizedSiftDescriptors.count { row -> row.any { it.toInt() != 0 } }
        val referenceUsable = reference.quantizedSiftDescriptors.count { row -> row.any { it.toInt() != 0 } }
        val count = if (query.coarseHash64 == reference.coarseHash64) minOf(queryUsable, referenceUsable) else 0
        val pairs = (0 until count).map { index ->
            dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchPair(index, index, 0.1)
        }
        dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchResult(
            matches = pairs,
            inlierMatchIndices = pairs.indices.toList(),
            homographyRowMajor = if (count >= 6) doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0) else null,
            diagnostics = dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchDiagnostics(
                rawQueryRows = query.quantizedSiftDescriptors.size,
                rawReferenceRows = reference.quantizedSiftDescriptors.size,
                usableQueryRows = queryUsable,
                usableReferenceRows = referenceUsable,
                forwardRatioMatches = count,
                reverseRatioMatches = count,
                reciprocalMatches = count,
                uniqueMatches = count,
                geometryAttempted = count >= 6,
                geometryFound = count >= 6,
                geometryAccepted = count >= 6,
                inliers = count,
                inlierRatio = if (count == 0) 0.0 else 1.0,
                medianInlierReprojectionErrorPx = if (count == 0) -1.0 else 1.0,
                referenceConvexHullCoverage = if (count == 0) -1.0 else 1.0,
                supportAreaPx2 = if (count == 0) 0.0 else 400.0,
                supportEdgeRatio = if (count == 0) 0.0 else 1.0,
                failure = if (count >= 6) dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchFailure.NONE
                else if (count == 0) dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchFailure.NO_RATIO_MATCHES
                else dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchFailure.INSUFFICIENT_UNIQUE_PAIRS,
            ),
        )
    }

    /** Resolves permission and binds exactly as the production surface does. */
    private fun bind(attempt: CaptureAttemptController) {
        attempt.onPermissionResult(granted = true, canAskAgain = false)
        attempt.onPreviewBound()
        assertEquals(CapturePermissionStep.Granted, attempt.permission)
    }

    // ------------------------------------------------------------------
    // Repeated identical rejection is retriable.
    // ------------------------------------------------------------------

    @Test
    fun repeatedIdenticalFrontRejectionsYieldFreshRetriableAttempts() = runBlocking {
        val rejection = setOf(QualityReason.TOO_BLURRY)
        val front = ScriptedProcessor(
            ProcessedStill.Rejected(rejection),
            ProcessedStill.Rejected(rejection),
            synthetic(),
        )
        val vm = viewModel(front)

        bind(vm.frontAttempt)

        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("a".toByteArray())
        val first = vm.frontAttempt.phase as CaptureAttemptPhase.Rejected
        assertEquals(rejection, first.reasons)
        assertEquals(ScanSessionState.AWAITING_FRONT, vm.captureSession.state)

        vm.retakeFront()
        assertEquals(CaptureAttemptPhase.Binding, vm.frontAttempt.phase)
        vm.frontAttempt.onPreviewBound()

        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("b".toByteArray())
        val second = vm.frontAttempt.phase as CaptureAttemptPhase.Rejected
        assertNotEquals(first.attemptId, second.attemptId)
        assertEquals(first.reasons, second.reasons)

        vm.retakeFront()
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("c".toByteArray())

        assertEquals(CaptureAttemptPhase.Accepted, vm.frontAttempt.phase)
        assertEquals(ScanSessionState.READY_FOR_MATCHING, vm.captureSession.state)
        assertEquals(ScanMatchUiState.IndexUnavailable, vm.matchState.value)
        assertEquals(3, front.calls.size)
        Unit
    }

    // ------------------------------------------------------------------
    // Processor exceptions terminate visibly and remain retriable.
    // ------------------------------------------------------------------

    @Test
    fun processorExceptionShowsFailedThenRetryAcceptsIntoMatching() = runBlocking {
        val front = ScriptedProcessor("sift extraction failed", synthetic())
        val vm = viewModel(front)
        bind(vm.frontAttempt)

        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("boom".toByteArray())

        val failed = vm.frontAttempt.phase as CaptureAttemptPhase.Failed
        assertEquals("sift extraction failed", failed.message)
        assertEquals(ScanSessionState.AWAITING_FRONT, vm.captureSession.state)

        vm.retakeFront()
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("good".toByteArray())
        assertEquals(CaptureAttemptPhase.Accepted, vm.frontAttempt.phase)

        // Accepted FRONT => matching cannot run without a local index.
        assertEquals(ScanSessionState.READY_FOR_MATCHING, vm.captureSession.state)
        assertEquals(ScanMatchUiState.IndexUnavailable, vm.matchState.value)
        Unit
    }

    // ------------------------------------------------------------------
    // Reset wipes the FRONT and invalidates in-flight work.
    // ------------------------------------------------------------------

    @Test
    fun resetWipesFrontCancelsActiveAttemptsAndStaleDeliveryIsInert() = runBlocking {
        val front = ScriptedProcessor(synthetic())
        val vm = viewModel(front)
        bind(vm.frontAttempt)

        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("front".toByteArray())
        assertEquals(ScanMatchUiState.IndexUnavailable, vm.matchState.value)

        // "Start over": whole flow returns to FRONT.
        vm.resetSession()
        assertEquals(ScanSessionState.AWAITING_FRONT, vm.captureSession.state)
        assertNull(vm.captureSession.front)
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)

        // A stale FRONT delivery for the wiped session must do nothing at all.
        // No active attempt exists, so the processor is never consulted again.
        vm.deliverFrontJpeg("stale".toByteArray())
        assertEquals(ScanSessionState.AWAITING_FRONT, vm.captureSession.state)
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
        assertEquals(1, front.calls.size)
        // After reset without rebind, a new shutter cannot begin until the
        // surface rebinds: restartCapture left the controller in Binding.
        assertFalse(vm.beginFrontCapture())
        assertEquals(dev.hryshyn.remanence.capture.CaptureAttemptPhase.Binding, vm.frontAttempt.phase)
        Unit
    }

    @Test
    fun beginSessionAlsoResetsAuthoritativeAttempts() = runBlocking {
        val front = ScriptedProcessor(synthetic())
        val vm = viewModel(front)
        vm.beginSession(epoch = 1L)
        bind(vm.frontAttempt)
        assertTrue(vm.beginFrontCapture())
        assertTrue(vm.frontAttempt.phase != null)

        vm.beginSession(epoch = 2L)

        assertNull(vm.frontAttempt.phase)
        assertEquals(ScanSessionState.AWAITING_FRONT, vm.captureSession.state)
        Unit
    }

    // ------------------------------------------------------------------
    // M2-F0-07: delivered JPEG bytes are zeroized on every path.
    // ------------------------------------------------------------------

    @Test
    fun deliveredJpegIsZeroizedOnSuccessRejectionAndFailure() = runBlocking {
        val rejection = setOf(QualityReason.TOO_BLURRY)
        val accepted = synthetic()
        val front = ScriptedProcessor(
            ProcessedStill.Rejected(rejection),
            "sift extraction failed",
            accepted,
        )
        val vm = viewModel(front)
        bind(vm.frontAttempt)

        assertTrue(vm.beginFrontCapture())
        val rejectedJpeg = ByteArray(8) { (it + 1).toByte() }
        vm.deliverFrontJpeg(rejectedJpeg)
        assertTrue(rejectedJpeg.all { it == 0.toByte() })
        assertTrue(vm.frontAttempt.phase is CaptureAttemptPhase.Rejected)

        vm.retakeFront()
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())
        val failedJpeg = ByteArray(8) { (it + 1).toByte() }
        vm.deliverFrontJpeg(failedJpeg)
        assertTrue(failedJpeg.all { it == 0.toByte() })
        assertTrue(vm.frontAttempt.phase is CaptureAttemptPhase.Failed)

        vm.retakeFront()
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())
        val successJpeg = ByteArray(8) { (it + 1).toByte() }
        vm.deliverFrontJpeg(successJpeg)
        assertTrue(successJpeg.all { it == 0.toByte() })
        assertEquals(CaptureAttemptPhase.Accepted, vm.frontAttempt.phase)
        assertEquals(ScanSessionState.READY_FOR_MATCHING, vm.captureSession.state)
        Unit
    }

    @Test
    fun staleAndUnadmittedDeliveriesZeroizeJpegWithoutConsultingProcessor() = runBlocking {
        val accepted = synthetic()
        val front = ScriptedProcessor(accepted)
        val vm = viewModel(front)
        bind(vm.frontAttempt)

        // No active attempt: admission rejects, JPEG still wiped.
        val unadmitted = ByteArray(8) { (it + 1).toByte() }
        vm.deliverFrontJpeg(unadmitted)
        assertTrue(unadmitted.all { it == 0.toByte() })
        assertEquals(0, front.calls.size)

        // Success moves the session out of AWAITING_FRONT.
        assertTrue(vm.beginFrontCapture())
        val successJpeg = ByteArray(8) { (it + 1).toByte() }
        vm.deliverFrontJpeg(successJpeg)
        assertTrue(successJpeg.all { it == 0.toByte() })
        assertEquals(ScanSessionState.READY_FOR_MATCHING, vm.captureSession.state)

        // Stale delivery for the finished session: wiped, processor untouched.
        val stale = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        vm.deliverFrontJpeg(stale)
        assertTrue(stale.all { it == 0.toByte() })
        assertEquals(1, front.calls.size)
        assertEquals(ScanSessionState.READY_FOR_MATCHING, vm.captureSession.state)
        Unit
    }

    @Test
    fun resetZeroizesAcceptedFingerprintAndStaleOutcomeWipesItsBytes() = runBlocking {
        // Success path: reset wipes the session-owned FRONT buffer.
        val accepted = synthetic()
        val staged = accepted.serializedBytes
        assertTrue(staged.isNotEmpty())
        val vm = viewModel(ScriptedProcessor(accepted))
        bind(vm.frontAttempt)
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg(ByteArray(8) { (it + 1).toByte() })
        assertEquals(ScanSessionState.READY_FOR_MATCHING, vm.captureSession.state)

        vm.resetSession()
        assertTrue(staged.all { it == 0.toByte() })
        assertNull(vm.captureSession.front)

        // Reset during processing: the stale accepted outcome wipes its bytes
        // instead of leaking, and the JPEG is wiped too.
        val staleAccepted = synthetic()
        val staleStaged = staleAccepted.serializedBytes
        lateinit var racing: ScanViewModel
        val racingProcessor = StillProcessor { _ ->
            racing.resetSession()
            staleAccepted
        }
        racing = viewModel(racingProcessor)
        bind(racing.frontAttempt)
        assertTrue(racing.beginFrontCapture())
        val racingJpeg = ByteArray(8) { (it + 1).toByte() }
        racing.deliverFrontJpeg(racingJpeg)
        assertTrue(racingJpeg.all { it == 0.toByte() })
        assertTrue(staleStaged.all { it == 0.toByte() })
        assertEquals(ScanSessionState.AWAITING_FRONT, racing.captureSession.state)
        assertNull(racing.captureSession.front)
        assertEquals(ScanMatchUiState.AwaitingCapture, racing.matchState.value)
        Unit
    }

    // ------------------------------------------------------------------
    // M2-F1 routing: one weak candidate is guided recapture, never a picker.
    // ------------------------------------------------------------------

    /**
     * Stored FRONT with only the first [intact] descriptors intact; the rest
     * read as noise, so the candidate lands weak-plausible below auto rules.
     */
    private fun weakenedFront(fullBytes: ByteArray, intact: Int): ByteArray {
        val full = SiftRootSiftFingerprintCodec.parse(fullBytes)
        val weakened = SiftRootSiftFingerprint(
            profileId = full.profileId,
            canonicalWidthPx = full.canonicalWidthPx,
            canonicalHeightPx = full.canonicalHeightPx,
            coarseHash64 = full.coarseHash64,
            keypoints = full.keypoints,
            quantizedSiftDescriptors = full.quantizedSiftDescriptors.mapIndexed { index, bytes ->
                if (index < intact) bytes.copyOf() else ByteArray(bytes.size)
            },
        )
        return try {
            SiftRootSiftFingerprintCodec.serialize(weakened)
        } finally {
            weakened.wipe()
            full.wipe()
        }
    }

    @Test
    fun singleWeakCandidateRendersGuidanceNeverChooserAndIssuesNoGrant() = runBlocking {
        val capsuleId = java.util.UUID.randomUUID()
        val fullBytes = synthetic().serializedBytes
        // Probed weak band: 18 intact descriptors classify exactly one
        // plausible below auto rules (16 and below do not match; 24 and
        // above verify). RANSAC is fixed-seed, so this pin is deterministic.
        val candidate = IndexedCandidate(
            capsuleId = capsuleId,
            front = SiftRootSiftFingerprintCodec.parse(weakenedFront(fullBytes, intact = 18)),
            recipientPreferred = false,
        )

        // Phase 1: pin the fixture through the REAL engine — exactly one
        // plausible below auto rules, so verifier/issuer must never run.
        val engine = LocalMatchEngine(
            RecognitionProfile.postcardSiftRootSiftV1(),
            verifier = { error("single weak candidate must never verify") },
            grantIssuer = { error("single weak candidate must never mint a grant") },
            matcher = deterministicMatcher(),
        )
        val decision = engine.run(SiftRootSiftFingerprintCodec.parse(fullBytes), listOf(candidate))
        assertTrue("fixture must classify SINGLE_CANDIDATE_RECAPTURE, got $decision", decision is ScanFlowResult.Ambiguous)
        val ambiguous = decision as ScanFlowResult.Ambiguous
        assertTrue(ambiguous.singleRecaptureFirst)
        assertTrue(ambiguous.rows.isEmpty())

        // Phase 2: the production ScanViewModel renders guided recapture for
        // that same single weak candidate — never a picker, never a grant.
        // A real authenticated identity is required: with a null identity
        // the candidate index is empty and the test would pass vacuously.
        val identity = AccountIdentityGenerator().generate()
        val userUuid = java.util.UUID.randomUUID()
        val vm = viewModel(
            StillProcessor { ProcessedStill.Accepted("postcard-sift-rootsift-v1", fullBytes.copyOf()) },
            candidateIndexProvider = {
                ScanCandidateIndex(
                    candidates = listOf(candidate),
                    presentationSources = emptyMap(),
                )
            },
            identityProvider = {
                SenderIdentitySnapshot(
                    userId = userUuid.toString(),
                    handle = "mykola",
                    activeKeyBundleId = java.util.UUID.randomUUID().toString(),
                    encryptionPrivateHandle = identity.encryptionPrivateHandle,
                    signingPrivateHandle = identity.signingPrivateHandle,
                )
            },
        )
        bind(vm.frontAttempt)
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("front".toByteArray())
        withTimeout(10_000) { vm.matchState.first { it !is ScanMatchUiState.Matching } }

        assertEquals(ScanSessionState.READY_FOR_MATCHING, vm.captureSession.state)
        assertTrue("single weak candidate must guide recapture, got ${vm.matchState.value}", vm.matchState.value is ScanMatchUiState.RecaptureGuidance)
        assertFalse("single weak candidate must never reach the picker", vm.matchState.value is ScanMatchUiState.Chooser)
        assertEquals(ScanTerminalState.Idle, vm.terminal.value)
        Unit
    }
}
