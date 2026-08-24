package app.postmark.memory.session

/**
 * M1-I02 cold-start session state (docs/security.md section 8): the single
 * source every surface reads at launch. Deliberately multi-valued - a server
 * session without local keys is RECOVERY_REQUIRED, never silently
 * re-provisioned, and a stored session is ACTIVE only after the sealed
 * rotating refresh token proved itself against the server.
 */
sealed interface SessionState {
    /** No sealed refresh token (or an invalid one, now cleared): show authentication. */
    data object SignedOut : SessionState

    /** Refresh token exists but the wrapped identity keysets are gone: recovery flow. */
    data object RecoveryRequired : SessionState

    /**
     * Sealed refresh token plus usable keysets, but the server could not be
     * reached to prove the session; connectivity is required before Home.
     */
    data object RequiresConnectivity : SessionState

    /** Proved refresh token plus usable HPKE/Ed25519 keysets: fully crypto-ready. */
    data class Active(
        val userId: String?,
        val handle: String?,
        val hasEncryptionKeyset: Boolean,
        val hasSigningKeyset: Boolean,
    ) : SessionState
}

/** Port over the locally stored, KEK-sealed rotating refresh token record. */
interface SessionTokenPort {
    /** Returns the stored refresh token or `null` when absent/corrupt-cleared. */
    fun readToken(): String?

    /** Atomically replaces the sealed refresh token after a rotation. */
    fun saveToken(refreshToken: String)

    /** Removes the sealed token record (logout or invalid material). */
    fun clearToken()
}

/** Port over the wrapped identity keysets on disk. */
interface IdentityAvailabilityPort {
    fun encryptionKeysetAvailable(): Boolean

    fun signingKeysetAvailable(): Boolean
}

fun interface AccountSummaryPort {
    suspend fun load(): PersistedAccountSummary?
}

/** Cold-start refresh outcome against `/v1/auth/refresh`. */
sealed interface SessionRefreshOutcome {
    /** The stored token was accepted; both credentials rotated. */
    data class Rotated(
        val accessToken: String,
        val refreshToken: String,
    ) : SessionRefreshOutcome

    /** The server definitively refused the session (401/replayed lineage). */
    data object Rejected : SessionRefreshOutcome

    /** Transient failure: the stored token must NOT be discarded. */
    data object Unreachable : SessionRefreshOutcome
}

/** Port performing the cold-start refresh round trip. */
fun interface SessionRefresher {
    suspend fun refresh(storedRefreshToken: String): SessionRefreshOutcome
}

/** What the root surface needs from session resolution. */
interface SessionStateResolver {
    suspend fun bootstrap(): SessionState

    suspend fun logout(): SessionState
}

/**
 * I02 cold-start bootstrap: resolves [SessionState] from the memory-less
 * combination of (sealed rotating refresh token, wrapped identity keysets,
 * account summary) PLUS a live refresh round trip - a cold start never enters
 * Active on persisted bytes alone.
 *
 * The ACCESS token is deliberately absent from persistence: it lives only in
 * process memory for its short lifetime. Invalid or corrupt sealed material
 * is cleared immediately instead of being retried forever. This class NEVER
 * regenerates a missing identity - that decision belongs to the explicit
 * recovery/registration flows.
 */
class SessionBootstrap(
    private val tokens: SessionTokenPort,
    private val identity: IdentityAvailabilityPort,
    private val account: AccountSummaryPort,
    private val refresher: SessionRefresher? = null,
) : SessionStateResolver {

    override suspend fun bootstrap(): SessionState {
        val stored = readSealedToken()
            ?: return SessionState.SignedOut

        val encryption = identity.encryptionKeysetAvailable()
        val signing = identity.signingKeysetAvailable()
        if (!encryption || !signing) {
            return SessionState.RecoveryRequired
        }

        val refresher = this.refresher ?: return SessionState.RequiresConnectivity
        return when (val outcome = refresher.refresh(stored)) {
            is SessionRefreshOutcome.Rotated -> {
                // Persist only the rotated REFRESH token; the fresh access
                // token stays in process memory with its holder. A persistence
                // failure clears the record rather than half-trusting it.
                val persisted = try {
                    tokens.saveToken(outcome.refreshToken)
                    true
                } catch (_: Exception) {
                    tokens.clearToken()
                    false
                }
                if (!persisted) return SessionState.SignedOut
                val summary = account.load()
                SessionState.Active(
                    userId = summary?.userId,
                    handle = summary?.handle,
                    hasEncryptionKeyset = true,
                    hasSigningKeyset = true,
                )
            }

            SessionRefreshOutcome.Rejected -> {
                tokens.clearToken()
                SessionState.SignedOut
            }

            SessionRefreshOutcome.Unreachable -> SessionState.RequiresConnectivity
        }
    }

    /** Explicit logout: forget the session; wrapped keys stay for this account. */
    override suspend fun logout(): SessionState {
        runCatching { tokens.clearToken() }
        return SessionState.SignedOut
    }

    /**
     * Reads the sealed refresh token; corrupt/undecryptable material is
     * cleared so a tampered record can never linger on disk.
     */
    private fun readSealedToken(): String? =
        try {
            val token = tokens.readToken()
            if (token.isNullOrBlank()) {
                tokens.clearToken()
                null
            } else {
                token
            }
        } catch (_: Exception) {
            tokens.clearToken()
            null
        }
}
