package dev.hryshyn.remanence.capture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.hryshyn.remanence.core.recognition.QualityReason

/**
 * Pure permission gate for the shared one-still capture component
 * (docs/implementation-plan.md M1-R13). Free of Android types so every
 * transition is unit-testable; the Compose layer only renders it.
 */
sealed interface CapturePermissionStep {
    /** No request launched yet; UI explains why the camera is needed. */
    data object NotRequested : CapturePermissionStep

    /** System dialog declined once; an explicit user action may retry it. */
    data object DeniedRetryable : CapturePermissionStep

    /** Declined permanently (do-not-ask-again/policy); only Settings can recover. */
    data object PermanentlyDenied : CapturePermissionStep

    /** Capture may bind a preview and expose exactly one still-capture action. */
    data object Granted : CapturePermissionStep
}

/**
 * FIX-REVIEW-05: THE one classifier of a system permission request outcome,
 * shared by every capture surface (Create, Scan, shared still component).
 * [shouldShowRationale] must be read via
 * ActivityCompat.shouldShowRequestPermissionRationale AFTER the denial
 * arrives: true means the system will show the ask-again dialog again
 * (ordinary decline); false means do-not-ask-again/policy block - only
 * Settings can recover. A bare denial is never assumed retryable forever.
 */
fun resolveCapturePermissionStep(
    granted: Boolean,
    shouldShowRationale: Boolean,
): CapturePermissionStep = when {
    granted -> CapturePermissionStep.Granted
    shouldShowRationale -> CapturePermissionStep.DeniedRetryable
    else -> CapturePermissionStep.PermanentlyDenied
}

/**
 * FIX-STATE-01: THE authoritative presentation contract for one deliberate
 * capture attempt. The camera surface, the ViewModel step, and quality
 * rejections all render from THIS single state; there is no second
 * unsynchronized capture state machine anywhere.
 *
 * Every begun attempt is guaranteed to reach a terminal phase - [Rejected],
 * [Failed], or [Accepted] - even when the processor or persistence throws.
 * Terminal payloads carry the monotonic [CaptureAttemptController.attemptId]
 * so two consecutive identical rejections (for example TOO_BLURRY twice) are
 * always distinct observable events and each yields a fresh working retry.
 */
sealed interface CaptureAttemptPhase {

    /** Camera permission granted; the preview use case is not bound yet. */
    data object Binding : CaptureAttemptPhase

    /** Preview live; the one-shot shutter action may begin an attempt. */
    data object Ready : CaptureAttemptPhase

    /** takePicture in flight; the shutter must stay disabled. */
    data object Capturing : CaptureAttemptPhase

    /** Bytes delivered; the CPU pipeline is running. */
    data object Processing : CaptureAttemptPhase

    /** Quality gate rejected this attempt; nothing was persisted. */
    data class Rejected(
        val attemptId: Long,
        val reasons: Set<QualityReason>,
    ) : CaptureAttemptPhase

    /** Attempt failed before producing anything usable; message is display-safe. */
    data class Failed(
        val attemptId: Long,
        val message: String,
    ) : CaptureAttemptPhase

    /** Attempt completed successfully. */
    data object Accepted : CaptureAttemptPhase

    val isTerminal: Boolean
        get() = this is Rejected || this is Failed || this == Accepted
}

/**
 * FIX-STATE-01: THE authoritative per-side capture controller replacing the
 * legacy shell/step/rejection trio for capture surfaces. Compose-observable,
 * free of Android camera types, and safe against stale asynchronous
 * callbacks:
 *
 * - attempt ids are monotonic and never reused ([attemptId]);
 * - terminal publications from an attempt that is no longer current return
 *   false and change nothing (inert late callbacks);
 * - cancellation ([cancelActiveAttempt]) completes the lifecycle cleanly
 *   WITHOUT publishing any result, so a cancelled pipeline can never leak a
 *   stale outcome into a newer attempt;
 * - pure caller-sequence violations still fail loudly (IllegalStateException)
 *   because they mean miswired production code, not device timing.
 *
 * Each entering of [CaptureAttemptPhase.Binding] bumps [bindEpoch] so the UI
 * can deterministically re-create and re-bind its camera use cases exactly
 * once per binding cycle and release them on dispose (FIX-STATE-04).
 */
class CaptureAttemptController {

    var permission: CapturePermissionStep by mutableStateOf(CapturePermissionStep.NotRequested)
        private set

    var phase: CaptureAttemptPhase? by mutableStateOf<CaptureAttemptPhase?>(null)
        private set

    /**
     * Monotonic id of the most recently begun attempt; 0 before the first
     * one. Ids survive retakes and session resets - they are never reused.
     */
    var attemptId: Long by mutableLongStateOf(0L)
        private set

    /**
     * Monotonic counter of Binding entries; the camera host keys its
     * bind/release cycle on this value.
     */
    var bindEpoch: Long by mutableLongStateOf(0L)
        private set

    fun onPermissionResult(granted: Boolean, canAskAgain: Boolean) {
        onPermissionResolved(resolveCapturePermissionStep(granted, canAskAgain))
    }

    /** Single transition entry for a RESOLVED permission step. */
    fun onPermissionResolved(step: CapturePermissionStep) {
        check(permission == CapturePermissionStep.NotRequested || permission == CapturePermissionStep.DeniedRetryable) {
            "permission already resolved: $permission"
        }
        permission = step
        if (step == CapturePermissionStep.Granted) enterBinding()
    }

    /** Preview use case reported bound; the shutter becomes available. */
    fun onPreviewBound() {
        check(permission == CapturePermissionStep.Granted) { "preview cannot bind without camera permission" }
        check(phase is CaptureAttemptPhase.Binding) { "unexpected preview binding while $phase" }
        phase = CaptureAttemptPhase.Ready
    }

    /** Camera setup failed before any attempt could begin. */
    fun onBindFailed(reason: String) {
        check(phase is CaptureAttemptPhase.Binding) { "binding failure outside Binding, was $phase" }
        phase = CaptureAttemptPhase.Failed(attemptId, reason)
    }

    /**
     * Starts a fresh capture attempt from Ready and returns its unique id.
     * Only legal from Ready; any other phase is a caller bug and fails loudly.
     */
    fun beginAttempt(): Long {
        check(phase is CaptureAttemptPhase.Ready) { "capture attempt requires Ready, was $phase" }
        attemptId += 1
        phase = CaptureAttemptPhase.Capturing
        return attemptId
    }

    /**
     * Moves the active attempt to Processing once its bytes left the camera.
     * Returns false (changing nothing) when no attempt is in Capturing -
     * the stale-callback case, not an error.
     */
    fun markProcessing(): Boolean =
        if (phase is CaptureAttemptPhase.Capturing) {
            phase = CaptureAttemptPhase.Processing
            true
        } else {
            false
        }

    /** Terminal success; inert (false) unless the current attempt is active. */
    fun accept(): Boolean = terminate { CaptureAttemptPhase.Accepted }

    /** Terminal rejection with reasons; inert unless the current attempt is active. */
    fun reject(reasons: Set<QualityReason>): Boolean =
        terminate { CaptureAttemptPhase.Rejected(attemptId, reasons) }

    /** Terminal failure with a display-safe message; inert unless active. */
    fun fail(message: String): Boolean =
        terminate { CaptureAttemptPhase.Failed(attemptId, message) }

    private inline fun terminate(compute: () -> CaptureAttemptPhase): Boolean =
        when (phase) {
            is CaptureAttemptPhase.Capturing, is CaptureAttemptPhase.Processing -> {
                phase = compute()
                true
            }
            else -> false
        }

    /**
     * Explicit user recapture after Rejected/Failed: returns to Binding so
     * the camera host performs a clean re-bind. The next attempt keeps the
     * next monotonic id.
     */
    fun startRetake() {
        check(phase is CaptureAttemptPhase.Rejected || phase is CaptureAttemptPhase.Failed) {
            "retake requires a terminal rejection or failure, was $phase"
        }
        enterBinding()
    }

    /**
     * FIX-STATE-01: cancels an in-flight attempt WITHOUT publishing any
     * result. Late processor/persistence outcomes for this attempt become
     * structurally inert (no active attempt exists). Used by flow teardown
     * and explicit flow resets.
     */
    fun cancelActiveAttempt(): Boolean =
        if (phase is CaptureAttemptPhase.Capturing || phase is CaptureAttemptPhase.Processing) {
            enterBinding()
            true
        } else {
            false
        }

    /**
     * Flow-level restart (for example the scan "start over"): unconditionally
     * drops any phase back to Binding without publishing results. Never
     * throws; safe to call repeatedly.
     */
    fun restartCapture() {
        if (phase !is CaptureAttemptPhase.Binding) enterBinding()
    }

    /** Fresh session teardown: permission and phase reset, ids stay monotonic. */
    fun reset() {
        permission = CapturePermissionStep.NotRequested
        phase = null
    }

    val hasActiveAttempt: Boolean
        get() = phase is CaptureAttemptPhase.Capturing || phase is CaptureAttemptPhase.Processing

    private fun enterBinding() {
        bindEpoch += 1
        phase = CaptureAttemptPhase.Binding
    }
}
