package dev.hryshyn.remanence.capture

import dev.hryshyn.remanence.create.CreateSessionFingerprintRepository
import dev.hryshyn.remanence.create.SideFingerprintExtractor
import dev.hryshyn.remanence.create.StagedSideFingerprint
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.hryshyn.remanence.core.data.db.FingerprintSide
import dev.hryshyn.remanence.core.recognition.QualityReason
import dev.hryshyn.remanence.core.recognition.FingerprintSide as RecognitionFingerprintSide
import java.util.Locale

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

    data class Rejected(
        val reasons: Set<QualityReason>,
        val diagnostic: CaptureDiagnostic? = null,
    ) : ProcessedStill
}

/**
 * Redacted, transient capture diagnostics. This is deliberately not part of
 * the fingerprint payload or any persistence contract; it exists only to
 * explain a DEBUG build's latest local rejection.
 */
data class CaptureDiagnostic(
    val side: RecognitionFingerprintSide,
    val stage: CaptureDiagnosticStage,
    val laplacianThreshold: Double? = null,
    val laplacianVariance: Double? = null,
    val nearBlackFraction: Double? = null,
    val clippedWhiteFraction: Double? = null,
    val largestGlareFraction: Double? = null,
    val usedGuideFallback: Boolean? = null,
    val warpedWidth: Int? = null,
    val warpedHeight: Int? = null,
    val orbKeypoints: Int? = null,
    val orbDescriptors: Int? = null,
) {
    /** Stable, redacted one-line form used by the DEBUG rejection panel. */
    fun summary(): String = buildString {
        append("DEBUG capture: side=").append(side.name)
        append(" stage=").append(stage.name)
        append(" laplacian=").append(decimal(laplacianVariance))
        append(" threshold=").append(decimal(laplacianThreshold))
        append(" darkness=").append(decimal(nearBlackFraction))
        append(" clippedWhite=").append(decimal(clippedWhiteFraction))
        append(" glare=").append(decimal(largestGlareFraction))
        append(" cropFallback=").append(usedGuideFallback?.toString() ?: "n/a")
        append(" warp=").append(
            if (warpedWidth != null && warpedHeight != null) {
                "${warpedWidth}x$warpedHeight"
            } else {
                "n/a"
            },
        )
        append(" orb=").append(orbKeypoints?.toString() ?: "n/a")
        append(" descriptors=").append(orbDescriptors?.toString() ?: "n/a")
    }

    private fun decimal(value: Double?): String =
        value?.let { String.format(Locale.US, "%.4f", it) } ?: "n/a"
}

enum class CaptureDiagnosticStage {
    DECODE,
    CROP,
    WARP,
    QUALITY,
    ORB,
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

    fun createRepository(persistence: dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence) =
        CreateSessionFingerprintRepository(persistence, extractor)

    suspend fun onJpegDelivered(
        jpegBytes: ByteArray,
        capsuleId: String,
        persistence: dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence,
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
                    attempt.reject(processed.reasons, processed.diagnostic)
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
