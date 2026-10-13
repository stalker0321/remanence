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
 * caller, post-sign transport cannot fall back across modes, and the full
 * frozen identity survives for sender wiring.
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
        val frozen = flow.freeze("c", "handle", "mykola", "op", "token")
        val identity = (frozen as PendingSendRouteFlow.State.Frozen).identity
            as PendingSendRouteFlow.FrozenIdentity.V1
        assertEquals("route-1", identity.routeId)
        assertEquals("user-1", identity.userId)
        assertEquals("bundle-1", identity.keyBundleId)
        assertEquals(3L, identity.bindingRevision)
        assertTrue(flow.checkTransport(PendingSenderRoute.Mode.EXISTING_V1))
        assertFalse(flow.checkTransport(PendingSenderRoute.Mode.PENDING_V2))
    }

    @Test
    fun nullRevisionSurvivesForHandleRoutes() = runTest {
        val flow = flow(
            SendRouteResult.FrozenV1("route-1", "user-1", "bundle-1", null, 1798765432L),
        )
        val frozen = flow.freeze("c", "handle", "mykola", "op", "token")
        val identity = (frozen as PendingSendRouteFlow.State.Frozen).identity
            as PendingSendRouteFlow.FrozenIdentity.V1
        // Never faked to 0: null survives durably.
        assertEquals(null, identity.bindingRevision)
        assertTrue(flow.checkTransport(PendingSenderRoute.Mode.EXISTING_V1))
    }

    @Test
    fun pendingFreezeKeepsFullIdentity() = runTest {
        val flow = flow(
            SendRouteResult.FrozenPending(
                "route-2", "target-1", "a42@example.test", "email",
                1798765432L, "svc-1", 1798765432L,
            ),
        )
        val frozen = flow.freeze("c", "email", "a42@example.test", "op", "token")
        val identity = (frozen as PendingSendRouteFlow.State.Frozen).identity
            as PendingSendRouteFlow.FrozenIdentity.Pending
        assertEquals("target-1", identity.deliveryTargetId)
        assertEquals("a42@example.test", identity.normalizedDestination)
        assertEquals("svc-1", identity.serviceKeyId)
        assertEquals(1798765432L, identity.claimDeadline)
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
