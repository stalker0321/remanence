package dev.hryshyn.remanence.capture

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.recognition.QualityReason
import dev.hryshyn.remanence.create.StagedSideFingerprint
import dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture

/**
 * FIX-STATE-01/03 regression proof for the front still delivery: the attempt
 * ALWAYS terminates (processor exceptions included), quality rejections
 * persist nothing but stay retriable, stale deliveries are inert, and the
 * CPU pipeline really runs off Main on the injected dispatcher.
 */
class FrontCaptureFlowTest {

    private class FakePersistence : SealedFingerprintPersistence {
        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        val persisted = mutableListOf<ByteArray>()
        var failNext = false

        override suspend fun persist(
            capsuleId: String,
            origin: FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String {
            if (failNext) {
                failNext = false
                throw IllegalStateException("disk full")
            }
            // Persistence owns its sealed copy; the flow wipes its handoff.
            persisted += plaintextBytes.copyOf()
            return "fp-${persisted.size}"
        }

        override suspend fun hasBaseline(capsuleId: String, origin: FingerprintOrigin): Boolean = persisted.isNotEmpty()

        override suspend fun setPreferredOrigin(capsuleId: String, origin: FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(capsuleId: String, origin: FingerprintOrigin) = Unit
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
        val expected = CanonicalSiftFingerprintFixture.bytes(seed = 1)
        val expectedCopy = expected.copyOf()
        var processorBytes: ByteArray? = null
        val flow = FrontCaptureFlow(
            StillProcessor {
                expected.also { processorBytes = it }
                    .let { ProcessedStill.Accepted("postcard-sift-rootsift-v1", it) }
            },
        )
        val controller = capturingController()

        val outcome = flow.onJpegDelivered("jpeg".toByteArray(), CAPSULE_ID, persistence, controller)

        assertTrue(outcome is FrontCaptureOutcome.Captured)
        assertEquals(
            1,
            persistence.persisted.size,
        )
        assertArrayEquals(expectedCopy, persistence.persisted.single())
        assertEquals(CaptureAttemptPhase.Accepted, controller.phase)
        assertTrue(processorBytes!!.all { it == 0.toByte() })
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
        var processorBytes: ByteArray? = null
        val flow = FrontCaptureFlow(
            StillProcessor {
                CanonicalSiftFingerprintFixture.bytes(seed = 2).also { processorBytes = it }
                    .let { ProcessedStill.Accepted("postcard-sift-rootsift-v1", it) }
            },
        )
        val controller = capturingController()

        val outcome = flow.onJpegDelivered("jpeg".toByteArray(), CAPSULE_ID, persistence, controller)

        assertEquals(FrontCaptureOutcome.Failed("disk full"), outcome)
        assertEquals(0, persistence.persisted.size)
        assertEquals(CaptureAttemptPhase.Failed(1L, "disk full"), controller.phase)
        assertTrue(processorBytes!!.all { it == 0.toByte() })
    }

    @Test
    fun fatalFailureAfterOfferBeforeExtractionClearsOwnerQueueAndWipesBuffers() = runBlocking {
        val handoff = CaptureHandoffQueue()
        var processorBytes: ByteArray? = null
        val flow = FrontCaptureFlow(
            StillProcessor {
                CanonicalSiftFingerprintFixture.bytes(seed = 6).also { processorBytes = it }
                    .let { ProcessedStill.Accepted("postcard-sift-rootsift-v1", it) }
            },
            Dispatchers.Unconfined,
            Dispatchers.Unconfined,
            handoff,
            { throw AssertionError("fatal failure after handoff offer") },
        )
        val controller = capturingController()
        val jpeg = "jpeg".toByteArray()

        val failure = assertThrows(AssertionError::class.java) {
            runBlocking {
                flow.onJpegDelivered(jpeg, CAPSULE_ID, persistence, controller)
            }
        }

        assertEquals("fatal failure after handoff offer", failure.message)
        assertTrue(processorBytes!!.all { it == 0.toByte() })
        assertTrue(jpeg.all { it == 0.toByte() })
        assertTrue(handoff.take(1L) == null)
    }

    @Test
    fun oldFailureCleanupCannotRemoveNewerQueuedHandoff() = runBlocking {
        lateinit var queue: CaptureHandoffQueue
        val newerBytes = CanonicalSiftFingerprintFixture.bytes(seed = 3)
        queue = CaptureHandoffQueue { owner ->
            if (owner == 1L) {
                queue.offer(
                    owner + 1,
                    StagedSideFingerprint("postcard-sift-rootsift-v1", newerBytes),
                )
            }
        }
        var oldBytes: ByteArray? = null
        val flow = FrontCaptureFlow(
            StillProcessor {
                CanonicalSiftFingerprintFixture.bytes(seed = 4).also { oldBytes = it }
                    .let { ProcessedStill.Accepted("postcard-sift-rootsift-v1", it) }
            },
            Dispatchers.Unconfined,
            Dispatchers.Unconfined,
            queue,
        )
        val controller = capturingController()

        // Invalid identity fails before extraction, leaving the old handoff
        // present while the test seam inserts the newer owner's value.
        val outcome = flow.onJpegDelivered("jpeg".toByteArray(), "not-a-uuid", persistence, controller)

        assertEquals(FrontCaptureOutcome.Failed("capsule id must be a canonical UUID string"), outcome)
        assertTrue(oldBytes!!.all { it == 0.toByte() })
        val surviving = requireNotNull(queue.take(2))
        assertTrue(surviving.serializedBytes.any { it != 0.toByte() })
        assertArrayEquals(newerBytes, surviving.serializedBytes)
        surviving.serializedBytes.fill(0)
    }

    @Test
    fun cancellationImmediatelyAfterAcceptedProcessorReturnWipesHandoff() = runBlocking {
        val parent = Job()
        var processorBytes: ByteArray? = null
        val flow = FrontCaptureFlow(StillProcessor {
            val bytes = CanonicalSiftFingerprintFixture.bytes(seed = 5).also { processorBytes = it }
            // Cancellation is requested at the Accepted return handoff, not
            // later during persistence.
            parent.cancel()
            ProcessedStill.Accepted("postcard-sift-rootsift-v1", bytes)
        })
        val controller = capturingController()
        val child = CoroutineScope(parent + Dispatchers.Default).launch {
            flow.onJpegDelivered("jpeg".toByteArray(), CAPSULE_ID, persistence, controller)
        }

        child.join()
        assertTrue(child.isCancelled)
        assertTrue(processorBytes!!.all { it == 0.toByte() })
    }

    @Test
    fun emptyStillFailsWithoutTouchingProcessor() = runBlocking {
        var processorCalls = 0
        val flow = FrontCaptureFlow(StillProcessor {
            processorCalls += 1
            ProcessedStill.Accepted(
                "postcard-sift-rootsift-v1",
                CanonicalSiftFingerprintFixture.bytes(seed = 7),
            )
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
            ProcessedStill.Accepted(
                "postcard-sift-rootsift-v1",
                CanonicalSiftFingerprintFixture.bytes(seed = 8),
            )
        })
        val controller = capturingController().apply { cancelActiveAttempt() }
        val jpeg = "jpeg".toByteArray()

        val outcome = flow.onJpegDelivered(jpeg, CAPSULE_ID, persistence, controller)

        assertTrue(outcome is FrontCaptureOutcome.Superseded)
        assertEquals(0, processorCalls)
        assertEquals(0, persistence.persisted.size)
        assertTrue(jpeg.all { it == 0.toByte() })
    }

    /**
     * FIX-STATE-03 regression: decode/contour/SIFT run on the INJECTED CPU
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
                    ProcessedStill.Accepted(
                        "postcard-sift-rootsift-v1",
                        CanonicalSiftFingerprintFixture.bytes(seed = 6),
                    )
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
