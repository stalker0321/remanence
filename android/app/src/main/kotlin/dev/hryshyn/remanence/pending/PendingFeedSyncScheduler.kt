package dev.hryshyn.remanence.pending

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * T186 M14a: receiver-owned feed sync scheduler. Sync never blocks
 * any viewer: callers schedule best-effort background syncs; the
 * worker drains a bounded number of pages per run and reschedules
 * while the feed is not exhausted. Network/session failure only
 * reschedules — never surfaces.
 *
 * All production dependencies come from [PendingFeedSyncHost],
 * implemented by the application container. No blind casts: the only
 * fallback is doing nothing when no host is attached (unit tests
 * drive [PendingFeedSync] explicitly).
 */
object PendingFeedSyncScheduler {

    const val UNIQUE_WORK = "pending-feed-sync"

    /** Bounded pages per background run (unbounded drain stays a foreground loop). */
    const val MAX_PAGES_PER_RUN = 5

    fun schedule(context: Context) {
        if (!PendingFeatureGate.isEnabled()) return
        val request = OneTimeWorkRequestBuilder<PendingFeedSyncWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK)
            .build()
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        } catch (_: Exception) {
            // Scheduler unavailable (tests): sync is driven explicitly.
        }
    }
}

/** Production seam implemented by the application container. */
interface PendingFeedSyncHost {
    val pendingFeedContext: Context
    val pendingFeedGate: PendingRetirementSessionGate?
    val pendingFeedAccount: (suspend () -> ProofAccount?)
    val pendingFeedCursorStore: PendingFeedCursorStore?
    val pendingFeedFetch: PendingFeedSync.FetchPort?
}

/** Background owned-feed sync: bounded pages, then reschedule or stop. */
class PendingFeedSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!PendingFeatureGate.isEnabled()) return Result.success()
        val host = try {
            (applicationContext as? dev.hryshyn.remanence.RemanenceApplication)?.container
                as? PendingFeedSyncHost
        } catch (_: Exception) {
            null
        } ?: return Result.success()
        val gate = host.pendingFeedGate ?: return Result.success()
        val store = host.pendingFeedCursorStore ?: return Result.success()
        val fetch = host.pendingFeedFetch ?: return Result.success()
        val sync = PendingFeedSync(
            fetchPort = fetch,
            cursorStore = store,
            sessionGate = gate,
            accountProvider = host.pendingFeedAccount,
            scope = null,
        )
        return try {
            var exhausted = true
            for (_page in 0 until PendingFeedSyncScheduler.MAX_PAGES_PER_RUN) {
                when (val state = sync.sync()) {
                    is PendingFeedSync.State.Synced -> {
                        exhausted = state.exhausted
                        if (exhausted) break
                    }
                    is PendingFeedSync.State.Failed -> return Result.retry()
                    else -> Unit
                }
            }
            if (exhausted) {
                Result.success()
            } else {
                PendingFeedSyncScheduler.schedule(applicationContext)
                Result.success()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
