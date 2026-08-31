package dev.hryshyn.remanence.session

import dev.hryshyn.remanence.core.crypto.SessionRefreshRecord
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.UserId

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

    /**
     * The local account, identity, and refresh-token record are coherent, but
     * the server could not be reached. Encrypted local material remains
     * available for offline Home/Scan; network work is not started here.
     */
    data class OfflineActive(
        val userId: String?,
        val handle: String?,
        val hasEncryptionKeyset: Boolean,
        val hasSigningKeyset: Boolean,
        val activeKeyBundleId: String? = null,
    ) : SessionState

    /** Proved refresh token plus usable HPKE/Ed25519 keysets: fully crypto-ready. */
    data class Active(
        val userId: String?,
        val handle: String?,
        val hasEncryptionKeyset: Boolean,
        val hasSigningKeyset: Boolean,
        val activeKeyBundleId: String? = null,
    ) : SessionState
}

/** Port over the locally stored, KEK-sealed rotating refresh token record. */
interface SessionTokenPort {
    /** Returns the stored refresh token or `null` when absent/corrupt-cleared. */
    fun readToken(): String?

    /** Returns the versioned owner-bound record, or `null` when absent. */
    fun readRecord(): SessionRefreshRecord?

    /** Atomically replaces the sealed refresh token after a rotation, preserving owner. */
    fun saveToken(refreshToken: String)

    /** Writes a bound owner+token record (login/register install). */
    fun saveRecord(record: SessionRefreshRecord)

    /** Removes the sealed token record (logout or invalid material). */
    fun clearToken()

    /**
     * Removes the sealed record only when it is still exactly [snapshot].
     * Production runs this under the coordinator publication fence.
     */
    fun clearExactRecord(snapshot: SessionRefreshRecord) {
        val current = readRecord() ?: return
        if (current.ownerUserId == snapshot.ownerUserId &&
            current.refreshToken == snapshot.refreshToken
        ) {
            clearToken()
        }
    }
}

/** Port over the wrapped identity keysets on disk. */
interface IdentityAvailabilityPort {
    /** True only when the locally loaded identity derives exactly this bundle ID. */
    fun hasIdentityFor(activeKeyBundleId: String): Boolean
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

    /** A concurrent caller already rotated the token while this call waited. */
    data class Reused(val accessToken: String) : SessionRefreshOutcome

    /** The server definitively refused the session (401/replayed lineage). */
    data object Rejected : SessionRefreshOutcome

    /** Transient failure: the stored token must NOT be discarded. */
    data object Unreachable : SessionRefreshOutcome

    /** The response was not a proven connectivity failure or valid session. */
    data object Unavailable : SessionRefreshOutcome

    /** No token remained when the coordinated refresh acquired its lock. */
    data object NoToken : SessionRefreshOutcome

    /** The refresh was invalidated by logout or a replacement login. */
    data object Invalidated : SessionRefreshOutcome
}

/** Port performing the coordinated cold-start refresh round trip. */
interface SessionRefresher {
    suspend fun hasStoredToken(): Boolean

    suspend fun refresh(expectedOwner: UserId): SessionRefreshOutcome
}

/** What the root surface needs from session resolution. */
interface SessionStateResolver {
    suspend fun bootstrap(): SessionState

    suspend fun logout(): SessionState
}

/**
 * I02 cold-start bootstrap: resolves [SessionState] from the memory-less
 * combination of (sealed rotating refresh token, wrapped identity keysets,
 * account summary) PLUS a live refresh round trip. A connectivity-only
 * failure enters [SessionState.OfflineActive] only after the local account
 * and identity have already been validated, and only when the sealed
 * refresh record is bound to that same canonical owner.
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

    /**
     * Test-only pause after the sealed record snapshot is taken and before
     * the account row is loaded. Production leaves this null.
     */
    internal var onAfterRecordRead: (() -> Unit)? = null

    override suspend fun bootstrap(): SessionState {
        val refresher = this.refresher ?: return SessionState.RequiresConnectivity
        if (!refresher.hasStoredToken()) return SessionState.SignedOut

        val record = try {
            tokens.readRecord()
        } catch (_: Exception) {
            runCatching { tokens.clearToken() }
            return SessionState.SignedOut
        } ?: return SessionState.SignedOut
        onAfterRecordRead?.invoke()

        val summary = account.load()
        val accountOwner = summary?.userId
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { UserId.parseRest(raw) }.getOrNull() }
        if (summary == null || accountOwner == null || accountOwner != record.ownerUserId) {
            runCatching { tokens.clearExactRecord(record) }
            return SessionState.SignedOut
        }
        if (!isCanonicalKeyBundleId(summary.activeKeyBundleId)) {
            return SessionState.RecoveryRequired
        }
        val identityMatches = try {
            identity.hasIdentityFor(summary.activeKeyBundleId)
        } catch (_: Exception) {
            false
        }
        if (!identityMatches) {
            return SessionState.RecoveryRequired
        }

        return when (val outcome = refresher.refresh(accountOwner)) {
            is SessionRefreshOutcome.Rotated,
            is SessionRefreshOutcome.Reused,
            -> SessionState.Active(
                userId = summary.userId,
                handle = summary.handle,
                hasEncryptionKeyset = true,
                hasSigningKeyset = true,
                activeKeyBundleId = summary.activeKeyBundleId,
            )

            SessionRefreshOutcome.Rejected,
            SessionRefreshOutcome.NoToken,
            -> SessionState.SignedOut

            SessionRefreshOutcome.Unreachable -> SessionState.OfflineActive(
                userId = summary.userId,
                handle = summary.handle,
                hasEncryptionKeyset = true,
                hasSigningKeyset = true,
                activeKeyBundleId = summary.activeKeyBundleId,
            )

            SessionRefreshOutcome.Unavailable,
            SessionRefreshOutcome.Invalidated,
            -> SessionState.RequiresConnectivity
        }
    }

    /** Explicit logout: forget the session; wrapped keys stay for this account. */
    override suspend fun logout(): SessionState {
        runCatching { tokens.clearToken() }
        return SessionState.SignedOut
    }

    private fun isCanonicalKeyBundleId(raw: String): Boolean =
        try {
            KeyBundleId.parseRest(raw)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
}
