package dev.hryshyn.remanence.pending

/**
 * T186: sender route freeze (T184 contract, T185 R1).
 *
 * The recipient/mode choice is frozen BEFORE signing. A signed existing-v1
 * send must never fall back to pending on error, stale bundle, or missing
 * client support. This is a pure state machine over the DISTINCT
 * server-authoritative route fact (R1 `EXISTING_USER_V1` / `PENDING_V2`);
 * wire DTOs arrive with the frozen T185 `api-contract.md` and are
 * integrated then — nothing here invents or sends network bytes. It never
 * doubles as receive-side proof triage (see [PendingAddressProofDecider]).
 */
object PendingSenderRoute {

    enum class Mode { EXISTING_V1, PENDING_V2 }

    data class FrozenRoute(
        val mode: Mode,
        val destination: String,
    )

    sealed interface FreezeResult {
        data class Frozen(val route: FrozenRoute) : FreezeResult
        data class Rejected(val reason: String) : FreezeResult
    }

    /**
     * Freezes the send route. [addressVerifiedToCurrentUser] must reflect the
     * server-authoritative verified registry (T185), not local history.
     */
    fun freeze(
        destination: String,
        addressVerifiedToCurrentUser: Boolean,
        senderBundleUsable: Boolean,
    ): FreezeResult {
        if (destination.isBlank()) return FreezeResult.Rejected("invalid-destination")
        return if (addressVerifiedToCurrentUser) {
            if (!senderBundleUsable) {
                // Verified binding without a usable bundle is an error —
                // never a silent downgrade to escrow.
                FreezeResult.Rejected("no-usable-bundle")
            } else {
                FreezeResult.Frozen(FrozenRoute(Mode.EXISTING_V1, destination))
            }
        } else {
            FreezeResult.Frozen(FrozenRoute(Mode.PENDING_V2, destination))
        }
    }

    /**
     * Guards post-sign transport: a frozen v1 route stays v1. Any attempt to
     * re-route a signed v1 payload to pending fails closed.
     */
    fun checkNoFallbackAfterSign(frozen: FrozenRoute, attempted: Mode): Boolean =
        !(frozen.mode == Mode.EXISTING_V1 && attempted == Mode.PENDING_V2)
}
