package app.postmark.memory.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import postmark.core.recognition.ManualCropQuad
import postmark.core.recognition.PointD

/**
 * Pure transition proof for the crop-confirm step (M1-R15): proposals are
 * canonicalized, manual edits revalidate live, invalid shapes cannot be
 * confirmed, and recapture is reachable until confirmation lands.
 */
class CropConfirmationShellTest {

    private val frame = ManualCropQuad(
        corners = listOf(
            PointD(100.0, 100.0),
            PointD(900.0, 100.0),
            PointD(900.0, 600.0),
            PointD(100.0, 600.0),
        ),
        frameWidth = 1000,
        frameHeight = 800,
    )

    private lateinit var shell: CropConfirmationShell

    @Before
    fun setUp() {
        shell = CropConfirmationShell(frame)
    }

    private fun expectIllegalState(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalStateException) {
            return
        }
        fail("expected IllegalStateException")
    }

    @Test
    fun proposalIsCanonicalizedOnCreation() {
        // Same rectangle as a rotated cycle (starts at top-right); stored quad
        // must be canonicalized to clockwise-from-top-left.
        val rotatedCycle = frame.copy(
            corners = listOf(frame.corners[1], frame.corners[2], frame.corners[3], frame.corners[0]),
        )
        val fromRotated = CropConfirmationShell(rotatedCycle)
        val proposing = fromRotated.step as CropConfirmationShell.Step.Proposing
        assertEquals(frame.corners, proposing.quad.corners)
    }

    @Test
    fun rejectedProposalRefusesToStart() {
        // Convex but far below the minimum-area floor.
        val degenerate = frame.copy(
            corners = listOf(
                PointD(498.0, 398.0),
                PointD(502.0, 398.0),
                PointD(502.0, 402.0),
                PointD(498.0, 402.0),
            ),
        )
        try {
            CropConfirmationShell(degenerate)
            fail("expected rejection of degenerate proposal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("DEGENERATE_AREA"))
        }
    }

    @Test
    fun adjustingReflectsLiveValidation() {
        shell.startAdjusting()
        var adjusting = shell.step
        assertTrue("was $adjusting", adjusting is CropConfirmationShell.Step.Adjusting)
        adjusting as CropConfirmationShell.Step.Adjusting
        assertTrue(adjusting.valid)

        // Drag one corner outside the frame.
        shell.updateCorner(0, PointD(-50.0, 100.0))
        adjusting = shell.step
        assertTrue("was $adjusting", adjusting is CropConfirmationShell.Step.Adjusting)
        val invalid = adjusting as CropConfirmationShell.Step.Adjusting
        assertFalse(invalid.valid)
        assertEquals("OUT_OF_FRAME", invalid.invalidReason)

        // Restore a valid shape.
        shell.updateCorner(0, PointD(120.0, 90.0))
        val restored = shell.step
        assertTrue("was $restored", restored is CropConfirmationShell.Step.Adjusting && restored.valid)
    }

    @Test
    fun invalidShapeCannotBeConfirmedButValidOneConfirmsWithOrderedCorners() {
        shell.startAdjusting()
        shell.updateCorner(1, PointD(900.0, -10.0))
        expectIllegalState { shell.confirm() }

        shell.updateCorner(1, PointD(880.0, 120.0))
        var received: List<PointD>? = null
        shell.confirm { received = it }
        val confirmed = shell.step
        assertTrue("was $confirmed", confirmed is CropConfirmationShell.Step.Confirmed)
        val orderedCorners = (confirmed as CropConfirmationShell.Step.Confirmed).orderedCorners
        assertEquals(orderedCorners, received)
        assertEquals(4, orderedCorners.size)
    }

    @Test
    fun recaptureReachableFromProposalAndAdjustmentOnly() {
        shell.requestRecapture()
        assertEquals(CropConfirmationShell.Step.Recapture, shell.step)
        expectIllegalState { shell.requestRecapture() }

        val second = CropConfirmationShell(frame)
        second.startAdjusting()
        second.updateCorner(2, PointD(880.0, 580.0))
        second.requestRecapture()
        assertEquals(CropConfirmationShell.Step.Recapture, second.step)

        val third = CropConfirmationShell(frame)
        third.startAdjusting()
        third.confirm()
        expectIllegalState { third.requestRecapture() }
    }

    @Test
    fun cornerUpdatesRequireAdjustingMode() {
        expectIllegalState { shell.updateCorner(0, PointD(1.0, 1.0)) }
    }
}
