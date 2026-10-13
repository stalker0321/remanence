package dev.hryshyn.remanence.pending

/**
 * T186: receive-side proof triage (T184 contract + T185 R7).
 *
 * Pure decision helper only; the server stays authoritative, and the frozen
 * send route (R1) is a DISTINCT server-authoritative fact owned by
 * [PendingSenderRoute] — this decider never freezes or converts routes.
 *
 * - Claimed/owned (v1 OR v2): the gift already belongs to this account.
 *   Ordinary sync/scan continues with NO proof/OTP, regardless of
 *   Google/phone authority. Never converted to another pipeline.
 * - Unclaimed pending: stays v2. An eligible authority proof (fresh Google
 *   or verified channel) may skip the OTP *dispatch*, but the gift still
 *   completes through the pending claim — never a `UseExistingPipeline`
 *   conversion.
 * - Bound to another account: REJECTED (generic unavailable). Never a
 *   pending fallback, never an ownership transfer, never an account merge.
 *
 * No username/email string booleans upgrade anything here: inputs are
 * ownership/binding states, not address text comparisons.
 */
object PendingAddressProofDecider {

    sealed interface Decision {
        /** Already owned: ordinary sync/scan, no proof/OTP of any kind. */
        data object AlreadyOwnedNoProof : Decision

        /**
         * Unclaimed pending: stays on the v2 claim path.
         * [otpSkippableViaAuthority] true means an eligible authority proof
         * may satisfy verification without an OTP dispatch — the claim still
         * runs as pending.
         */
        data class PendingProof(val otpSkippableViaAuthority: Boolean) : Decision

        /** Bound to a different account: reject, no fallback. */
        data object RejectedBoundToOther : Decision
    }

    fun decide(
        isClaimedOrOwnedByCurrentAccount: Boolean,
        isBoundToOtherAccount: Boolean,
        authorityProofAvailable: Boolean,
    ): Decision {
        if (isBoundToOtherAccount) return Decision.RejectedBoundToOther
        if (isClaimedOrOwnedByCurrentAccount) return Decision.AlreadyOwnedNoProof
        return Decision.PendingProof(otpSkippableViaAuthority = authorityProofAvailable)
    }
}
