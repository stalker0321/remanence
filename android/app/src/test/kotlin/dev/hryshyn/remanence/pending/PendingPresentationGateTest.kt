package dev.hryshyn.remanence.pending

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPresentationGateTest {

    @Test
    fun supportedReadyPresentationWithLiveGrantEnqueues() {
        assertTrue(
            PendingPresentationGate.shouldEnqueueRetirement(
                featureEnabled = true,
                grantLive = true,
                routeReady = true,
                kind = PendingPresentationGate.PresentationKind.SUPPORTED,
            ),
        )
    }

    @Test
    fun retirementNeverGatesDisplayInputs() {
        // Lookup/proof/claim/admission alone, dead grant, loading route,
        // unsupported content, or disabled flag: never enqueue.
        assertFalse(
            PendingPresentationGate.shouldEnqueueRetirement(true, false, true, PendingPresentationGate.PresentationKind.SUPPORTED),
        )
        assertFalse(
            PendingPresentationGate.shouldEnqueueRetirement(true, true, false, PendingPresentationGate.PresentationKind.SUPPORTED),
        )
        assertFalse(
            PendingPresentationGate.shouldEnqueueRetirement(
                true, true, true, PendingPresentationGate.PresentationKind.UNSUPPORTED,
            ),
        )
        assertFalse(
            PendingPresentationGate.shouldEnqueueRetirement(
                false, true, true, PendingPresentationGate.PresentationKind.SUPPORTED,
            ),
        )
    }
}
