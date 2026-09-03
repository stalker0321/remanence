package dev.hryshyn.remanence.capture

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.recognition.QualityReason

/**
 * FIX-STATE-01/02 regression proof for the checklist-gated back capture:
 * a locked checklist is a VISIBLE Failed attempt (never a crash), front-first
 * ordering and session-local BACK staging are enforced, and every delivery
 * terminates its authoritative attempt.
 */
class BackCaptureFlowTest {

    private class FakePersistence : dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence {
        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        val persisted = mutableListOf<String>()
        var frontExists = false

        override suspend fun persist(
            capsuleId: String,
            origin: FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String {
            persisted += String(plaintextBytes)
            frontExists = true
            return "fp-${persisted.size}"
        }

        override suspend fun hasBaseline(capsuleId: String, origin: FingerprintOrigin): Boolean = frontExists

        override suspend fun setPreferredOrigin(capsuleId: String, origin: FingerprintOrigin) = Unit
        override suspend fun deleteBaseline(capsuleId: String, origin: FingerprintOrigin) = Unit
    }

    private val capsuleId = UUID.randomUUID().toString()

    /** Accepting processor as a plain lambda (StillProcessor is a fun interface). */
    private val acceptingProcessor = StillProcessor {
        ProcessedStill.Accepted("mvp-orb-v1", "orb-back".toByteArray())
    }

    private val rejectingProcessor = StillProcessor {
        ProcessedStill.Rejected(setOf(QualityReason.CROP_UNCERTAIN))
    }

    private fun capturingController(): CaptureAttemptController =
        CaptureAttemptController().apply {
            onPermissionResult(granted = true, canAskAgain = false)
            onPreviewBound()
            beginAttempt()
        }

    private fun fullyConfirmed(gate: PreparedBackGate) {
        PreparedBackItem.entries.forEach { gate.setChecked(it, true) }
    }

    @Test
    fun lockedChecklistYieldsVisibleFailedAttemptNotCrash() = runBlocking {
        val gate = PreparedBackGate()
        val backFlow = BackCaptureFlow(gate, acceptingProcessor)
        assertFalse(backFlow.readyToCapture())

        // Partial confirmation is not enough.
        gate.setChecked(PreparedBackItem.MESSAGE_WRITTEN, true)
        gate.setChecked(PreparedBackItem.ADDRESS_WRITTEN, true)

        val controller = capturingController()
        val outcome = backFlow.onJpegDelivered("jpeg".toByteArray(), capsuleId, FakePersistence(), controller)

        assertTrue(outcome is FrontCaptureOutcome.Failed)
        assertTrue(controller.phase is CaptureAttemptPhase.Failed)
        assertFalse(backFlow.readyToCapture())
        Unit
    }

    @Test
    fun confirmedChecklistStagesBackWithoutRoomPersistence() = runBlocking {
        val gate = PreparedBackGate()
        val processed = ByteArrayCaptureProcessor("orb-back")
        val backFlow = BackCaptureFlow(gate, processed)
        val persistence = FakePersistence()
        // Front-first ordering: seed the front baseline.
        persistence.persist(capsuleId, FingerprintOrigin.SENDER, "mvp-orb-v1", "orb-front".toByteArray())
        fullyConfirmed(gate)
        val controller = capturingController()

        val outcome = backFlow.onJpegDelivered("jpeg".toByteArray(), capsuleId, persistence, controller)

        assertTrue(outcome is FrontCaptureOutcome.Captured)
        val captured = outcome as FrontCaptureOutcome.Captured
        assertTrue(captured.fingerprintId.startsWith("capture-"))
        val taken = backFlow.takeStagedBack(captured.fingerprintId)
        assertEquals("orb-back", String(taken!!))
        assertTrue(processed.bytes!!.all { it == 0.toByte() })
        taken.fill(0)
        assertEquals(null, backFlow.takeStagedBack(captured.fingerprintId))
        assertEquals(
            listOf("orb-front"),
            persistence.persisted,
        )
        assertEquals(CaptureAttemptPhase.Accepted, controller.phase)
        Unit
    }

    @Test
    fun qualityRejectionReportsWithoutPersistingAndStaysRetriable() = runBlocking {
        val gate = PreparedBackGate()
        val rejecting = BackCaptureFlow(gate, rejectingProcessor)
        fullyConfirmed(gate)
        val persistence = FakePersistence()
        persistence.persist(capsuleId, FingerprintOrigin.SENDER, "mvp-orb-v1", ByteArray(1))
        val controller = capturingController()

        val outcome = rejecting.onJpegDelivered("jpeg".toByteArray(), capsuleId, persistence, controller)

        assertEquals(FrontCaptureOutcome.QualityRejected(setOf(QualityReason.CROP_UNCERTAIN)), outcome)
        assertEquals(1, persistence.persisted.size)
        assertTrue(controller.phase is CaptureAttemptPhase.Rejected)
        controller.startRetake()
        assertEquals(CaptureAttemptPhase.Binding, controller.phase)
        Unit
    }

    /** FIX-STATE-01: an unexpected capture failure still ends the attempt visibly. */
    @Test
    fun processingFailureTerminatesTheAttemptAsFailed() = runBlocking {
        val gate = PreparedBackGate()
        val backFlow = BackCaptureFlow(gate, StillProcessor { throw IllegalStateException("processor failed") })
        val persistence = FakePersistence()
        persistence.persist(capsuleId, FingerprintOrigin.SENDER, "mvp-orb-v1", ByteArray(1))
        fullyConfirmed(gate)
        val controller = capturingController()

        val outcome = backFlow.onJpegDelivered("jpeg".toByteArray(), capsuleId, persistence, controller)

        assertEquals(FrontCaptureOutcome.Failed("processor failed"), outcome)
        assertEquals(CaptureAttemptPhase.Failed(1L, "processor failed"), controller.phase)
        Unit
    }

    @Test
    fun missingFrontWipesQueuedProcessorBytesAndFailsClosed() = runBlocking {
        val gate = PreparedBackGate().also(::fullyConfirmed)
        val processed = ByteArrayCaptureProcessor("queued-back")
        val backFlow = BackCaptureFlow(gate, processed)
        val controller = capturingController()

        val outcome = backFlow.onJpegDelivered("jpeg".toByteArray(), capsuleId, FakePersistence(), controller)

        assertEquals(FrontCaptureOutcome.Failed("front must be captured before the back"), outcome)
        assertTrue(processed.bytes!!.all { it == 0.toByte() })
    }

    @Test
    fun cancellationWipesQueuedProcessorBytes() = runBlocking {
        val gate = PreparedBackGate().also(::fullyConfirmed)
        val processed = ByteArrayCaptureProcessor("cancel-back")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val persistence = BlockingBaselinePersistence(entered, release)
        val backFlow = BackCaptureFlow(gate, processed)
        val controller = capturingController()

        val job = launch {
            backFlow.onJpegDelivered("jpeg".toByteArray(), capsuleId, persistence, controller)
        }
        entered.await()
        assertTrue(processed.bytes!!.any { it != 0.toByte() })
        job.cancelAndJoin()
        assertTrue(processed.bytes!!.all { it == 0.toByte() })
    }

    private class ByteArrayCaptureProcessor(private val marker: String) : StillProcessor {
        var bytes: ByteArray? = null

        override fun process(jpegBytes: ByteArray): ProcessedStill {
            val result = marker.toByteArray()
            bytes = result
            return ProcessedStill.Accepted("mvp-orb-v1", result)
        }
    }

    private class BlockingBaselinePersistence(
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence {
        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        override suspend fun persist(
            capsuleId: String,
            origin: FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String = "unused"

        override suspend fun hasBaseline(capsuleId: String, origin: FingerprintOrigin): Boolean {
            entered.complete(Unit)
            release.await()
            return true
        }

        override suspend fun setPreferredOrigin(capsuleId: String, origin: FingerprintOrigin) = Unit
        override suspend fun deleteBaseline(capsuleId: String, origin: FingerprintOrigin) = Unit
    }
}
