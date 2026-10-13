package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.core.data.network.SendRouteFailure
import dev.hryshyn.remanence.core.data.network.SendRouteResult

/**
 * T186 M8: send-route freeze flow (frozen R1). Real caller of
 * [PendingSenderRoute] and the R1 client: freezes the recipient/mode
 * BEFORE signing and enforces no v1→pending fallback afterwards.
 *
 * The frozen identity is DURABLE: the full mode-specific payload
 * (user/bundle/revision or target/normalized/channel/service-key/
 * deadline) survives in [State.Frozen] for the actual sender wiring —
 * never reduced to route-id + mode. Transport-agnostic (injected port);
 * sender UI mount follows on navigation handoff. Routes scaffold
 * server-side; failures stay typed.
 */
class PendingSendRouteFlow(
    private val port: SendRoutePort,
) {
    /** Full frozen route identity; every field the sender needs later. */
    sealed interface FrozenIdentity {
        val routeId: String
        val mode: PendingSenderRoute.Mode
        val expiresAt: Long

        data class V1(
            override val routeId: String,
            val userId: String,
            val keyBundleId: String,
            /** Null for handle-routed decisions; never faked. */
            val bindingRevision: Long?,
            override val expiresAt: Long,
        ) : FrozenIdentity {
            override val mode: PendingSenderRoute.Mode = PendingSenderRoute.Mode.EXISTING_V1
        }

        data class Pending(
            override val routeId: String,
            val deliveryTargetId: String,
            val normalizedDestination: String,
            val channel: String,
            val claimDeadline: Long,
            val serviceKeyId: String,
            override val expiresAt: Long,
        ) : FrozenIdentity {
            override val mode: PendingSenderRoute.Mode = PendingSenderRoute.Mode.PENDING_V2
        }
    }

    sealed interface State {
        data object Idle : State
        data object Freezing : State
        data class Frozen(val identity: FrozenIdentity) : State
        data class Failed(val reason: SendRouteFailure) : State
    }

    fun interface SendRoutePort {
        suspend fun freeze(
            capsuleId: String,
            kind: String,
            rawValue: String,
            idempotencyKey: String,
            token: String,
        ): SendRouteResult
    }

    private var state: State = State.Idle
        private set

    fun current(): State = state

    suspend fun freeze(
        capsuleId: String,
        kind: String,
        rawValue: String,
        idempotencyKey: String,
        token: String,
    ): State {
        state = State.Freezing
        state = when (val result = port.freeze(capsuleId, kind, rawValue, idempotencyKey, token)) {
            is SendRouteResult.FrozenV1 ->
                State.Frozen(
                    FrozenIdentity.V1(
                        result.routeId, result.userId, result.keyBundleId,
                        result.bindingRevision, result.expiresAt,
                    ),
                )
            is SendRouteResult.FrozenPending ->
                State.Frozen(
                    FrozenIdentity.Pending(
                        result.routeId, result.deliveryTargetId, result.normalizedDestination,
                        result.channel, result.claimDeadline, result.serviceKeyId, result.expiresAt,
                    ),
                )
            is SendRouteResult.Failure -> State.Failed(result.reason)
        }
        return state
    }

    /**
     * Post-sign transport guard: a frozen route stays in its mode. Returns
     * false (fail-closed, caller must abort) on any cross-mode re-route
     * attempt or when nothing is frozen.
     */
    fun checkTransport(mode: PendingSenderRoute.Mode): Boolean {
        val frozen = (state as? State.Frozen)?.identity ?: return false
        return PendingSenderRoute.checkNoFallbackAfterSign(
            PendingSenderRoute.FrozenRoute(frozen.mode, ""),
            mode,
        )
    }
}
