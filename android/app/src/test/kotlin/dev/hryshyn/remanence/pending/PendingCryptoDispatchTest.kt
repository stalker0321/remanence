package dev.hryshyn.remanence.pending

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCryptoDispatchTest {

    @Test
    fun v1AlwaysPassesThrough() {
        assertEquals(
            PendingCryptoDispatch.Dispatch.V1Passthrough,
            PendingCryptoDispatch.dispatch(PendingCryptoDispatch.CapsuleVersion.V1, null),
        )
    }

    @Test
    fun v2WithoutFrozenContractFailsClosedNeverFake() {
        val result = PendingCryptoDispatch.dispatch(
            PendingCryptoDispatch.CapsuleVersion.V2_PENDING,
            "opaque-envelope-id",
            v2ContractFrozen = false,
        )
        assertEquals(PendingCryptoDispatch.Dispatch.V2ContractUnavailable, result)
    }

    @Test
    fun v2WithFrozenContractAndOpaqueIdDispatches() {
        val result = PendingCryptoDispatch.dispatch(
            PendingCryptoDispatch.CapsuleVersion.V2_PENDING,
            "opaque-envelope-id",
            v2ContractFrozen = true,
        )
        assertTrue(result is PendingCryptoDispatch.Dispatch.V2Pending)
    }

    @Test
    fun v2WithFrozenContractButBlankIdFailsClosed() {
        assertEquals(
            PendingCryptoDispatch.Dispatch.V2ContractUnavailable,
            PendingCryptoDispatch.dispatch(
                PendingCryptoDispatch.CapsuleVersion.V2_PENDING,
                "  ",
                v2ContractFrozen = true,
            ),
        )
    }
}
