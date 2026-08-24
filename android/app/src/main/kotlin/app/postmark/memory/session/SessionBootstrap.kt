package app.postmark.memory.session

/**
 * M1-I02 cold-start session state (docs/security.md section 8): the single
 * source every surface reads at launch. Deliberately three-valued - a server
 * session without local keys is RECOVERY_REQUIRED, never silently
 * re-provisioned.
 */
sealed interface SessionState {
    /** No sealed token and no identity: show authentication. */
    data object SignedOut : SessionState

    /** Token exists but the wrapped identity keysets are gone: recovery flow. */
    data object RecoveryRequired : SessionState

    /** Token plus usable HPKE/Ed25519 keysets: fully crypto-ready. */
    data class Active(
        val userId: String?,
        val handle: String?,
        val hasEncryptionKeyset: Boolean,
        val hasSigningKeyset: Boolean,
    ) : SessionState
}

/** Port over the locally stored, KEK-sealed access-token record. */
interface SessionTokenPort {
    fun readToken(): String?

    /** Removes the sealed token record (logout). */
    fun clearToken()
}

/** Port over the wrapped identity keysets on disk. */
interface IdentityAvailabilityPort {
    fun encryptionKeysetAvailable(): Boolean
    fun signingKeysetAvailable(): Boolean
}

fun interface AccountSummaryPort {
    fun load(): PersistedAccountSummary?
}

/**
 * I02 cold-start bootstrap: resolves [SessionState] from the memory-less
 * combination of (sealed session token, wrapped identity keysets, account
 * summary). It NEVER regenerates a missing identity here - that decision
 * belongs to the explicit recovery/registration flows. Logout clears the
 * token and returns to SignedOut; process death is handled naturally because
 * nothing beyond disk state participates.
 */
class SessionBootstrap(
    private val tokens: SessionTokenPort,
    private val identity: IdentityAvailabilityPort,
    private val account: AccountSummaryPort,
) {

    fun bootstrap(): SessionState {
        val token = runCatching { tokens.readToken() }.getOrNull()
            ?: return SessionState.SignedOut

        val encryption = identity.encryptionKeysetAvailable()
        val signing = identity.signingKeysetAvailable()
        if (!encryption || !signing) {
            return SessionState.RecoveryRequired
        }

        val summary = account.load()
        return SessionState.Active(
            userId = summary?.userId,
            handle = summary?.handle,
            hasEncryptionKeyset = true,
            hasSigningKeyset = true,
        )
    }

    /** Explicit logout: forget the session; wrapped keys stay for this account. */
    fun logout(): SessionState {
        runCatching { tokens.clearToken() }
        return SessionState.SignedOut
    }
}
