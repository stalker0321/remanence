package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.core.data.network.SendRouteFailure
import dev.hryshyn.remanence.core.data.network.SendRouteResult

/**
 * T186 M8: send-route freeze flow (frozen R1). Real caller of
 * [PendingSenderRoute] and the R1 client: freezes the recipient/mode
 * BEFORE signing and enforces no v1→pending fallback afterwards.
 * Transport-agnostic (injected port); UI mount follows on navigation
 * handoff. Routes scaffold server-side; failures stay typed.
 */
class PendingSendRouteFlow(
    private val port: SendRoutePort,
) {
    sealed interface State {
        data object Idle : State
        data object Freezing : State
        data class Frozen(val routeId: String, val mode: PendingSenderRoute.Mode) : State
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
                State.Frozen(result.routeId, PendingSenderRoute.Mode.EXISTING_V1)
            is SendRouteResult.FrozenPending ->
                State.Frozen(result.routeId, PendingSenderRoute.Mode.PENDING_V2)
            is SendRouteResult.Failure -> State.Failed(result.reason)
        }
        return state
    }

    /**
     * Post-sign transport guard: a frozen v1 route stays v1. Returns false
     * (fail-closed, caller must abort) on any v1→pending re-route attempt.
     */
    fun checkTransport(mode: PendingSenderRoute.Mode): Boolean {
        val frozen = state as? State.Frozen ?: return false
        return PendingSenderRoute.checkNoFallbackAfterSign(
            PendingSenderRoute.FrozenRoute(frozen.mode, ""),
            mode,
        )
    }
}
