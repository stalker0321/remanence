package dev.hryshyn.remanence.pending

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSenderRouteTest {

    @Test
    fun verifiedBindingWithUsableBundleStaysV1() {
        val result = PendingSenderRoute.freeze(
            destination = "a@example.org",
            addressVerifiedToCurrentUser = true,
            senderBundleUsable = true,
        )
        val frozen = result as PendingSenderRoute.FreezeResult.Frozen
        assertEquals(PendingSenderRoute.Mode.EXISTING_V1, frozen.route.mode)
    }

    @Test
    fun verifiedBindingWithoutBundleIsErrorNeverSilentEscrow() {
        val result = PendingSenderRoute.freeze(
            destination = "a@example.org",
            addressVerifiedToCurrentUser = true,
            senderBundleUsable = false,
        )
        assertTrue(result is PendingSenderRoute.FreezeResult.Rejected)
    }

    @Test
    fun unboundAddressFreezesPending() {
        val result = PendingSenderRoute.freeze(
            destination = "+380501234567",
            addressVerifiedToCurrentUser = false,
            senderBundleUsable = true,
        )
        val frozen = result as PendingSenderRoute.FreezeResult.Frozen
        assertEquals(PendingSenderRoute.Mode.PENDING_V2, frozen.route.mode)
    }

    @Test
    fun signedV1NeverFallsBackToPending() {
        val frozen = PendingSenderRoute.FrozenRoute(PendingSenderRoute.Mode.EXISTING_V1, "a@example.org")
        assertFalse(PendingSenderRoute.checkNoFallbackAfterSign(frozen, PendingSenderRoute.Mode.PENDING_V2))
        assertTrue(PendingSenderRoute.checkNoFallbackAfterSign(frozen, PendingSenderRoute.Mode.EXISTING_V1))
    }
}
