package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.session.SessionTokenPort
import dev.hryshyn.remanence.core.data.network.AuthResult
import dev.hryshyn.remanence.core.model.UserId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

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

/** Port over the in-memory session credential sink used by logout. */
fun interface LogoutCredentialSink {
    fun clear()
}

/** Observable result of [LogoutUseCase.logout]. */
data class LogoutOutcome(
    /** Failure while proving the owner for account-scoped cleanup. */
    val ownerSnapshotFailure: Exception? = null,
    /**
     * Non-null when the account-scoped temp cleanup failed. Teardown of
     * credentials, local account, and grants is NEVER skipped because of
     * it; tests (and future telemetry) read the failure here instead of a
     * silent pass.
     */
    val tempStorageCleanupFailure: Exception? = null,
    /**
     * Non-null when the account-scoped work cancellation failed. Same
     * guarantees as above: observable, never teardown-blocking.
     */
    val workCancellationFailure: Exception? = null,
    /** Failure clearing the in-memory session credential sink. */
    val credentialSinkClearFailure: Exception? = null,
    /** Failure clearing the sealed refresh-token store. */
    val refreshTokenClearFailure: Exception? = null,
    /** Failure clearing the owner-independent local account row. */
    val localAccountClearFailure: Exception? = null,
    /** Failure invalidating the owner-independent scan grant state. */
    val scanGrantInvalidationFailure: Exception? = null,
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
 *    observe a half-torn session. It must finish or fail before step 2;
 * 2. attempt SERVER logout while the bearer is still live (best-effort:
 *    an unreachable server never traps credentials on the device);
 * 3. clear SESSION material: the in-memory credential sink, then the sealed
 *    rotating refresh-token store;
 * 4. run ACCOUNT-SCOPED storage retention against the snapshotted owner -
 *    normal logout purges only that account's temp directory and leaves its
 *    durable encrypted material plus every other account's root untouched.
 *    A cleanup failure is reported in [LogoutOutcome] but never blocks steps
 *    5 and 6;
 * 5. clear LOCAL state: the `local_account` Room row;
 * 6. invalidate any SCAN GRANT held by the running session.
 *
 * The bounded sequence runs in [NonCancellable], and each operational local
 * [Exception] is recorded independently so a later local step is still
 * attempted. A [CancellationException] is remembered, not treated as a
 * successful result; after the sequence the caller's cancellation is
 * rethrown. Fatal [Error] values are never caught. Owner-scoped steps are
 * skipped when the single owner snapshot fails, while all unscoped steps
 * still run.
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
    private val credentialSink: LogoutCredentialSink,
    private val accounts: suspend () -> Unit,
    private val grants: () -> Unit,
    private val logoutOwnerSnapshot: LogoutOwnerSnapshotPort? = null,
    private val tempStorageCleanup: LogoutTempCleanupPort? = null,
    private val workCancellation: LogoutWorkCancellationPort? = null,
) {

    /** Runs the bounded sequence once, preserving cancellation after cleanup. */
    suspend fun logout(): LogoutOutcome {
        val callerContext = currentCoroutineContext()
        var cancellationFailure: CancellationException? = null

        fun rememberCancellation(failure: CancellationException) {
            if (cancellationFailure == null) cancellationFailure = failure
        }

        val outcome = withContext(NonCancellable) {
            // 0. Immutable owner snapshot - before any identity context changes.
            var owner: UserId? = null
            var ownerSnapshotFailure: Exception? = null
            val snapshot = logoutOwnerSnapshot
            if (snapshot != null) {
                try {
                    owner = snapshot.currentOwnerForTeardown()
                } catch (cancelled: CancellationException) {
                    rememberCancellation(cancelled)
                } catch (failure: Exception) {
                    ownerSnapshotFailure = failure
                }
            }

            // 1. Account-scoped work cancellation, strictly against the
            // snapshot, before server/network teardown and every local clear.
            // Awaiting this port is part of the ordering contract.
            var workFailure: Exception? = null
            val cancellation = workCancellation
            val cancelTarget = owner
            if (cancellation != null && cancelTarget != null) {
                try {
                    cancellation.cancelAccountWork(cancelTarget)
                } catch (cancelled: CancellationException) {
                    rememberCancellation(cancelled)
                } catch (failure: Exception) {
                    workFailure = failure
                }
            }

            // 2. Server revocation while the bearer is live. It is best
            // effort and never blocks local teardown; fatal Errors propagate.
            val bearer = accessToken.get()
            if (bearer != null) {
                try {
                    serverLogout.logout(bearer)
                } catch (cancelled: CancellationException) {
                    rememberCancellation(cancelled)
                } catch (_: Exception) {
                    // Network and protocol failures are intentionally not a
                    // reason to skip local security teardown.
                }
            }

            // 3a. In-memory credential sink.
            var credentialFailure: Exception? = null
            try {
                credentialSink.clear()
            } catch (cancelled: CancellationException) {
                rememberCancellation(cancelled)
            } catch (failure: Exception) {
                credentialFailure = failure
            }

            // 3b. Sealed rotating refresh-token store.
            var refreshTokenFailure: Exception? = null
            try {
                tokens.clearToken()
            } catch (cancelled: CancellationException) {
                rememberCancellation(cancelled)
            } catch (failure: Exception) {
                refreshTokenFailure = failure
            }

            // 4. Account-scoped temp cleanup, strictly against the snapshot.
            var cleanupFailure: Exception? = null
            val cleanup = tempStorageCleanup
            if (cleanup != null && cancelTarget != null) {
                try {
                    cleanup.cleanupTemp(cancelTarget)
                } catch (cancelled: CancellationException) {
                    rememberCancellation(cancelled)
                } catch (failure: Exception) {
                    cleanupFailure = failure
                }
            }

            // 5. Local account row.
            var accountFailure: Exception? = null
            try {
                accounts()
            } catch (cancelled: CancellationException) {
                rememberCancellation(cancelled)
            } catch (failure: Exception) {
                accountFailure = failure
            }

            // 6. Any scan grant of the running session dies with the account.
            var grantsFailure: Exception? = null
            try {
                grants()
            } catch (cancelled: CancellationException) {
                rememberCancellation(cancelled)
            } catch (failure: Exception) {
                grantsFailure = failure
            }

            LogoutOutcome(
                ownerSnapshotFailure = ownerSnapshotFailure,
                tempStorageCleanupFailure = cleanupFailure,
                workCancellationFailure = workFailure,
                credentialSinkClearFailure = credentialFailure,
                refreshTokenClearFailure = refreshTokenFailure,
                localAccountClearFailure = accountFailure,
                scanGrantInvalidationFailure = grantsFailure,
            )
        }

        // A cancellation that happened outside a teardown port must still be
        // reported by the caller's own Job after NonCancellable has finished.
        callerContext.ensureActive()
        cancellationFailure?.let { throw it }
        return outcome
    }
}
