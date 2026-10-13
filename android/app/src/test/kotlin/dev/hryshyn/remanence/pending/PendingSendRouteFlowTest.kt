package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.core.data.network.SendRouteFailure
import dev.hryshyn.remanence.core.data.network.SendRouteResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T186 M8: freeze-flow wiring — the pure route policy finally has a real
 * caller, and post-sign transport cannot fall back across modes.
 */
class PendingSendRouteFlowTest {

    private fun flow(result: SendRouteResult) = PendingSendRouteFlow(
        PendingSendRouteFlow.SendRoutePort { _, _, _, _, _ -> result },
    )

    @Test
    fun v1FreezeThenTransportStaysV1() = runTest {
        val flow = flow(
            SendRouteResult.FrozenV1("route-1", "user-1", "bundle-1", 3L, 1798765432L),
        )
        assertEquals(
            PendingSendRouteFlow.State.Frozen("route-1", PendingSenderRoute.Mode.EXISTING_V1),
            flow.freeze("c", "handle", "mykola", "op", "token"),
        )
        assertTrue(flow.checkTransport(PendingSenderRoute.Mode.EXISTING_V1))
        assertFalse(flow.checkTransport(PendingSenderRoute.Mode.PENDING_V2))
    }

    @Test
    fun pendingFreezeThenTransportStaysPending() = runTest {
        val flow = flow(
            SendRouteResult.FrozenPending(
                "route-2", "target-1", "a42@example.test", "email",
                1798765432L, "svc-1", 1798765432L,
            ),
        )
        assertEquals(
            PendingSendRouteFlow.State.Frozen("route-2", PendingSenderRoute.Mode.PENDING_V2),
            flow.freeze("c", "email", "a42@example.test", "op", "token"),
        )
        assertTrue(flow.checkTransport(PendingSenderRoute.Mode.PENDING_V2))
    }

    @Test
    fun failureSurfacesTyped() = runTest {
        val flow = flow(SendRouteResult.Failure(SendRouteFailure.ADDRESS_UNAVAILABLE, 400, false))
        assertEquals(
            PendingSendRouteFlow.State.Failed(SendRouteFailure.ADDRESS_UNAVAILABLE),
            flow.freeze("c", "email", "gone@example.test", "op", "token"),
        )
        // No frozen route: transport refused entirely.
        assertFalse(flow.checkTransport(PendingSenderRoute.Mode.EXISTING_V1))
    }
}
