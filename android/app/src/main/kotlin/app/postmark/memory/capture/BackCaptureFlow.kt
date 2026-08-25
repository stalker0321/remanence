package app.postmark.memory.capture

import app.postmark.memory.create.CreateSessionFingerprintRepository
import app.postmark.memory.create.SideFingerprintExtractor
import app.postmark.memory.create.StagedSideFingerprint
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import postmark.core.data.db.FingerprintSide

/**
 * I06: the prepared-back checklist gates the back capture, and the back can
 * only be persisted after the front baseline exists (docs/product.md sender
 * flow step 6, recognition.md section 3). The flow mirrors [FrontCaptureFlow]
 * with two extra guards: checklist readiness and front-first ordering.
 *
 * FIX-STATE-01: a begun attempt ALWAYS terminates; FIX-STATE-03: CPU work on
 * [cpuDispatcher], sealed persistence on [ioDispatcher]. A locked checklist
 * is reported as Failed instead of crashing.
 */
class BackCaptureFlow(
    private val checklistGate: PreparedBackGate,
    private val processor: StillProcessor,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val queued = AtomicReference<StagedSideFingerprint?>()

    private val extractor = SideFingerprintExtractor { side ->
        val staged = queued.getAndSet(null)
            ?: throw IllegalStateException("no processed still queued for $side")
        if (staged.side != side) throw IllegalStateException("queued side ${staged.side} does not match $side")
        staged
    }

    fun createRepository(persistence: postmark.core.data.fingerprints.SealedFingerprintPersistence) =
        CreateSessionFingerprintRepository(persistence, extractor)

    /** True when every checklist item is explicitly confirmed. */
    fun readyToCapture(): Boolean = checklistGate.ready

    suspend fun onJpegDelivered(
        jpegBytes: ByteArray,
        capsuleId: String,
        persistence: postmark.core.data.fingerprints.SealedFingerprintPersistence,
        attempt: CaptureAttemptController,
    ): FrontCaptureOutcome {
        if (!attempt.markProcessing()) return FrontCaptureOutcome.Superseded
        try {
            if (!readyToCapture()) {
                val message = "back capture is locked until the preparation checklist completes"
                attempt.fail(message)
                return FrontCaptureOutcome.Failed(message)
            }
            if (jpegBytes.isEmpty()) {
                attempt.fail("empty still")
                return FrontCaptureOutcome.Failed("empty still")
            }
            return when (
                val processed = withContext(cpuDispatcher) { processor.process(jpegBytes) }
            ) {
                is ProcessedStill.Rejected -> {
                    attempt.reject(processed.reasons)
                    FrontCaptureOutcome.QualityRejected(processed.reasons)
                }
                is ProcessedStill.Accepted -> {
                    queued.set(
                        StagedSideFingerprint(processed.profileId, FingerprintSide.BACK, processed.serializedBytes),
                    )
                    val id = withContext(ioDispatcher) {
                        createRepository(persistence).captureBack(capsuleId)
                    }
                    attempt.accept()
                    FrontCaptureOutcome.Captured(id)
                }
            }
        } catch (cancelled: CancellationException) {
            queued.set(null)
            attempt.cancelActiveAttempt()
            throw cancelled
        } catch (failure: Exception) {
            queued.set(null)
            val message = failure.message ?: "capture failed"
            attempt.fail(message)
            return FrontCaptureOutcome.Failed(message)
        }
    }
}
