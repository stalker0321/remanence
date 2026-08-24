package app.postmark.memory.capture

import app.postmark.memory.create.CreateSessionFingerprintRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import postmark.core.data.db.FingerprintOrigin
import postmark.core.data.db.FingerprintSide
import postmark.core.data.fingerprints.SealedFingerprintPersistence
import postmark.core.recognition.QualityReason

/** Checklist-gated ordered back capture proof for I06. */
class BackCaptureFlowTest {

    private class FakePersistence : SealedFingerprintPersistence {
        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

        val persisted = mutableListOf<Pair<FingerprintSide, String>>()
        var frontExists = false

        override suspend fun persist(
            capsuleId: String,
            side: FingerprintSide,
            origin: FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String {
            if (side == FingerprintSide.BACK && !frontExists) {
                throw IllegalStateException("front must be captured before the back")
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

    private fun flow(): Pair<BackCaptureFlow, PreparedBackGate> {
        val gate = PreparedBackGate()
        return BackCaptureFlow(gate) { ProcessedStill.Accepted("mvp-orb-v1", "orb-back".toByteArray()) } to gate
    }

    @Test
    fun backIsLockedUntilEveryChecklistItemIsConfirmed() = runBlocking {
        val (flow, gate) = flow()
        assertFalse(flow.readyToCapture())

        // Partial confirmation is not enough.
        gate.setChecked(PreparedBackItem.MESSAGE_WRITTEN, true)
        gate.setChecked(PreparedBackItem.ADDRESS_WRITTEN, true)
        assertFalse(flow.readyToCapture())
        assertThrows(IllegalStateException::class.java) {
            runBlocking { flow.onJpegDelivered("jpeg".toByteArray(), capsuleId, FakePersistence()) }
        }

        PreparedBackItem.entries.forEach { gate.setChecked(it, true) }
        assertTrue(flow.readyToCapture())
    }

    @Test
    fun confirmedChecklistFlowsBackThroughProcessorIntoPersistence() = runBlocking {
        val (flow, gate) = flow()
        val persistence = FakePersistence()
        // Front-first ordering: seed the front baseline.
        persistence.persist(capsuleId, FingerprintSide.FRONT, FingerprintOrigin.SENDER, "mvp-orb-v1", "orb-front".toByteArray())
        PreparedBackItem.entries.forEach { gate.setChecked(it, true) }

        val outcome = flow.onJpegDelivered("jpeg".toByteArray(), capsuleId, persistence)

        assertEquals(FrontCaptureOutcome.Captured("fp-2"), outcome)
        assertEquals(listOf(FingerprintSide.FRONT to "orb-front", FingerprintSide.BACK to "orb-back"), persistence.persisted)
    }

    @Test
    fun qualityRejectionStillReportsWithoutPersisting() = runBlocking {
        val (flow, gate) = flow()
        val rejecting = BackCaptureFlow(gate) {
            ProcessedStill.Rejected(setOf(QualityReason.CROP_UNCERTAIN))
        }
        PreparedBackItem.entries.forEach { gate.setChecked(it, true) }
        val persistence = FakePersistence()
        persistence.persist(capsuleId, FingerprintSide.FRONT, FingerprintOrigin.SENDER, "mvp-orb-v1", ByteArray(1))

        val outcome = rejecting.onJpegDelivered("jpeg".toByteArray(), capsuleId, persistence)

        assertEquals(FrontCaptureOutcome.QualityRejected(setOf(QualityReason.CROP_UNCERTAIN)), outcome)
        assertEquals(1, persistence.persisted.size)
    }

    private companion object {
        fun runBlocking(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
    }
}
