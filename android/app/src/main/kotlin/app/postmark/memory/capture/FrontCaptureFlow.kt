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

/**
 * I05 outcome of one camera still flowing through to encrypted persistence.
 * FIX-STATE-01: [Superseded] marks a delivery that arrived for an attempt
 * that is no longer current (cancelled/reset/disposed); it changes nothing.
 */
sealed interface FrontCaptureOutcome {
    data class Captured(val fingerprintId: String) : FrontCaptureOutcome

    data class QualityRejected(val reasons: Set<QualityReason>) : FrontCaptureOutcome

    data class Failed(val message: String) : FrontCaptureOutcome

    data object Superseded : FrontCaptureOutcome
}

/**
 * I05 integration: runs the bounded normalize-crop-quality-ORB pipeline and
 * the sealed fingerprint repository for one delivered still, publishing every
 * transition through THE authoritative [CaptureAttemptController].
 *
 * FIX-STATE-01: a begun attempt ALWAYS terminates - Accepted, Rejected, or
 * Failed - even when the processor or persistence throws; cancellation ends
 * the lifecycle cleanly without publishing any result. FIX-STATE-03: JPEG/
 * OpenCV decode and ORB extraction run on [cpuDispatcher] (never Main);
 * sealed file/database persistence runs on [ioDispatcher]; raw plaintext
 * bytes live only inside this call.
 */
class FrontCaptureFlow(
    private val processor: StillProcessor,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
        attempt: CaptureAttemptController,
    ): FrontCaptureOutcome {
        // A stale or non-capturing delivery is structurally inert.
        if (!attempt.markProcessing()) return FrontCaptureOutcome.Superseded
        try {
            if (jpegBytes.isEmpty()) {
                attempt.fail("empty still")
                return FrontCaptureOutcome.Failed("empty still")
            }
            return when (
                val processed = withContext(cpuDispatcher) { processor.process(jpegBytes) }
            ) {
                is ProcessedStill.Rejected -> {
                    // Quality failure: no persistence, controller shows Rejected.
                    attempt.reject(processed.reasons)
                    FrontCaptureOutcome.QualityRejected(processed.reasons)
                }
                is ProcessedStill.Accepted -> {
                    queued.set(
                        StagedSideFingerprint(processed.profileId, FingerprintSide.FRONT, processed.serializedBytes),
                    )
                    val id = withContext(ioDispatcher) {
                        createRepository(persistence).captureFront(capsuleId)
                    }
                    attempt.accept()
                    FrontCaptureOutcome.Captured(id)
                }
            }
        } catch (cancelled: CancellationException) {
            // Clean lifecycle completion: no terminal result is published for
            // this attempt, and nothing stays queued.
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
