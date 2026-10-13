package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.ui.navigation.AuthUiState
import dev.hryshyn.remanence.ui.home.AccountCapabilityState

/**
 * T186: receive-only nullable profile (T184 contract).
 *
 * A receive-only account on the same immutable identity may defer its unique
 * immutable handle until first send. No empty placeholder identity is ever
 * minted: [handle] stays null until the user explicitly picks one, and the
 * first send is the only place that requires it. Existing auth/Google + PRF /
 * ARK / key-recovery readiness is unchanged and lives outside this type.
 */
data class PendingReceiveProfile(
    val email: String? = null,
    val phone: String? = null,
    val handle: String? = null,
)

object PendingReceivePolicy {

    /** True when the account is already usable and must skip new setup. */
    fun isKnownReady(
        authState: AuthUiState,
        capability: AccountCapabilityState,
    ): Boolean = authState is AuthUiState.Authenticated &&
        capability is AccountCapabilityState.CryptoReady

    /**
     * Handle is required only at first send. Receive/claim/code lookup never
     * requires it. Returns true when the send UI must ask for an immutable
     * handle now (profile has none and caller is about to send).
     */
    fun shouldPromptForHandleOnSend(profile: PendingReceiveProfile): Boolean =
        profile.handle.isNullOrBlank()

    /**
     * Fail-closed handle binding: sets the handle exactly once. Re-binding
     * (immutable ownership, no auto-merge) is rejected.
     */
    fun bindHandleOnce(
        profile: PendingReceiveProfile,
        handle: String,
    ): PendingReceiveProfile {
        require(handle.isNotBlank()) { "handle must not be blank" }
        check(profile.handle.isNullOrBlank()) { "handle is already bound and immutable" }
        return profile.copy(handle = handle)
    }
}
