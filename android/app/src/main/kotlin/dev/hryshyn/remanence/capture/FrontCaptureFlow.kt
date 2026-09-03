package dev.hryshyn.remanence.capture

import dev.hryshyn.remanence.create.CreateSessionFingerprintRepository
import dev.hryshyn.remanence.create.SideFingerprintExtractor
import dev.hryshyn.remanence.create.StagedSideFingerprint
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.hryshyn.remanence.core.recognition.QualityReason
import dev.hryshyn.remanence.core.recognition.FingerprintSide as RecognitionFingerprintSide
import java.util.Locale

/** Port over the OpenCV-backed still pipeline: decode→crop→warp→quality→ORB. */
fun interface StillProcessor {
    /**
     * Returns the extracted fingerprint bytes, or the set of quality reasons
     * that reject this still before any extraction result is trusted.
     * The caller transfers the temporary JPEG to the flow; it is wiped after
     * processing, including rejection and exception paths.
     */
    fun process(jpegBytes: ByteArray): ProcessedStill
}

sealed interface ProcessedStill {
    /**
     * The flow takes ownership of [serializedBytes] for the remainder of the
     * delivery and wipes it after persistence or any failure.
     */
    data class Accepted(val profileId: String, val serializedBytes: ByteArray) : ProcessedStill

    data class Rejected(
        val reasons: Set<QualityReason>,
        val diagnostic: CaptureDiagnostic? = null,
    ) : ProcessedStill
}

/**
 * One-slot capture handoff keyed by the monotonically increasing attempt
 * owner. A newer owner may replace an older value, but an older cleanup can
 * never remove a newer value. The optional hook is internal-test-only and is
 * never authoritative for zeroization.
 */
internal class CaptureHandoffQueue(
    private val beforeClear: ((Long) -> Unit)? = null,
) {
    private data class Entry(val owner: Long, val staged: StagedSideFingerprint)

    private val current = AtomicReference<Entry?>()

    /** Offers one owner-scoped value; rejected incoming bytes are wiped. */
    fun offer(owner: Long, staged: StagedSideFingerprint): Boolean {
        while (true) {
            val previous = current.get()
            if (previous != null && previous.owner > owner) {
                staged.serializedBytes.fill(0)
                return false
            }
            if (current.compareAndSet(previous, Entry(owner, staged))) {
                previous?.staged?.serializedBytes?.fill(0)
                return true
            }
        }
    }

    /** Takes only the exact owner; another owner's value is untouched. */
    fun take(owner: Long): StagedSideFingerprint? {
        val snapshot = current.get() ?: return null
        if (snapshot.owner != owner || !current.compareAndSet(snapshot, null)) return null
        return snapshot.staged
    }

    /**
     * Clears only [owner]. The hook runs between snapshot and CAS so tests can
     * insert a newer owner; a thrown hook cannot prevent the authoritative
     * compare-and-set and wipe.
     */
    fun clear(owner: Long) {
        val snapshot = current.get() ?: return
        if (snapshot.owner != owner) return
        runCatching { beforeClear?.invoke(owner) }
        val latest = current.get() ?: return
        if (latest.owner != owner) return
        if (current.compareAndSet(latest, null)) latest.staged.serializedBytes.fill(0)
    }
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

    /** One-shot processor handoff; the flow/repository own and wipe its bytes. */
    private var handoffQueue = CaptureHandoffQueue()
    private val ownerCounter = AtomicLong()

    /** Internal-only queue injection keeps race tests deterministic. */
    internal constructor(
        processor: StillProcessor,
        cpuDispatcher: CoroutineDispatcher,
        ioDispatcher: CoroutineDispatcher,
        handoffQueue: CaptureHandoffQueue,
    ) : this(processor, cpuDispatcher, ioDispatcher) {
        this.handoffQueue = handoffQueue
    }

    private fun createRepository(
        persistence: dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence,
        owner: Long,
    ) = CreateSessionFingerprintRepository(persistence, SideFingerprintExtractor {
        handoffQueue.take(owner)
            ?: throw IllegalStateException("no processed still queued")
    })

    suspend fun onJpegDelivered(
        jpegBytes: ByteArray,
        capsuleId: String,
        persistence: dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence,
        attempt: CaptureAttemptController,
    ): FrontCaptureOutcome {
        val owner = ownerCounter.incrementAndGet()
        // A stale or non-capturing delivery is structurally inert.
        if (!attempt.markProcessing()) {
            handoffQueue.clear(owner)
            jpegBytes.fill(0)
            return FrontCaptureOutcome.Superseded
        }
        try {
            if (jpegBytes.isEmpty()) {
                attempt.fail("empty still")
                return FrontCaptureOutcome.Failed("empty still")
            }
            val processed = withContext(cpuDispatcher) {
                // Capture the accepted result into the owner-scoped queue
                // before this dispatcher boundary can resume/cancel.
                val result = processor.process(jpegBytes)
                if (result is ProcessedStill.Accepted) {
                    val staged = StagedSideFingerprint(
                        result.profileId,
                        result.serializedBytes,
                    )
                    check(handoffQueue.offer(owner, staged)) { "capture superseded" }
                }
                result
            }
            return when (processed) {
                is ProcessedStill.Rejected -> {
                    // Quality failure: no persistence, controller shows Rejected.
                    attempt.reject(processed.reasons, processed.diagnostic)
                    FrontCaptureOutcome.QualityRejected(processed.reasons)
                }
                is ProcessedStill.Accepted -> {
                    val id = withContext(ioDispatcher) {
                        createRepository(persistence, owner).captureFront(capsuleId)
                    }
                    attempt.accept()
                    FrontCaptureOutcome.Captured(id)
                }
            }
        } catch (cancelled: CancellationException) {
            // Clean lifecycle completion: no terminal result is published for
            // this attempt, and nothing stays queued.
            handoffQueue.clear(owner)
            attempt.cancelActiveAttempt()
            throw cancelled
        } catch (failure: Exception) {
            handoffQueue.clear(owner)
            val message = failure.message ?: "capture failed"
            attempt.fail(message)
            return FrontCaptureOutcome.Failed(message)
        } finally {
            // Delivery transfers the temporary JPEG to this flow; it never
            // survives the processor/persistence handoff.
            jpegBytes.fill(0)
        }
    }
}
