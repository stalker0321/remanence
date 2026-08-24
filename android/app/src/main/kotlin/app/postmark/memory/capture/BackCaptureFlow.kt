package app.postmark.memory.capture

import app.postmark.memory.create.CreateSessionFingerprintRepository
import app.postmark.memory.create.SideFingerprintExtractor
import app.postmark.memory.create.StagedSideFingerprint
import java.util.concurrent.atomic.AtomicReference
import postmark.core.data.db.FingerprintSide

/**
 * I06: the prepared-back checklist gates the back capture, and the back can
 * only be persisted after the front baseline exists (docs/product.md sender
 * flow step 6, recognition.md section 3). The flow mirrors [FrontCaptureFlow]
 * with two extra guards: checklist readiness and front-first ordering.
 */
class BackCaptureFlow(
    private val checklistGate: app.postmark.memory.capture.PreparedBackGate,
    private val processor: StillProcessor,
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
    ): FrontCaptureOutcome {
        check(readyToCapture()) { "back capture is locked until the preparation checklist completes" }
        if (jpegBytes.isEmpty()) {
            return FrontCaptureOutcome.Failed("empty still")
        }
        return when (val processed = processor.process(jpegBytes)) {
            is ProcessedStill.Rejected -> FrontCaptureOutcome.QualityRejected(processed.reasons)
            is ProcessedStill.Accepted -> {
                queued.set(StagedSideFingerprint(processed.profileId, FingerprintSide.BACK, processed.serializedBytes))
                try {
                    val id = createRepository(persistence).captureBack(capsuleId)
                    FrontCaptureOutcome.Captured(id)
                } catch (expected: Exception) {
                    queued.set(null)
                    FrontCaptureOutcome.Failed(expected.message ?: "capture failed")
                }
            }
        }
    }
}
