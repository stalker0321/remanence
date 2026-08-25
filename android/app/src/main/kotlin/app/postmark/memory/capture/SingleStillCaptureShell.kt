package app.postmark.memory.capture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
 * Progress of the single deliberate still after permission was granted.
 * Raw JPEG bytes are never held here; they travel through the delivery
 * callback straight into normalization (docs/security.md section 12).
 */
sealed interface StillCapturePhase {
    /** Permission granted; the preview use case has not reported bound yet. */
    data object BindingPreview : StillCapturePhase

    /** Preview live; the one-shot capture action is enabled. */
    data object PreviewReady : StillCapturePhase

    /** takePicture in flight; the action must stay disabled. */
    data object Capturing : StillCapturePhase

    /** The single still left this component through its callback. */
    data object Delivered : StillCapturePhase

    /** Capture failed before delivering anything; reason text is display-safe. */
    data class Failed(val reason: String) : StillCapturePhase
}

/**
 * Transition rules for [CapturePermissionStep] plus [StillCapturePhase].
 * Illegal transitions fail loudly instead of being silently ignored so a
 * miswired camera surface cannot fake progress.
 */
class SingleStillCaptureShell {

    // Snapshot-backed so Compose recomposes on transitions; plain JVM tests
    // read them like ordinary properties.
    var permission: CapturePermissionStep by mutableStateOf(CapturePermissionStep.NotRequested)
        private set

    var phase: StillCapturePhase? by mutableStateOf<StillCapturePhase?>(null)
        private set

    fun onPermissionResult(granted: Boolean, canAskAgain: Boolean) {
        onPermissionResolved(resolveCapturePermissionStep(granted, canAskAgain))
    }

    /**
     * FIX-REVIEW-05: single transition entry for a RESOLVED permission step.
     * Callers classify the raw system result once through
     * [resolveCapturePermissionStep] and hand the step here.
     */
    fun onPermissionResolved(step: CapturePermissionStep) {
        check(permission == CapturePermissionStep.NotRequested || permission == CapturePermissionStep.DeniedRetryable) {
            "permission already resolved: $permission"
        }
        permission = step
        if (step == CapturePermissionStep.Granted) phase = StillCapturePhase.BindingPreview
    }

    fun onPreviewBound() {
        check(permission == CapturePermissionStep.Granted) { "preview cannot bind without camera permission" }
        check(phase == StillCapturePhase.BindingPreview) { "unexpected preview binding while $phase" }
        phase = StillCapturePhase.PreviewReady
    }

    fun onCaptureStarted() {
        check(phase == StillCapturePhase.PreviewReady) { "capture requires a ready preview, was $phase" }
        phase = StillCapturePhase.Capturing
    }

    fun onStillDelivered() {
        check(phase == StillCapturePhase.Capturing) { "delivery requires an active capture, was $phase" }
        phase = StillCapturePhase.Delivered
    }

    fun onCaptureFailed(reason: String) {
        check(phase == StillCapturePhase.Capturing || phase == StillCapturePhase.BindingPreview) {
            "failure reported outside an active capture/binding, was $phase"
        }
        phase = StillCapturePhase.Failed(reason)
    }
}
