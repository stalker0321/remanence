package dev.hryshyn.remanence.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.hryshyn.remanence.RemanenceApplication
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId

/** WorkManager adapter for one immutable `(account, capsule)` upload scope. */
class CapsuleUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val owner = parseUser(inputData.getString(INPUT_OWNER_USER_ID)) ?: return Result.failure()
        val capsule = parseCapsule(inputData.getString(INPUT_CAPSULE_ID)) ?: return Result.failure()
        val application = applicationContext as? RemanenceApplication ?: return Result.failure()
        return when (application.container.capsuleUploadOrchestrator.run(owner, capsule)) {
            CapsuleUploadOutcome.Succeeded,
            CapsuleUploadOutcome.Missing,
            -> Result.success()
            CapsuleUploadOutcome.AccountMismatch -> Result.failure()
            is CapsuleUploadOutcome.Retryable -> Result.retry()
            is CapsuleUploadOutcome.TerminalFailure -> Result.failure()
        }
    }

    companion object {
        const val INPUT_OWNER_USER_ID = "owner_user_id"
        const val INPUT_CAPSULE_ID = "capsule_id"

        /** Builds the canonical account/capsule-scoped request without embedding secrets or paths. */
        fun request(owner: UserId, capsule: CapsuleId): OneTimeWorkRequest {
            val identity = AccountWorkIdentity.outbox(owner, capsule)
            return OneTimeWorkRequestBuilder<CapsuleUploadWorker>()
                .setInputData(
                    workDataOf(
                        INPUT_OWNER_USER_ID to owner.toRestString(),
                        INPUT_CAPSULE_ID to capsule.toRestString(),
                    ),
                )
                .also { builder -> identity.tags.forEach(builder::addTag) }
                .build()
        }

        /** Enqueues exactly one KEEP chain for this authenticated account/capsule pair. */
        fun enqueue(workManager: WorkManager, owner: UserId, capsule: CapsuleId): Operation =
            workManager.enqueueUniqueWork(
                AccountWorkIdentity.outbox(owner, capsule).uniqueName,
                ExistingWorkPolicy.KEEP,
                request(owner, capsule),
            )

        private fun parseUser(raw: String?): UserId? = raw?.let {
            runCatching { UserId.parseRest(it) }.getOrNull()
        }

        private fun parseCapsule(raw: String?): CapsuleId? = raw?.let {
            runCatching { CapsuleId.parseRest(it) }.getOrNull()
        }
    }
}
