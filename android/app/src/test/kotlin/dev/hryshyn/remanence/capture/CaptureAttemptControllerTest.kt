package dev.hryshyn.remanence.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import dev.hryshyn.remanence.core.recognition.QualityReason

/**
 * FIX-STATE-01 regression proof for THE authoritative capture attempt
 * contract: monotonic attempt ids, a guaranteed terminal phase for every
 * begun attempt, inert stale callbacks, clean cancellation, and distinct
 * observable events for repeated identical rejections.
 */
class CaptureAttemptControllerTest {

    private lateinit var controller: CaptureAttemptController

    @Before
    fun setUp() {
        controller = CaptureAttemptController()
    }

    private fun reachReady() {
        controller.onPermissionResult(granted = true, canAskAgain = false)
        controller.onPreviewBound()
    }

    private fun begin(): Long {
        val id = controller.beginAttempt()
        controller.markProcessing()
        return id
    }

    private fun expectIllegalState(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalStateException) {
            return
        }
        fail("expected IllegalStateException")
    }

    // ------------------------------------------------------------------
    // Permission half (FIX-REVIEW-05 semantics preserved).
    // ------------------------------------------------------------------

    @Test
    fun startsUnrequestedWithoutPhase() {
        assertEquals(CapturePermissionStep.NotRequested, controller.permission)
        assertEquals(null, controller.phase)
        assertEquals(0L, controller.attemptId)
    }

    @Test
    fun grantedEntersBindingAndBindEpochAdvancesPerBindingCycle() {
        assertEquals(0L, controller.bindEpoch)
        controller.onPermissionResult(granted = true, canAskAgain = false)
        assertEquals(CaptureAttemptPhase.Binding, controller.phase)
        assertEquals(1L, controller.bindEpoch)

        controller.onPreviewBound()
        controller.beginAttempt()
        controller.cancelActiveAttempt()

        // Every re-entry into Binding bumps the epoch exactly once.
        assertEquals(CaptureAttemptPhase.Binding, controller.phase)
        assertEquals(2L, controller.bindEpoch)
    }

    @Test
    fun denialOutcomesMapToRetryableOrPermanent() {
        controller.onPermissionResult(false, canAskAgain = true)
        assertEquals(CapturePermissionStep.DeniedRetryable, controller.permission)

        val fresh = CaptureAttemptController()
        fresh.onPermissionResult(false, canAskAgain = false)
        assertEquals(CapturePermissionStep.PermanentlyDenied, fresh.permission)
    }

    @Test
    fun secondPermissionResolutionIsRejected() {
        controller.onPermissionResult(true, false)
        expectIllegalState { controller.onPermissionResult(false, true) }
    }

    // ------------------------------------------------------------------
    // Happy path and terminal guarantee.
    // ------------------------------------------------------------------

    @Test
    fun happyPathEndsAcceptedAfterExactlyOneStill() {
        reachReady()
        assertEquals(CaptureAttemptPhase.Ready, controller.phase)

        val id = controller.beginAttempt()
        assertEquals(1L, id)
        assertEquals(CaptureAttemptPhase.Capturing, controller.phase)

        assertTrue(controller.markProcessing())
        assertEquals(CaptureAttemptPhase.Processing, controller.phase)

        assertTrue(controller.accept())
        assertEquals(CaptureAttemptPhase.Accepted, controller.phase)
        assertTrue(controller.phase!!.isTerminal)
    }

    @Test
    fun rejectionCarriesMonotonicAttemptIdAndReasons() {
        reachReady()
        val id = controller.beginAttempt()
        controller.markProcessing()
        val reasons = setOf(QualityReason.TOO_BLURRY)
        assertTrue(controller.reject(reasons))

        val phase = controller.phase
        assertTrue(phase is CaptureAttemptPhase.Rejected)
        assertEquals(id, (phase as CaptureAttemptPhase.Rejected).attemptId)
        assertEquals(reasons, phase.reasons)
    }

    /**
     * FIX-STATE-02 core regression: two consecutive identical TOO_BLURRY
     * rejections are TWO DISTINCT events with different attempt ids - the
     * content of Set<QualityReason> is never used as the event identity.
     */
    @Test
    fun repeatedIdenticalRejectionYieldsTwoDistinctObservableEvents() {
        reachReady()
        controller.beginAttempt()
        assertTrue(controller.reject(setOf(QualityReason.TOO_BLURRY)))
        val first = controller.phase as CaptureAttemptPhase.Rejected

        controller.startRetake()
        assertEquals(CaptureAttemptPhase.Binding, controller.phase)
        controller.onPreviewBound()
        assertTrue(controller.phase is CaptureAttemptPhase.Ready)

        val secondId = controller.beginAttempt()
        assertTrue(secondId > first.attemptId)
        controller.markProcessing()
        assertTrue(controller.reject(setOf(QualityReason.TOO_BLURRY)))
        val second = controller.phase as CaptureAttemptPhase.Rejected

        assertFalse(first == second)
        assertFalse(first.attemptId == second.attemptId)
        assertEquals(first.reasons, second.reasons)
    }

    @Test
    fun failureFromCapturingAlsoTerminatesTheAttempt() {
        reachReady()
        val id = controller.beginAttempt()
        assertTrue(controller.fail("camera disconnected"))
        val phase = controller.phase
        assertTrue(phase is CaptureAttemptPhase.Failed)
        assertEquals(id, (phase as CaptureAttemptPhase.Failed).attemptId)
        assertEquals("camera disconnected", phase.message)
    }

    @Test
    fun retakeFromFailureReturnsToBindingWithNextMonotonicId() {
        reachReady()
        controller.beginAttempt()
        controller.fail("disk full")
        controller.startRetake()
        assertEquals(CaptureAttemptPhase.Binding, controller.phase)
        controller.onPreviewBound()
        assertEquals(2L, controller.beginAttempt())
    }

    // ------------------------------------------------------------------
    // Illegal sequences fail loudly.
    // ------------------------------------------------------------------

    @Test
    fun beginAttemptRequiresReadyPhase() {
        expectIllegalState { controller.beginAttempt() }
        controller.onPermissionResult(true, false)
        expectIllegalState { controller.beginAttempt() }
        controller.onPreviewBound()
        controller.beginAttempt()
        expectIllegalState { controller.beginAttempt() }
    }

    @Test
    fun previewCannotBindWithoutPermissionOrOutsideBinding() {
        expectIllegalState { controller.onPreviewBound() }
        controller.onPermissionResult(true, false)
        controller.onPreviewBound()
        expectIllegalState { controller.onPreviewBound() }
    }

    @Test
    fun bindFailureOnlyLegalDuringBinding() {
        expectIllegalState { controller.onBindFailed("no camera") }
        controller.onPermissionResult(true, false)
        controller.onBindFailed("Camera unavailable")
        assertEquals(
            CaptureAttemptPhase.Failed(0L, "Camera unavailable"),
            controller.phase,
        )
        expectIllegalState { controller.onPreviewBound() }
    }

    @Test
    fun retakeRequiresTerminalPhase() {
        expectIllegalState { controller.startRetake() }
        reachReady()
        expectIllegalState { controller.startRetake() }
    }

    // ------------------------------------------------------------------
    // Stale callbacks are structurally inert.
    // ------------------------------------------------------------------

    @Test
    fun terminalPublicationsWithoutActiveAttemptAreInert() {
        reachReady()
        assertFalse(controller.markProcessing())
        assertFalse(controller.accept())
        assertFalse(controller.reject(setOf(QualityReason.TOO_BLURRY)))
        assertFalse(controller.fail("late"))
        assertEquals(CaptureAttemptPhase.Ready, controller.phase)
    }

    /**
     * FIX-STATE-01: cancellation completes the lifecycle WITHOUT publishing
     * any result; a late processor outcome afterwards must be inert.
     */
    @Test
    fun cancelledAttemptNeverPublishesAndLateOutcomeIsInert() {
        reachReady()
        controller.beginAttempt()
        controller.markProcessing()
        assertTrue(controller.cancelActiveAttempt())

        assertEquals(CaptureAttemptPhase.Binding, controller.phase)
        // No terminal publication happened...
        assertTrue(
            controller.phase !is CaptureAttemptPhase.Rejected &&
                controller.phase !is CaptureAttemptPhase.Failed &&
                controller.phase != CaptureAttemptPhase.Accepted,
        )
        // ...and the late pipeline result cannot resurrect anything.
        assertFalse(controller.accept())
        assertFalse(controller.reject(setOf(QualityReason.TOO_DARK)))
        assertFalse(controller.fail("late failure"))
        assertEquals(CaptureAttemptPhase.Binding, controller.phase)
    }

    @Test
    fun cancelOutsideActiveAttemptReportsFalseWithoutChange() {
        reachReady()
        assertFalse(controller.cancelActiveAttempt())
        assertEquals(CaptureAttemptPhase.Ready, controller.phase)
    }

    /** Flow-level restart never throws, whatever the current phase is. */
    @Test
    fun restartCaptureDropsAnyPhaseBackToBinding() {
        controller.restartCapture()
        assertEquals(CaptureAttemptPhase.Binding, controller.phase)

        reachReady()
        controller.beginAttempt()
        controller.markProcessing()
        controller.restartCapture()
        assertEquals(CaptureAttemptPhase.Binding, controller.phase)
    }

    @Test
    fun resetClearsPermissionAndPhaseButKeepsIdsMonotonic() {
        reachReady()
        controller.beginAttempt()
        controller.accept()
        val lastId = controller.attemptId

        controller.reset()
        assertEquals(CapturePermissionStep.NotRequested, controller.permission)
        assertEquals(null, controller.phase)
        assertEquals(lastId, controller.attemptId)

        controller.onPermissionResult(true, false)
        controller.onPreviewBound()
        assertTrue(controller.attemptId > 0 && controller.beginAttempt() > lastId)
    }
}
