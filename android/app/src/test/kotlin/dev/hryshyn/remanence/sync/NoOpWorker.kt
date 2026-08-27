package dev.hryshyn.remanence.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * M2-P05 test fixture: a worker that does nothing. The real outbox and
 * incoming-sync workers are not implemented yet; this fixture exists
 * only so the cancellation tests have a [androidx.work.WorkRequest] type
 * to enqueue. The cancellation tests never let it run, so its body is
 * intentionally trivial.
 */
internal class NoOpWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = Result.success()
}
