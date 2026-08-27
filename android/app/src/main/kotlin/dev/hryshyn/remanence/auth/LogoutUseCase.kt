package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.session.SessionTokenPort
import dev.hryshyn.remanence.core.data.network.AuthResult
import dev.hryshyn.remanence.core.data.network.SessionRotationSink
import dev.hryshyn.remanence.core.model.UserId

/** Port over the server logout endpoint (protocol.md section 5). */
fun interface ServerLogoutPort {
    suspend fun logout(accessToken: String): AuthResult<Unit>
}

/** Port over the memory-only current access token. */
fun interface CurrentAccessTokenPort {
    fun get(): String?
}

/**
 * Port capturing THE OWNER this logout tears down. Production resolves it
 * against the authenticated `local_account` row; implementations must return
 * a canonical [UserId] or throw - there is never an unattributed fallback.
 */
fun interface LogoutOwnerSnapshotPort {
    suspend fun currentOwnerForTeardown(): UserId
}

/** Port over account-scoped TEMP storage cleanup for one captured owner. */
fun interface LogoutTempCleanupPort {
    /**
     * Purges ONLY the temp root of [owner]. May throw
     * [dev.hryshyn.remanence.core.data.storage.AccountStorageCleanupException];
     * the use case guarantees teardown continues regardless.
     */
    suspend fun cleanupTemp(owner: UserId)
}

/** Observable result of [LogoutUseCase.logout]. */
data class LogoutOutcome(
    /**
     * Non-null when the account-scoped temp cleanup failed. Teardown of
     * credentials, local account, and grants is NEVER skipped because of
     * it; tests (and future telemetry) read the failure here instead of a
     * silent pass.
     */
    val tempStorageCleanupFailure: Throwable?,
)

/**
 * FIX-M1-007-07: real account teardown with the mandated ordering -
 * 0. SNAPSHOT the owner being torn down ONCE, from the still-live
 *    `local_account` row, into an immutable value: every later step - and in
 *    particular the storage cleanup - acts on exactly that snapshot even if
 *    another login lands mid-call;
 * 1. attempt SERVER logout first while the bearer is still live (best-effort:
 *    an unreachable server never traps credentials on the device);
 * 2. clear SESSION material (memory-only access token plus sealed rotating
 *    refresh token) through the atomic rotation sink;
 * 3. run ACCOUNT-SCOPED storage retention against the snapshotted owner -
 *    normal logout purges only that account's temp directory and leaves its
 *    durable encrypted material plus every other account's root untouched.
 *    A cleanup failure is reported in [LogoutOutcome] but never blocks steps
 *    4 and 5;
 * 4. clear LOCAL state: the `local_account` Room row;
 * 5. invalidate any SCAN GRANT held by the running session.
 * Wrapped identity keysets and durable ciphertext stay on disk for this same
 * account, exactly as docs/security.md section 9 requires.
 *
 * M2-P04 review note: without an attributable owner the use case SKIPS the
 * storage cleanup entirely - deleting anything whose ownership could not be
 * proved is worse than leaving in-scope temp bytes behind for the same
 * account's next login.
 */
class LogoutUseCase(
    private val serverLogout: ServerLogoutPort,
    private val accessToken: CurrentAccessTokenPort,
    private val tokens: SessionTokenPort,
    private val credentialSink: SessionRotationSink,
    private val accounts: suspend () -> Unit,
    private val grants: () -> Unit,
    private val logoutOwnerSnapshot: LogoutOwnerSnapshotPort? = null,
    private val tempStorageCleanup: LogoutTempCleanupPort? = null,
) {

    /** Runs every step in order; local completion is unconditional. */
    suspend fun logout(): LogoutOutcome {
        // 0. Immutable owner snapshot - before any identity context changes.
        var owner: UserId? = null
        if (logoutOwnerSnapshot != null) {
            owner = try {
                logoutOwnerSnapshot.currentOwnerForTeardown()
            } catch (_: Exception) {
                null
            }
        }

        // 1. Server revocation first, while the access token still works.
        val bearer = accessToken.get()
        if (bearer != null) {
            try {
                serverLogout.logout(bearer)
            } catch (_: Exception) {
                // Best effort only: offline logout must remain possible.
            }
        }

        // 2. Session credentials: memory + sealed storage, atomically.
        credentialSink.clear()
        runCatching { tokens.clearToken() }

        // 3. Account-scoped temp cleanup, strictly against the snapshot.
        // Failure is observable but must never block teardown below.
        var cleanupFailure: Throwable? = null
        val cleanup = tempStorageCleanup
        val targetOwner = owner
        if (cleanup != null && targetOwner != null) {
            try {
                cleanup.cleanupTemp(targetOwner)
            } catch (reported: Throwable) {
                cleanupFailure = reported
            }
        }

        // 4. Local account row.
        accounts()

        // 5. Any scan grant of the running session dies with the account.
        grants()

        return LogoutOutcome(tempStorageCleanupFailure = cleanupFailure)
    }
}
