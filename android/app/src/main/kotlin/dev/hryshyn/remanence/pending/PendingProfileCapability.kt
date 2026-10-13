package dev.hryshyn.remanence.pending

/**
 * T186 M2: profile capability (T185 `api-contract.md` §2, pure client side).
 *
 * - Capability header `X-Remanence-Profile-Version: 2` negotiates nullable
 *   user summaries; v1 shape (non-null handle) is unchanged for handleful
 *   accounts and headerless clients. (Header emission is wired with the
 *   routes in milestone 3; this type only models the decision.)
 * - `canReceive = authenticated && cryptoReady`.
 * - `canSend = canReceive && handle != null && terms/send-policy`.
 * - A handleless account on an old client gets `CLIENT_UPGRADE_REQUIRED`
 *   before session publication — never a 500, never a fake/empty handle.
 */
object PendingProfileCapability {

    const val PROFILE_VERSION_HEADER = "X-Remanence-Profile-Version"
    const val PROFILE_VERSION_2 = "2"

    data class AccountView(
        val authenticated: Boolean,
        val cryptoReady: Boolean,
        val handle: String?,
        val termsAccepted: Boolean,
    )

    val canReceive: AccountView.() -> Boolean
        get() = { authenticated && cryptoReady }

    /**
     * Send additionally requires the immutable handle (first send prompts
     * for it; see [PendingReceivePolicy]) plus the existing terms gate.
     */
    fun canSend(account: AccountView): Boolean =
        account.authenticated && account.cryptoReady &&
            !account.handle.isNullOrBlank() && account.termsAccepted
}
