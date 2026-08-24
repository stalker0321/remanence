package app.postmark.memory.capture

import app.postmark.memory.create.CreateSessionFingerprintRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import postmark.core.data.db.FingerprintOrigin
import postmark.core.data.db.FingerprintSide
import postmark.core.data.db.PostmarkLocalDatabase
import postmark.core.data.fingerprints.SealedFingerprintPersistence
import postmark.core.recognition.QualityReason

/** Camera-to-encrypted-fingerprint wiring proof for I05. */
class FrontCaptureFlowTest {

    private class FakePersistence : SealedFingerprintPersistence {
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

    private fun flowWith(processor: StillProcessor): Pair<FrontCaptureFlow, SingleStillCaptureShell> {
        val shell = SingleStillCaptureShell()
        shell.onPermissionResult(granted = true, canAskAgain = false)
        shell.onPreviewBound()
        shell.onCaptureStarted()
        val flow = FrontCaptureFlow(shell, processor)
        return flow to shell
    }

    @Test
    fun deliveredStillFlowsThroughToSealedPersistence() = runBlockingTest {
        val (flow, shell) = flowWith {
            ProcessedStill.Accepted("mvp-orb-v1", "serialized-orb".toByteArray())
        }
        val outcome = flow.onJpegDelivered("jpeg".toByteArray(), CAPSULE_ID, persistence)

        assertTrue(outcome is FrontCaptureOutcome.Captured)
        assertEquals(
            listOf(FingerprintSide.FRONT to "serialized-orb"),
            persistence.persisted.map { it.first to String(it.second) },
        )
        assertEquals(StillCapturePhase.Delivered, shell.phase)
    }

    @Test
    fun qualityRejectionPersistsNothingAndReportsReasons() = runBlockingTest {
        val reasons = setOf(QualityReason.TOO_BLURRY, QualityReason.GLARE_EXCESSIVE)
        val (flow, shell) = flowWith { ProcessedStill.Rejected(reasons) }

        val outcome = flow.onJpegDelivered("jpeg".toByteArray(), CAPSULE_ID, persistence)

        assertEquals(FrontCaptureOutcome.QualityRejected(reasons), outcome)
        assertEquals(0, persistence.persisted.size)
    }

    @Test
    fun duplicateFrontPersistenceFailureSurfacesAsFailedOutcome() = runBlockingTest {
        persistence.failNext = true
        val (flow, _) = flowWith {
            ProcessedStill.Accepted("mvp-orb-v1", "bytes".toByteArray())
        }

        val outcome = flow.onJpegDelivered("jpeg".toByteArray(), CAPSULE_ID, persistence)

        assertEquals(FrontCaptureOutcome.Failed("disk full"), outcome)
        assertEquals(0, persistence.persisted.size)
    }

    @Test
    fun emptyStillFailsWithoutTouchingProcessor() = runBlockingTest {
        var processorCalls = 0
        val (flow, shell) = flowWith {
            processorCalls += 1
            ProcessedStill.Accepted("p", ByteArray(1))
        }

        val outcome = flow.onJpegDelivered(ByteArray(0), CAPSULE_ID, persistence)

        assertEquals(FrontCaptureOutcome.Failed("empty still"), outcome)
        assertEquals(0, processorCalls)
        assertEquals(0, persistence.persisted.size)
        assertTrue(shell.phase is StillCapturePhase.Failed)
    }

    private companion object {
        fun runBlockingTest(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
    }
}
