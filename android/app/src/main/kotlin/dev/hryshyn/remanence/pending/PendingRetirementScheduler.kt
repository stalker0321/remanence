package dev.hryshyn.remanence.pending

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * T186: background retirement scheduler. Content display never waits: callers
 * use [enqueueAfterPresentation] (fire-and-forget persistence on the
 * application-owned scope) and return to the viewer immediately; WorkManager
 * retries the durable outbox with backoff. Network/session failure only
 * reschedules — never surfaces into the viewer.
 *
 * All production dependencies come from [PendingRetirementHost], implemented
 * by the application container (real session gate, owner, scope, port). No
 * blind `applicationContext as? Port` casts: the only fallback is the
 * fail-closed unconfigured port, used solely when no host is attached
 * (unit tests).
 */
object PendingRetirementScheduler {

    const val UNIQUE_WORK = "pending-retirement-drain"

    /**
     * Persists the retirement intent on the application-owned scope, then
     * schedules a background drain. Never throws into the presentation path:
     * persistence failure degrades to a best-effort schedule attempt.
     */
    fun enqueueAfterPresentation(
        host: PendingRetirementHost,
        ownerUserId: String,
        capsuleId: String,
        publicationHash: String? = null,
    ) {
        if (!PendingFeatureGate.isEnabled()) return
        if (ownerUserId.isBlank() || capsuleId.isBlank()) return
        host.pendingAppScope.launch {
            try {
                host.pendingOutbox.enqueue(ownerUserId, capsuleId, publicationHash)
            } catch (_: Exception) {
                // Durability failure must never break presentation.
            } finally {
                scheduleDrain(host.pendingContext)
            }
        }
    }

    fun scheduleDrain(context: Context) {
        if (!PendingFeatureGate.isEnabled()) return
        val request = OneTimeWorkRequestBuilder<PendingRetirementWorker>()
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
            // Scheduler unavailable (tests): drain is driven explicitly.
        }
    }
}

/**
 * Production seam implemented by the application container. The scope is
 * application-owned (outlives any screen); the gate/owner come from the real
 * session store; the port stays fail-closed until T185 M2/M3 mount R15.
 */
interface PendingRetirementHost {
    val pendingContext: Context
    val pendingAppScope: CoroutineScope
    val pendingOutbox: PendingRetirementOutbox
    val pendingSessionGate: PendingRetirementSessionGate?
    val pendingOwnerId: String?

    /**
     * T188-B2: the ACTUAL API base the app stack was constructed with
     * (not just the BuildConfig constant), so the isolation guard below
     * verifies the real endpoint before any byte is sent.
     */
    val pendingApiBaseUrl: String
}

/**
 * Durable drain worker. Reads the CURRENT authenticated owner from the host;
 * with no owner it exits (items stay queued for next login). Items of a
 * previous owner are never executed after account switch. Returns success
 * only when the queue is empty; any remaining item retries with backoff.
 */
class PendingRetirementWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!PendingFeatureGate.isEnabled()) return Result.success()
        val host = try {
            (applicationContext as? dev.hryshyn.remanence.RemanenceApplication)?.container
                as? PendingRetirementHost
        } catch (_: Exception) {
            null
        } ?: return Result.success()
        // T188-B2: guard the ACTUAL constructed API base, not the
        // BuildConfig constant. Misconfigured → keep queued, send nothing.
        val baseUrl = try {
            host.pendingApiBaseUrl
        } catch (_: Exception) {
            ""
        }
        if (baseUrl.isBlank() || !PendingFeatureGate.isIsolatedApiAccepted(baseUrl)) {
            return Result.success()
        }
        val owner = try {
            host.pendingOwnerId?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } ?: return Result.success()
        return try {
            // T188-B3: a corrupt queue reports fault (quarantined aside),
            // which rearms via backoff retry — never empty-success, and the
            // failed intent stays queued. No UI thread involved.
            val outcome = host.pendingOutbox.drainDetailed(owner, host.pendingSessionGate)
            if (!outcome.fault && outcome.remaining == 0) Result.success()
            else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
