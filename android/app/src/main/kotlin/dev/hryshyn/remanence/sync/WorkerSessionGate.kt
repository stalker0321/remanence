package dev.hryshyn.remanence.sync

import androidx.work.ListenableWorker
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.session.SessionOwnerCoordinator
import dev.hryshyn.remanence.session.SessionOwnerResolution

/**
 * Common worker admission boundary. A retryable session/connectivity result
 * uses WorkManager backoff; logout, owner change, definitive auth rejection,
 * and local recovery requirements are terminal. The supplied operation is
 * never invoked unless the coordinator proved the expected owner and a live
 * access token exists.
 */
internal suspend fun runWithRestoredSession(
    expectedOwner: UserId,
    coordinator: SessionOwnerCoordinator,
    operation: suspend () -> ListenableWorker.Result,
): ListenableWorker.Result = when (coordinator.ensure(expectedOwner)) {
    SessionOwnerResolution.Ready -> operation()
    SessionOwnerResolution.Retryable -> ListenableWorker.Result.retry()
    SessionOwnerResolution.NoOwner,
    SessionOwnerResolution.AccountChanged,
    SessionOwnerResolution.Rejected,
    SessionOwnerResolution.RecoveryRequired,
    -> ListenableWorker.Result.failure()
}
