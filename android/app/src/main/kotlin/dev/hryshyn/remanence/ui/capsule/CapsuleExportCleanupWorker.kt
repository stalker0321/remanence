package dev.hryshyn.remanence.ui.capsule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Best-effort OS scheduled expiry; startup/foreground/logout also sweep. */
class CapsuleExportCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        CapsuleExportFiles.sweep(applicationContext)
        Result.success()
    } catch (_: java.io.IOException) {
        Result.retry()
    } catch (_: IllegalStateException) {
        Result.retry()
    }

    companion object {
        internal fun schedule(context: Context) {
            // A newer lease needs its own wakeup, even if an older one is pending.
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<CapsuleExportCleanupWorker>()
                    .setInitialDelay(CapsuleExportFiles.TTL_MILLIS, TimeUnit.MILLISECONDS)
                    .build(),
            )
        }
    }
}
