package dev.hryshyn.remanence.capture

import dev.hryshyn.remanence.create.CreateSessionFingerprintRepository
import dev.hryshyn.remanence.create.CreateCaptureSessionStore
import dev.hryshyn.remanence.create.CaptureFingerprintSide
import dev.hryshyn.remanence.create.SideFingerprintExtractor
import dev.hryshyn.remanence.create.StagedSideFingerprint
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * I06: the prepared-back checklist gates the back capture, and the back can
 * only be staged after the front baseline exists (docs/product.md sender
 * flow step 6, recognition.md section 3). The flow mirrors [FrontCaptureFlow]
 * with two extra guards: checklist readiness and front-first ordering.
 *
 * FIX-STATE-01: a begun attempt ALWAYS terminates; FIX-STATE-03: CPU work on
 * [cpuDispatcher], capture-session handoff on [ioDispatcher]. A locked
 * checklist is reported as Failed instead of crashing.
 */
class BackCaptureFlow(
    private val checklistGate: PreparedBackGate,
    private val processor: StillProcessor,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val captureStore: CreateCaptureSessionStore = CreateCaptureSessionStore(),
) {

    /** One-shot processor handoff; the flow/repository own and wipe its bytes. */
    private var handoffQueue = CaptureHandoffQueue()
    private val ownerCounter = AtomicLong()

    /** Internal-only queue injection keeps race tests deterministic. */
    internal constructor(
        checklistGate: PreparedBackGate,
        processor: StillProcessor,
        cpuDispatcher: CoroutineDispatcher,
        ioDispatcher: CoroutineDispatcher,
        captureStore: CreateCaptureSessionStore,
        handoffQueue: CaptureHandoffQueue,
    ) : this(checklistGate, processor, cpuDispatcher, ioDispatcher, captureStore) {
        this.handoffQueue = handoffQueue
    }

    private fun createRepository(
        persistence: dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence,
        owner: Long,
    ) = CreateSessionFingerprintRepository(persistence, SideFingerprintExtractor { side ->
        val staged = handoffQueue.take(owner)
            ?: throw IllegalStateException("no processed still queued for $side")
        if (staged.side != side) {
            staged.serializedBytes.fill(0)
            throw IllegalStateException("queued side ${staged.side} does not match $side")
        }
        staged
    }, captureStore)

    /**
     * Consumes deferred BACK material, transferring a private copy to the
     * caller. The caller owns and must wipe that returned copy.
     */
    fun takeStagedBack(fingerprintId: String): ByteArray? = captureStore.take(fingerprintId)

    /** Clears any deferred BACK material when the Create session is reset/closed. */
    fun clearStagedMaterial() = captureStore.clear()

    /** True when every checklist item is explicitly confirmed. */
    fun readyToCapture(): Boolean = checklistGate.ready

    suspend fun onJpegDelivered(
        jpegBytes: ByteArray,
        capsuleId: String,
        persistence: dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence,
        attempt: CaptureAttemptController,
    ): FrontCaptureOutcome {
        val owner = ownerCounter.incrementAndGet()
        if (!attempt.markProcessing()) {
            handoffQueue.clear(owner)
            jpegBytes.fill(0)
            return FrontCaptureOutcome.Superseded
        }
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
            val processed = withContext(cpuDispatcher) {
                // Capture Accepted ownership before this dispatcher boundary
                // can resume/cancel and lose the returned byte array.
                val result = processor.process(jpegBytes)
                if (result is ProcessedStill.Accepted) {
                    val staged = StagedSideFingerprint(
                        result.profileId,
                        CaptureFingerprintSide.BACK,
                        result.serializedBytes,
                    )
                    check(handoffQueue.offer(owner, staged)) { "capture superseded" }
                }
                result
            }
            return when (processed) {
                is ProcessedStill.Rejected -> {
                    attempt.reject(processed.reasons, processed.diagnostic)
                    FrontCaptureOutcome.QualityRejected(processed.reasons)
                }
                is ProcessedStill.Accepted -> {
                    val id = withContext(ioDispatcher) {
                        createRepository(persistence, owner).captureBack(capsuleId)
                    }
                    attempt.accept()
                    FrontCaptureOutcome.Captured(id)
                }
            }
        } catch (cancelled: CancellationException) {
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
            // survives the processor/capture-session handoff.
            jpegBytes.fill(0)
        }
    }
}
