package dev.hryshyn.remanence.sync

import androidx.work.ListenableWorker
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.session.SessionOwnerCoordinator
import dev.hryshyn.remanence.session.SessionOwnerResolution

/**
 * Common worker admission boundary. A retryable session/connectivity result
 * uses WorkManager backoff; logout, owner change, definitive auth rejection,
 * and local recovery requirements are terminal. The supplied operation is
 * never invoked unless the coordinator proved the expected owner and restored
 * a live access token. WorkManager input contains the owner only: after this
 * admission boundary, the authenticated transport captures a fresh in-memory
 * request lease at each resource call. Therefore a durable A1 chain cannot
 * perform resource I/O before admission, cannot carry an A1 token through
 * process death, and cannot borrow A2 credentials through persisted work data;
 * after a valid A2 admission it can only use the new live lease. No token or
 * incarnation is persisted.
 */
internal suspend fun runWithRestoredSession(
    expectedOwner: UserId,
    coordinator: SessionOwnerCoordinator,
    onSessionOwnerRejected: () -> Unit = {},
    operation: suspend () -> ListenableWorker.Result,
): ListenableWorker.Result = when (coordinator.ensure(expectedOwner)) {
    SessionOwnerResolution.Ready -> operation()
    SessionOwnerResolution.Retryable -> ListenableWorker.Result.retry()
    SessionOwnerResolution.NoOwner,
    SessionOwnerResolution.AccountChanged,
    SessionOwnerResolution.Rejected,
    SessionOwnerResolution.RecoveryRequired,
    -> onSessionOwnerRejected().let { ListenableWorker.Result.failure() }
}
