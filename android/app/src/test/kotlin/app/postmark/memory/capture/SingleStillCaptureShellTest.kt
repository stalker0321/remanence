package app.postmark.memory.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Pure transition proof for the one-still capture shell (M1-R13): permission
 * outcomes map correctly, preview/capture phases cannot skip steps, and the
 * shell terminates after exactly one delivered still.
 */
class SingleStillCaptureShellTest {

    private lateinit var shell: SingleStillCaptureShell

    @Before
    fun setUp() {
        shell = SingleStillCaptureShell()
    }

    private fun grant() {
        shell.onPermissionResult(granted = true, canAskAgain = true)
    }

    private fun reachPreviewReady() {
        grant()
        shell.onPreviewBound()
    }

    private fun expectIllegalState(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalStateException) {
            return
        }
        fail("expected IllegalStateException")
    }

    private fun failedReason(): String {
        val phase = shell.phase
        assertTrue("expected Failed phase, was $phase", phase is StillCapturePhase.Failed)
        return (phase as StillCapturePhase.Failed).reason
    }

    @Test
    fun startsUnrequestedWithoutPhase() {
        assertEquals(CapturePermissionStep.NotRequested, shell.permission)
        assertEquals(null, shell.phase)
    }

    @Test
    fun grantedEntersBindingPreview() {
        grant()
        assertEquals(CapturePermissionStep.Granted, shell.permission)
        assertEquals(StillCapturePhase.BindingPreview, shell.phase)
    }

    @Test
    fun denialOutcomesMapToRetryableOrPermanent() {
        shell.onPermissionResult(granted = false, canAskAgain = true)
        assertEquals(CapturePermissionStep.DeniedRetryable, shell.permission)
        assertEquals(null, shell.phase)

        val fresh = SingleStillCaptureShell()
        fresh.onPermissionResult(granted = false, canAskAgain = false)
        assertEquals(CapturePermissionStep.PermanentlyDenied, fresh.permission)
        assertEquals(null, fresh.phase)
    }

    @Test
    fun secondResultAfterResolutionIsRejected() {
        grant()
        expectIllegalState { shell.onPermissionResult(granted = false, canAskAgain = false) }
    }

    @Test
    fun fullHappyPathEndsDeliveredAfterExactlyOneStill() {
        reachPreviewReady()
        assertEquals(StillCapturePhase.PreviewReady, shell.phase)
        shell.onCaptureStarted()
        assertEquals(StillCapturePhase.Capturing, shell.phase)
        shell.onStillDelivered()
        assertEquals(StillCapturePhase.Delivered, shell.phase)
    }

    @Test
    fun previewCannotBindWithoutPermission() {
        expectIllegalState { shell.onPreviewBound() }
    }

    @Test
    fun captureRequiresReadyPreview() {
        grant()
        expectIllegalState { shell.onCaptureStarted() }
        shell.onPreviewBound()
        shell.onCaptureStarted()
        expectIllegalState { shell.onCaptureStarted() }
    }

    @Test
    fun deliveryRequiresActiveCaptureAndCaptureCannotResumeAfterDelivery() {
        reachPreviewReady()
        expectIllegalState { shell.onStillDelivered() }
        shell.onCaptureStarted()
        shell.onStillDelivered()
        expectIllegalState { shell.onCaptureStarted() }
    }

    @Test
    fun failureDuringCapturingLandsFailedWithReason() {
        reachPreviewReady()
        shell.onCaptureStarted()
        shell.onCaptureFailed("Capture failed")
        assertEquals("Capture failed", failedReason())
        expectIllegalState { shell.onStillDelivered() }
    }

    @Test
    fun bindingFailureIsReportableButDeliveryStaysImpossible() {
        grant()
        shell.onCaptureFailed("Camera unavailable")
        assertEquals("Camera unavailable", failedReason())
        expectIllegalState { shell.onPreviewBound() }
        expectIllegalState { shell.onStillDelivered() }
        assertTrue(shell.permission == CapturePermissionStep.Granted)
    }
}
