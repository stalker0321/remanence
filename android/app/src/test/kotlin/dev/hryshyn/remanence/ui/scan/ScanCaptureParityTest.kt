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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.QualityReason
import dev.hryshyn.remanence.core.recognition.RecognitionProfile

/**
 * FIX-STATE-05 parity regression: the scan flow satisfies the SAME capture
 * contract as create - exceptions never hang, repeated rejections are
 * retriable, matching runs only after an accepted pair, and reset wipes the
 * pair AND invalidates in-flight work so stale callbacks are inert.
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

    /** Scripted per-side outcomes; one entry consumes one delivered still. */
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

    private fun synthetic(side: FingerprintSide, seed: Long = 7L): ProcessedStill.Accepted {
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
                    coarseHash64 = seed,
                    keypoints = keypoints,
                    descriptors = List(64) { i ->
                        ByteArray(32) { ((it * 7 + i * 13 + seed.toInt() * 29) and 0xFF).toByte() }
                    },
                    quality = dev.hryshyn.remanence.core.recognition.ExtractionQuality(200.0, 90.0, 0.01, 0.85),
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

    private fun viewModel(front: StillProcessor, back: StillProcessor): ScanViewModel = ScanViewModel(
        persistence = NoPersistence(),
        database = database,
        profile = RecognitionProfile.mvpOrbV1(),
        identityProvider = { null },
        trustedSenderKeys = dev.hryshyn.remanence.identity.DirectorySenderKeyStore(
            directoryFetch = { error("verification must be unreachable in this test") },
            ownAccount = { null },
        ),
        presentationGrants = dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority(
            dev.hryshyn.remanence.core.recognition.ScanGrantManager(clockMillis = { 0L }),
        ),
        frontProcessor = front,
        backProcessor = back,
        candidateIndexProvider = { ScanCandidateIndex.EMPTY },
        incomingPresentationPreparation = null,
        cpuDispatcher = testDispatcher,
        ioDispatcher = testDispatcher,
    )

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
            synthetic(FingerprintSide.FRONT),
        )
        val back = ScriptedProcessor(synthetic(FingerprintSide.BACK))
        val vm = viewModel(front, back)

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
        assertEquals(ScanSessionState.AWAITING_BACK, vm.captureSession.state)
        assertEquals(3, front.calls.size)
        Unit
    }

    // ------------------------------------------------------------------
    // Processor exceptions terminate visibly and remain retriable.
    // ------------------------------------------------------------------

    @Test
    fun processorExceptionShowsFailedThenRetryAcceptsIntoBackThenMatching() = runBlocking {
        val front = ScriptedProcessor("orb exploded", synthetic(FingerprintSide.FRONT))
        val back = ScriptedProcessor(synthetic(FingerprintSide.BACK))
        val vm = viewModel(front, back)
        bind(vm.frontAttempt)
        bind(vm.backAttempt)

        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("boom".toByteArray())

        val failed = vm.frontAttempt.phase as CaptureAttemptPhase.Failed
        assertEquals("orb exploded", failed.message)
        assertEquals(ScanSessionState.AWAITING_FRONT, vm.captureSession.state)

        vm.retakeFront()
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("good".toByteArray())
        assertEquals(CaptureAttemptPhase.Accepted, vm.frontAttempt.phase)
        assertEquals(ScanSessionState.AWAITING_BACK, vm.captureSession.state)

        assertTrue(vm.beginBackCapture())
        vm.deliverBackJpeg("back".toByteArray())

        // Accepted pair => matching actually ran (empty index => guidance).
        assertEquals(ScanSessionState.READY_FOR_MATCHING, vm.captureSession.state)
        assertEquals(ScanMatchUiState.RecaptureGuidance(failedAttempts = 1), vm.matchState.value)
        Unit
    }

    // ------------------------------------------------------------------
    // Ordering guards: wrong-side deliveries are inert.
    // ------------------------------------------------------------------

    @Test
    fun backDeliveryBeforeAnAcceptedFrontIsInert() = runBlocking {
        val front = ScriptedProcessor(synthetic(FingerprintSide.FRONT))
        val back = ScriptedProcessor(synthetic(FingerprintSide.BACK))
        val vm = viewModel(front, back)
        bind(vm.frontAttempt)
        bind(vm.backAttempt)

        vm.deliverBackJpeg("stray-back".toByteArray())

        assertEquals(ScanSessionState.AWAITING_FRONT, vm.captureSession.state)
        assertEquals(0, back.calls.size)
        // The BACK controller was bound but never left Ready.
        assertEquals(dev.hryshyn.remanence.capture.CaptureAttemptPhase.Ready, vm.backAttempt.phase)
        Unit
    }

    // ------------------------------------------------------------------
    // Reset wipes the pair and invalidates in-flight work.
    // ------------------------------------------------------------------

    @Test
    fun resetWipesPairCancelsActiveAttemptsAndStaleDeliveryIsInert() = runBlocking {
        val front = ScriptedProcessor(synthetic(FingerprintSide.FRONT))
        val back = ScriptedProcessor(synthetic(FingerprintSide.BACK))
        val vm = viewModel(front, back)
        bind(vm.frontAttempt)
        bind(vm.backAttempt)

        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("front".toByteArray())
        assertTrue(vm.beginBackCapture())
        vm.deliverBackJpeg("back".toByteArray())
        assertEquals(ScanMatchUiState.RecaptureGuidance(failedAttempts = 1), vm.matchState.value)

        // "Start over": whole flow returns to FRONT.
        vm.resetSession()
        assertEquals(ScanSessionState.AWAITING_FRONT, vm.captureSession.state)
        assertNull(vm.captureSession.front)
        assertNull(vm.captureSession.back)
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)

        // A stale BACK delivery for the wiped session must do nothing at all.
        vm.deliverBackJpeg("stale".toByteArray())
        assertEquals(ScanSessionState.AWAITING_FRONT, vm.captureSession.state)
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)
        assertEquals(1, back.calls.size)
        assertFalse(vm.beginBackCapture())
        Unit
    }

    @Test
    fun beginSessionAlsoResetsAuthoritativeAttempts() = runBlocking {
        val front = ScriptedProcessor(synthetic(FingerprintSide.FRONT))
        val back = ScriptedProcessor(synthetic(FingerprintSide.BACK))
        val vm = viewModel(front, back)
        vm.beginSession(epoch = 1L)
        bind(vm.frontAttempt)
        assertTrue(vm.beginFrontCapture())
        assertNotNull(vm.frontAttempt.phase)

        vm.beginSession(epoch = 2L)

        assertNull(vm.frontAttempt.phase)
        assertNull(vm.backAttempt.phase)
        assertEquals(ScanSessionState.AWAITING_FRONT, vm.captureSession.state)
        Unit
    }
}
