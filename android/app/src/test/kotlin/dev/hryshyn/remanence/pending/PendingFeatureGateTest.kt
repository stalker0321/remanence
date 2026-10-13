package dev.hryshyn.remanence.pending

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingFeatureGateTest {

    @Test
    fun liveBetaHostAndPathRejected() {
        assertFalse(
            PendingFeatureGate.isIsolatedApiAccepted("https://remanence.hryshyn.dev/onboarding-test/"),
        )
        assertEquals(
            "reserved-backend",
            PendingFeatureGate.isolatedApiRejection("https://remanence.hryshyn.dev/onboarding-test/"),
        )
        assertFalse(PendingFeatureGate.isIsolatedApiAccepted("https://example.org/onboarding-test/"))
    }

    @Test
    fun isolatedHostsAccepted() {
        assertTrue(PendingFeatureGate.isIsolatedApiAccepted("http://127.0.0.1:8000/"))
        assertTrue(PendingFeatureGate.isIsolatedApiAccepted("https://pending-test.example.org/api/"))
    }

    @Test
    fun blankIsRejected() {
        assertFalse(PendingFeatureGate.isIsolatedApiAccepted("  "))
    }
}
