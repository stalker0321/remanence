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

/**
 * Port over account-scoped WORKMANAGER chain cancellation for ONE captured
 * owner (M2-P05). Implementations must act strictly on [owner]'s canonical
 * account tag - never a global tag or another account's selector - and may
 * throw; the use case records the failure without blocking teardown.
 */
fun interface LogoutWorkCancellationPort {
    suspend fun cancelAccountWork(owner: UserId)
}

/** Observable result of [LogoutUseCase.logout]. */
data class LogoutOutcome(
    /**
     * Non-null when the account-scoped temp cleanup failed. Teardown of
     * credentials, local account, and grants is NEVER skipped because of
     * it; tests (and future telemetry) read the failure here instead of a
     * silent pass.
     */
    val tempStorageCleanupFailure: Throwable? = null,
    /**
     * Non-null when the account-scoped work cancellation failed. Same
     * guarantees as above: observable, never teardown-blocking.
     */
    val workCancellationFailure: Throwable? = null,
)

/**
 * FIX-M1-007-07: real account teardown with the mandated ordering -
 * 0. SNAPSHOT the owner being torn down ONCE, from the still-live
 *    `local_account` row, into an immutable value: every later step - and in
 *    particular storage cleanup and work cancellation - acts on exactly that
 *    snapshot even if another login lands mid-call;
 * 1. cancel that ACCOUNT's WorkManager chains through the narrow
 *    cancellation port - BEFORE any server/network teardown and before any
 *    credential or local-account state clears, so no background chain can
 *    observe a half-torn session. A cancellation exception is recorded in
 *    [LogoutOutcome.workCancellationFailure] and never blocks steps 2..6;
 * 2. attempt SERVER logout while the bearer is still live (best-effort:
 *    an unreachable server never traps credentials on the device);
 * 3. clear SESSION material (memory-only access token plus sealed rotating
 *    refresh token) through the atomic rotation sink;
 * 4. run ACCOUNT-SCOPED storage retention against the snapshotted owner -
 *    normal logout purges only that account's temp directory and leaves its
 *    durable encrypted material plus every other account's root untouched.
 *    A cleanup failure is reported in [LogoutOutcome] but never blocks steps
 *    5 and 6;
 * 5. clear LOCAL state: the `local_account` Room row;
 * 6. invalidate any SCAN GRANT held by the running session.
 * Wrapped identity keysets and durable ciphertext stay on disk for this same
 * account, exactly as docs/security.md section 9 requires.
 *
 * M2-P04/P05 review note: without an attributable owner BOTH the work
 * cancellation and the storage cleanup are SKIPPED entirely - cancelling or
 * deleting anything whose ownership could not be proved is worse than
 * leaving in-scope material behind for that account's next login. There is
 * deliberately NO global-cancellation path anywhere in this class.
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
    private val workCancellation: LogoutWorkCancellationPort? = null,
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

        // 1. Account-scoped work cancellation, strictly against the
        // snapshot, BEFORE server/network teardown and before any credential
        // or account state clears. Failure is observable but must never
        // block teardown below; a missing owner skips it entirely.
        var workFailure: Throwable? = null
        val cancellation = workCancellation
        val cancelTarget = owner
        if (cancellation != null && cancelTarget != null) {
            try {
                cancellation.cancelAccountWork(cancelTarget)
            } catch (reported: Throwable) {
                workFailure = reported
            }
        }

        // 2. Server revocation next, while the access token still works.
        val bearer = accessToken.get()
        if (bearer != null) {
            try {
                serverLogout.logout(bearer)
            } catch (_: Exception) {
                // Best effort only: offline logout must remain possible.
            }
        }

        // 3. Session credentials: memory + sealed storage, atomically.
        credentialSink.clear()
        runCatching { tokens.clearToken() }

        // 4. Account-scoped temp cleanup, strictly against the snapshot.
        // Failure is observable but must never block teardown below.
        var cleanupFailure: Throwable? = null
        val cleanup = tempStorageCleanup
        if (cleanup != null && cancelTarget != null) {
            try {
                cleanup.cleanupTemp(cancelTarget)
            } catch (reported: Throwable) {
                cleanupFailure = reported
            }
        }

        // 5. Local account row.
        accounts()

        // 6. Any scan grant of the running session dies with the account.
        grants()

        return LogoutOutcome(
            tempStorageCleanupFailure = cleanupFailure,
            workCancellationFailure = workFailure,
        )
    }
}
