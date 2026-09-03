package dev.hryshyn.remanence.capture

import dev.hryshyn.remanence.create.CreateSessionFingerprintRepository
import dev.hryshyn.remanence.create.CreateCaptureSessionStore
import dev.hryshyn.remanence.create.CaptureFingerprintSide
import dev.hryshyn.remanence.create.SideFingerprintExtractor
import dev.hryshyn.remanence.create.StagedSideFingerprint
import java.util.concurrent.atomic.AtomicReference
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
    private val queued = AtomicReference<StagedSideFingerprint?>()

    /** Drop the queued handoff and wipe the processor-owned bytes. */
    private fun clearQueuedMaterial(expected: StagedSideFingerprint? = null) {
        if (expected == null) {
            queued.getAndSet(null)?.serializedBytes?.fill(0)
        } else if (queued.compareAndSet(expected, null)) {
            expected.serializedBytes.fill(0)
        }
    }

    private val extractor = SideFingerprintExtractor { side ->
        val staged = queued.getAndSet(null)
            ?: throw IllegalStateException("no processed still queued for $side")
        if (staged.side != side) {
            staged.serializedBytes.fill(0)
            throw IllegalStateException("queued side ${staged.side} does not match $side")
        }
        staged
    }

    fun createRepository(persistence: dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence) =
        CreateSessionFingerprintRepository(persistence, extractor, captureStore)

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
        if (!attempt.markProcessing()) {
            clearQueuedMaterial()
            jpegBytes.fill(0)
            return FrontCaptureOutcome.Superseded
        }
        var handoff: StagedSideFingerprint? = null
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
                    attempt.reject(processed.reasons, processed.diagnostic)
                    FrontCaptureOutcome.QualityRejected(processed.reasons)
                }
                is ProcessedStill.Accepted -> {
                    val staged = StagedSideFingerprint(
                        processed.profileId,
                        CaptureFingerprintSide.BACK,
                        processed.serializedBytes,
                    )
                    handoff = staged
                    val previous = queued.getAndSet(staged)
                    previous?.serializedBytes?.fill(0)
                    val id = withContext(ioDispatcher) {
                        createRepository(persistence).captureBack(capsuleId)
                    }
                    attempt.accept()
                    FrontCaptureOutcome.Captured(id)
                }
            }
        } catch (cancelled: CancellationException) {
            clearQueuedMaterial(handoff)
            attempt.cancelActiveAttempt()
            throw cancelled
        } catch (failure: Exception) {
            clearQueuedMaterial(handoff)
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
