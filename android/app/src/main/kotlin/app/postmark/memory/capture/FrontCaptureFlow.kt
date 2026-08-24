package app.postmark.memory.capture

import app.postmark.memory.create.CreateSessionFingerprintRepository
import app.postmark.memory.create.SideFingerprintExtractor
import app.postmark.memory.create.StagedSideFingerprint
import java.util.concurrent.atomic.AtomicReference
import postmark.core.data.db.FingerprintSide
import postmark.core.recognition.QualityReason

/** Port over the OpenCV-backed still pipeline: decode→crop→warp→quality→ORB. */
fun interface StillProcessor {
    /**
     * Returns the extracted fingerprint bytes, or the set of quality reasons
     * that reject this still before any extraction result is trusted.
     */
    fun process(jpegBytes: ByteArray): ProcessedStill
}

sealed interface ProcessedStill {
    data class Accepted(val profileId: String, val serializedBytes: ByteArray) : ProcessedStill

    data class Rejected(val reasons: Set<QualityReason>) : ProcessedStill
}

/** I05 outcome of one camera still flowing through to encrypted persistence. */
sealed interface FrontCaptureOutcome {
    data class Captured(val fingerprintId: String) : FrontCaptureOutcome

    data class QualityRejected(val reasons: Set<QualityReason>) : FrontCaptureOutcome

    data class Failed(val message: String) : FrontCaptureOutcome
}

/**
 * I05 integration: binds the CameraX capture shell to the bounded
 * normalize-crop-quality-ORB pipeline and the sealed fingerprint repository.
 * A delivered still is processed once; quality rejections persist nothing and
 * leave the session untouched so the user can recapture; persistence failures
 * surface as [FrontCaptureOutcome.Failed] without partial state. Exactly one
 * processed still is queued at a time and handed to the repository through its
 * extractor port, keeping the OpenCV pipeline out of persistence code.
 */
class FrontCaptureFlow(
    val shell: SingleStillCaptureShell,
    private val processor: StillProcessor,
) {

    private val queued = AtomicReference<StagedSideFingerprint?>()

    /** Extractor view handed to the repository; serves the queued still once. */
    private val extractor = SideFingerprintExtractor { side ->
        val staged = queued.getAndSet(null)
            ?: throw IllegalStateException("no processed still queued for $side")
        if (staged.side != side) throw IllegalStateException("queued side ${staged.side} does not match $side")
        staged
    }

    fun createRepository(persistence: postmark.core.data.fingerprints.SealedFingerprintPersistence) =
        CreateSessionFingerprintRepository(persistence, extractor)

    suspend fun onJpegDelivered(
        jpegBytes: ByteArray,
        capsuleId: String,
        persistence: postmark.core.data.fingerprints.SealedFingerprintPersistence,
    ): FrontCaptureOutcome {
        if (jpegBytes.isEmpty()) {
            shell.onCaptureFailed("empty still")
            return FrontCaptureOutcome.Failed("empty still")
        }
        return when (val processed = processor.process(jpegBytes)) {
            is ProcessedStill.Rejected -> {
                // Quality failure: no persistence, shell stays consumable.
                shell.onStillDelivered()
                FrontCaptureOutcome.QualityRejected(processed.reasons)
            }
            is ProcessedStill.Accepted -> {
                shell.onStillDelivered()
                queued.set(StagedSideFingerprint(processed.profileId, FingerprintSide.FRONT, processed.serializedBytes))
                try {
                    val id = createRepository(persistence).captureFront(capsuleId)
                    FrontCaptureOutcome.Captured(id)
                } catch (expected: Exception) {
                    queued.set(null)
                    FrontCaptureOutcome.Failed(expected.message ?: "capture failed")
                }
            }
        }
    }
}
