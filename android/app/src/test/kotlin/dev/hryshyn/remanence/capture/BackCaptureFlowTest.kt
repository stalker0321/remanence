package dev.hryshyn.remanence.capture

import java.util.UUID
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
        val backFlow = BackCaptureFlow(gate, acceptingProcessor)
        val persistence = FakePersistence()
        // Front-first ordering: seed the front baseline.
        persistence.persist(capsuleId, FingerprintOrigin.SENDER, "mvp-orb-v1", "orb-front".toByteArray())
        fullyConfirmed(gate)
        val controller = capturingController()

        val outcome = backFlow.onJpegDelivered("jpeg".toByteArray(), capsuleId, persistence, controller)

        assertTrue(outcome is FrontCaptureOutcome.Captured)
        val captured = outcome as FrontCaptureOutcome.Captured
        assertTrue(captured.fingerprintId.startsWith("capture-"))
        assertEquals("orb-back", String(backFlow.readStagedBack(captured.fingerprintId)!!))
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
}
