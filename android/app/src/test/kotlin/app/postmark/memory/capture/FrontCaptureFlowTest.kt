package app.postmark.memory.capture

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import postmark.core.data.db.FingerprintOrigin
import postmark.core.data.db.FingerprintSide
import postmark.core.data.fingerprints.SealedFingerprintPersistence
import postmark.core.recognition.QualityReason

/**
 * FIX-STATE-01/03 regression proof for the front still delivery: the attempt
 * ALWAYS terminates (processor exceptions included), quality rejections
 * persist nothing but stay retriable, stale deliveries are inert, and the
 * CPU pipeline really runs off Main on the injected dispatcher.
 */
class FrontCaptureFlowTest {

    private class FakePersistence : SealedFingerprintPersistence {
        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        val persisted = mutableListOf<Pair<FingerprintSide, ByteArray>>()
        var failNext = false

        override suspend fun persist(
            capsuleId: String,
            side: FingerprintSide,
            origin: FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String {
            if (failNext) {
                failNext = false
                throw IllegalStateException("disk full")
            }
            persisted += side to plaintextBytes
            return "fp-${persisted.size}"
        }

        override suspend fun hasBaseline(capsuleId: String, side: FingerprintSide, origin: FingerprintOrigin): Boolean =
            persisted.any { it.first == side }

        override suspend fun setPreferredPair(capsuleId: String, origin: FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(capsuleId: String, side: FingerprintSide, origin: FingerprintOrigin) = Unit
    }

    private val CAPSULE_ID = java.util.UUID.randomUUID().toString()

    private lateinit var persistence: FakePersistence

    @Before
    fun setUp() {
        persistence = FakePersistence()
    }

    /** Drives a controller to Capturing exactly as the production UI does. */
    private fun capturingController(): CaptureAttemptController =
        CaptureAttemptController().apply {
            onPermissionResult(granted = true, canAskAgain = false)
            onPreviewBound()
            beginAttempt()
        }

    @Test
    fun deliveredStillFlowsThroughToSealedPersistenceAndAccepts() = runBlocking {
        val flow = FrontCaptureFlow(
            StillProcessor { ProcessedStill.Accepted("mvp-orb-v1", "serialized-orb".toByteArray()) },
        )
        val controller = capturingController()

        val outcome = flow.onJpegDelivered("jpeg".toByteArray(), CAPSULE_ID, persistence, controller)

        assertTrue(outcome is FrontCaptureOutcome.Captured)
        assertEquals(
            listOf(FingerprintSide.FRONT to "serialized-orb"),
            persistence.persisted.map { it.first to String(it.second) },
        )
        assertEquals(CaptureAttemptPhase.Accepted, controller.phase)
    }

    @Test
    fun qualityRejectionPersistsNothingAndStaysRetriable() = runBlocking {
        val reasons = setOf(QualityReason.TOO_BLURRY, QualityReason.GLARE_EXCESSIVE)
        val flow = FrontCaptureFlow(StillProcessor { ProcessedStill.Rejected(reasons) })
        val controller = capturingController()

        val outcome = flow.onJpegDelivered("jpeg".toByteArray(), CAPSULE_ID, persistence, controller)

        assertEquals(FrontCaptureOutcome.QualityRejected(reasons), outcome)
        assertEquals(0, persistence.persisted.size)
        assertTrue(controller.phase is CaptureAttemptPhase.Rejected)

        // The identical rejection can be retried into a fresh working attempt.
        controller.startRetake()
        assertEquals(CaptureAttemptPhase.Binding, controller.phase)
    }

    /**
     * FIX-STATE-01 core regression: an OpenCV/pipeline EXCEPTION must end the
     * attempt visibly instead of crashing or leaving it in Processing forever.
     */
    @Test
    fun processorExceptionTerminatesTheAttemptAsFailedWithoutPersistence() = runBlocking {
        val flow = FrontCaptureFlow(StillProcessor { throw IllegalArgumentException("cannot decode jpeg") })
        val controller = capturingController()

        val outcome = flow.onJpegDelivered("jpeg".toByteArray(), CAPSULE_ID, persistence, controller)

        assertEquals(FrontCaptureOutcome.Failed("cannot decode jpeg"), outcome)
        assertEquals(0, persistence.persisted.size)
        assertEquals(CaptureAttemptPhase.Failed(1L, "cannot decode jpeg"), controller.phase)

        // And the user can retake.
        controller.startRetake()
        assertEquals(CaptureAttemptPhase.Binding, controller.phase)
    }

    @Test
    fun persistenceFailureSurfacesAsFailedTerminalNotSilentProgress() = runBlocking {
        persistence.failNext = true
        val flow = FrontCaptureFlow(
            StillProcessor { ProcessedStill.Accepted("mvp-orb-v1", "bytes".toByteArray()) },
        )
        val controller = capturingController()

        val outcome = flow.onJpegDelivered("jpeg".toByteArray(), CAPSULE_ID, persistence, controller)

        assertEquals(FrontCaptureOutcome.Failed("disk full"), outcome)
        assertEquals(0, persistence.persisted.size)
        assertEquals(CaptureAttemptPhase.Failed(1L, "disk full"), controller.phase)
    }

    @Test
    fun emptyStillFailsWithoutTouchingProcessor() = runBlocking {
        var processorCalls = 0
        val flow = FrontCaptureFlow(StillProcessor {
            processorCalls += 1
            ProcessedStill.Accepted("p", ByteArray(1))
        })
        val controller = capturingController()

        val outcome = flow.onJpegDelivered(ByteArray(0), CAPSULE_ID, persistence, controller)

        assertEquals(FrontCaptureOutcome.Failed("empty still"), outcome)
        assertEquals(0, processorCalls)
        assertEquals(0, persistence.persisted.size)
        assertTrue(controller.phase is CaptureAttemptPhase.Failed)
    }

    /** A delivery for a cancelled/non-capturing attempt must do nothing at all. */
    @Test
    fun supersededDeliveryIsInertEndToEnd() = runBlocking {
        var processorCalls = 0
        val flow = FrontCaptureFlow(StillProcessor {
            processorCalls += 1
            ProcessedStill.Accepted("p", ByteArray(1))
        })
        val controller = capturingController().apply { cancelActiveAttempt() }

        val outcome = flow.onJpegDelivered("jpeg".toByteArray(), CAPSULE_ID, persistence, controller)

        assertTrue(outcome is FrontCaptureOutcome.Superseded)
        assertEquals(0, processorCalls)
        assertEquals(0, persistence.persisted.size)
    }

    /**
     * FIX-STATE-03 regression: decode/contour/ORB run on the INJECTED CPU
     * dispatcher and sealed persistence on the IO dispatcher - never on the
     * caller (Main) thread.
     */
    @Test
    fun processingRunsOnInjectedDispatchersNotOnMain() = runBlocking {
        val cpuDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "test-cpu") }.asCoroutineDispatcher()
        val ioDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "test-io") }.asCoroutineDispatcher()
        val probe = object : SealedFingerprintPersistence by persistence {
            var persistThread: String? = null
            override suspend fun persist(
                capsuleId: String,
                side: FingerprintSide,
                origin: FingerprintOrigin,
                profileId: String,
                plaintextBytes: ByteArray,
            ): String {
                persistThread = Thread.currentThread().name
                return "fp-probe"
            }
        }
        val seenCpu = mutableListOf<String>()
        try {
            val flow = FrontCaptureFlow(
                StillProcessor {
                    seenCpu += Thread.currentThread().name
                    ProcessedStill.Accepted("mvp-orb-v1", "bytes".toByteArray())
                },
                cpuDispatcher,
                ioDispatcher,
            )
            val controller = capturingController()

            val outcome = flow.onJpegDelivered("jpeg".toByteArray(), CAPSULE_ID, probe, controller)

            assertTrue(outcome is FrontCaptureOutcome.Captured)
            assertEquals(1, seenCpu.size)
            assertTrue(
                "processor must run on the injected CPU dispatcher",
                seenCpu.single().startsWith("test-cpu"),
            )
            assertTrue(
                "persistence must run on the injected IO dispatcher",
                probe.persistThread!!.startsWith("test-io"),
            )
        } finally {
            cpuDispatcher.close()
            ioDispatcher.close()
        }
    }
}
