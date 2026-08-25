package app.postmark.memory.capture

import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import postmark.core.data.db.FingerprintOrigin
import postmark.core.data.db.FingerprintSide
import postmark.core.recognition.QualityReason

/**
 * FIX-STATE-01/02 regression proof for the checklist-gated back capture:
 * a locked checklist is a VISIBLE Failed attempt (never a crash), ordering is
 * enforced through sealed persistence failures, and every delivery terminates
 * its authoritative attempt.
 */
class BackCaptureFlowTest {

    private class FakePersistence : postmark.core.data.fingerprints.SealedFingerprintPersistence {
        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        val persisted = mutableListOf<Pair<FingerprintSide, String>>()
        var frontExists = false
        var failBackNext = false

        override suspend fun persist(
            capsuleId: String,
            side: FingerprintSide,
            origin: FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String {
            if (failBackNext && side == FingerprintSide.BACK) {
                failBackNext = false
                throw IllegalStateException("disk full")
            }
            persisted += side to String(plaintextBytes)
            if (side == FingerprintSide.FRONT) frontExists = true
            return "fp-${persisted.size}"
        }

        override suspend fun hasBaseline(capsuleId: String, side: FingerprintSide, origin: FingerprintOrigin): Boolean =
            side == FingerprintSide.FRONT && frontExists

        override suspend fun setPreferredPair(capsuleId: String, origin: FingerprintOrigin) = Unit
        override suspend fun deleteBaseline(capsuleId: String, side: FingerprintSide, origin: FingerprintOrigin) = Unit
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
    fun confirmedChecklistFlowsBackThroughProcessorIntoPersistence() = runBlocking {
        val gate = PreparedBackGate()
        val backFlow = BackCaptureFlow(gate, acceptingProcessor)
        val persistence = FakePersistence()
        // Front-first ordering: seed the front baseline.
        persistence.persist(capsuleId, FingerprintSide.FRONT, FingerprintOrigin.SENDER, "mvp-orb-v1", "orb-front".toByteArray())
        fullyConfirmed(gate)
        val controller = capturingController()

        val outcome = backFlow.onJpegDelivered("jpeg".toByteArray(), capsuleId, persistence, controller)

        assertEquals(FrontCaptureOutcome.Captured("fp-2"), outcome)
        assertEquals(
            listOf(FingerprintSide.FRONT to "orb-front", FingerprintSide.BACK to "orb-back"),
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
        persistence.persist(capsuleId, FingerprintSide.FRONT, FingerprintOrigin.SENDER, "mvp-orb-v1", ByteArray(1))
        val controller = capturingController()

        val outcome = rejecting.onJpegDelivered("jpeg".toByteArray(), capsuleId, persistence, controller)

        assertEquals(FrontCaptureOutcome.QualityRejected(setOf(QualityReason.CROP_UNCERTAIN)), outcome)
        assertEquals(1, persistence.persisted.size)
        assertTrue(controller.phase is CaptureAttemptPhase.Rejected)
        controller.startRetake()
        assertEquals(CaptureAttemptPhase.Binding, controller.phase)
        Unit
    }

    /** FIX-STATE-01: even the persistence layer failing ends the attempt visibly. */
    @Test
    fun persistenceFailureTerminatesTheAttemptAsFailed() = runBlocking {
        val gate = PreparedBackGate()
        val backFlow = BackCaptureFlow(gate, acceptingProcessor)
        val persistence = FakePersistence()
        persistence.persist(capsuleId, FingerprintSide.FRONT, FingerprintOrigin.SENDER, "mvp-orb-v1", ByteArray(1))
        persistence.failBackNext = true
        fullyConfirmed(gate)
        val controller = capturingController()

        val outcome = backFlow.onJpegDelivered("jpeg".toByteArray(), capsuleId, persistence, controller)

        assertEquals(FrontCaptureOutcome.Failed("disk full"), outcome)
        assertEquals(CaptureAttemptPhase.Failed(1L, "disk full"), controller.phase)
        Unit
    }
}
