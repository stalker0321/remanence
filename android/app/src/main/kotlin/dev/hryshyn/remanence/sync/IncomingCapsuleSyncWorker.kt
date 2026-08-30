package dev.hryshyn.remanence.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.hryshyn.remanence.AppContainer
import dev.hryshyn.remanence.RemanenceApplication
import dev.hryshyn.remanence.core.data.db.IncomingSyncFailure
import dev.hryshyn.remanence.core.data.db.IncomingSyncResult
import dev.hryshyn.remanence.core.model.UserId
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.TimeUnit

/** Maximum number of pages one foreground/background invocation may commit. */
internal const val MAX_PAGES_PER_RUN = 10

/** WorkManager's exponential retry is platform-capped; this is its explicit initial delay. */
internal const val INCOMING_SYNC_BACKOFF_INITIAL_MILLIS = 30_000L

/** Account-scoped incoming page worker and its one-time request builder. */
class IncomingCapsuleSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val owner = parseOwner(inputData.getString(INPUT_OWNER_USER_ID)) ?: return Result.failure()
        val application = applicationContext as? RemanenceApplication ?: return Result.failure()
        val container = application.container
        return mapCombinedOutcome(
            IncomingSyncAndAcceptanceRunner(
                currentOwner = {
                    container.currentAccountStore.load()?.userId?.let { raw ->
                        runCatching { UserId.parseRest(raw) }.getOrNull()
                    }
                },
                syncNextPage = {
                    container.incomingCapsuleSyncRepository.syncNextPage(expectedOwner = owner)
                },
                runAcceptance = { expectedOwner ->
                    acceptanceDrainForWorker(container).run(expectedOwner)
                },
            ).run(owner),
        )
    }

    companion object {
        const val INPUT_OWNER_USER_ID = "owner_user_id"

        /** Builds a request containing only the immutable owner identity. */
        fun request(owner: UserId): OneTimeWorkRequest {
            val identity = AccountWorkIdentity.incomingSync(owner)
            return OneTimeWorkRequestBuilder<IncomingCapsuleSyncWorker>()
                .setInputData(workDataOf(INPUT_OWNER_USER_ID to owner.toRestString()))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    INCOMING_SYNC_BACKOFF_INITIAL_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .also { builder -> identity.tags.forEach(builder::addTag) }
                .build()
        }

        /** Enqueues exactly one account-scoped KEEP chain. */
        fun enqueue(workManager: WorkManager, owner: UserId): Operation =
            workManager.enqueueUniqueWork(
                AccountWorkIdentity.incomingSync(owner).uniqueName,
                ExistingWorkPolicy.KEEP,
                request(owner),
            )

        internal fun mapOutcome(outcome: IncomingSyncRunOutcome): ListenableWorker.Result =
            when (outcome) {
                is IncomingSyncRunOutcome.Succeeded -> ListenableWorker.Result.success()
                IncomingSyncRunOutcome.PageCapReached,
                is IncomingSyncRunOutcome.Retryable,
                -> ListenableWorker.Result.retry()
                is IncomingSyncRunOutcome.Terminal -> ListenableWorker.Result.failure()
            }

        internal fun mapCombinedOutcome(
            outcome: IncomingSyncAndAcceptanceRunOutcome,
        ): ListenableWorker.Result = when (outcome) {
            IncomingSyncAndAcceptanceRunOutcome.Succeeded -> ListenableWorker.Result.success()
            IncomingSyncAndAcceptanceRunOutcome.Retryable -> ListenableWorker.Result.retry()
            IncomingSyncAndAcceptanceRunOutcome.Terminal -> ListenableWorker.Result.failure()
        }

        /** The worker's only acceptance dependency: the container's real lazy drain. */
        internal fun acceptanceDrainForWorker(container: AppContainer): IncomingAcceptanceDrain =
            container.incomingAcceptanceDrain

        private fun parseOwner(raw: String?): UserId? = raw?.let {
            runCatching { UserId.parseRest(it) }.getOrNull()
        }
    }
}

/** One worker invocation: finish page sync, then make one bounded acceptance attempt. */
internal class IncomingSyncAndAcceptanceRunner(
    private val currentOwner: suspend () -> UserId?,
    private val syncNextPage: suspend () -> IncomingSyncResult,
    private val runAcceptance: suspend (UserId) -> IncomingAcceptanceDrainResult,
    private val maxPagesPerRun: Int = MAX_PAGES_PER_RUN,
) {

    suspend fun run(owner: UserId): IncomingSyncAndAcceptanceRunOutcome {
        val pageOutcome = IncomingSyncPageLoop(
            currentOwner = currentOwner,
            syncNextPage = syncNextPage,
            maxPagesPerRun = maxPagesPerRun,
        ).run(owner)

        if (pageOutcome !is IncomingSyncRunOutcome.Succeeded) {
            return when (pageOutcome) {
                IncomingSyncRunOutcome.PageCapReached,
                is IncomingSyncRunOutcome.Retryable,
                -> IncomingSyncAndAcceptanceRunOutcome.Retryable
                is IncomingSyncRunOutcome.Terminal -> IncomingSyncAndAcceptanceRunOutcome.Terminal
                is IncomingSyncRunOutcome.Succeeded -> error("unreachable")
            }
        }

        when (readCurrentOwner(owner)) {
            IncomingSyncAndAcceptanceOwnerStatus.Stopped ->
                return IncomingSyncAndAcceptanceRunOutcome.Terminal
            IncomingSyncAndAcceptanceOwnerStatus.Unavailable ->
                return IncomingSyncAndAcceptanceRunOutcome.Retryable
            IncomingSyncAndAcceptanceOwnerStatus.Current -> Unit
        }

        return when (val acceptance = runAcceptance(owner)) {
            is IncomingAcceptanceDrainResult.Completed -> {
                if (acceptance.pageMayHaveMore && acceptance.progressCount > 0) {
                    IncomingSyncAndAcceptanceRunOutcome.Retryable
                } else {
                    IncomingSyncAndAcceptanceRunOutcome.Succeeded
                }
            }
            is IncomingAcceptanceDrainResult.Retryable ->
                IncomingSyncAndAcceptanceRunOutcome.Retryable
            IncomingAcceptanceDrainResult.AccountStopped ->
                IncomingSyncAndAcceptanceRunOutcome.Terminal
        }
    }

    private suspend fun readCurrentOwner(expected: UserId): IncomingSyncAndAcceptanceOwnerStatus = try {
        when (currentOwner()) {
            expected -> IncomingSyncAndAcceptanceOwnerStatus.Current
            null -> IncomingSyncAndAcceptanceOwnerStatus.Stopped
            else -> IncomingSyncAndAcceptanceOwnerStatus.Stopped
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        IncomingSyncAndAcceptanceOwnerStatus.Unavailable
    }
}

private enum class IncomingSyncAndAcceptanceOwnerStatus {
    Current,
    Stopped,
    Unavailable,
}

internal enum class IncomingSyncAndAcceptanceRunOutcome {
    Succeeded,
    Retryable,
    Terminal,
}

/** Explicit result of one bounded incoming page loop, without WorkManager types. */
internal sealed interface IncomingSyncRunOutcome {
    data class Succeeded(val pagesProcessed: Int) : IncomingSyncRunOutcome

    data object PageCapReached : IncomingSyncRunOutcome

    data class Retryable(val reason: IncomingSyncFailure) : IncomingSyncRunOutcome

    data class Terminal(val reason: IncomingSyncFailure) : IncomingSyncRunOutcome
}

/**
 * Repeatedly invokes the existing one-page repository. Cursor continuation is
 * read from and committed by Room inside that repository; this loop never
 * carries a cursor in WorkData or invents a second persistence mechanism.
 */
internal class IncomingSyncPageLoop(
    private val currentOwner: suspend () -> UserId?,
    private val syncNextPage: suspend () -> IncomingSyncResult,
    private val maxPagesPerRun: Int = MAX_PAGES_PER_RUN,
) {

    init {
        require(maxPagesPerRun > 0) { "incoming sync page cap must be positive" }
    }

    suspend fun run(owner: UserId): IncomingSyncRunOutcome {
        var pagesProcessed = 0
        while (true) {
            when (val ownerFailure = ownerFailure(owner)) {
                null -> Unit
                else -> return ownerFailure
            }

            val result = try {
                syncNextPage()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                return IncomingSyncRunOutcome.Terminal(IncomingSyncFailure.VALIDATION_FAILED)
            } catch (_: Exception) {
                return IncomingSyncRunOutcome.Retryable(IncomingSyncFailure.DATABASE_FAILURE)
            }

            when (result) {
                is IncomingSyncResult.Failure -> {
                    return if (result.retryable) {
                        IncomingSyncRunOutcome.Retryable(result.reason)
                    } else {
                        IncomingSyncRunOutcome.Terminal(result.reason)
                    }
                }

                is IncomingSyncResult.Committed -> {
                    pagesProcessed += 1
                    if (!result.hasMore) {
                        return IncomingSyncRunOutcome.Succeeded(pagesProcessed)
                    }
                    if (pagesProcessed >= maxPagesPerRun) {
                        return IncomingSyncRunOutcome.PageCapReached
                    }
                }
            }
        }
    }

    private suspend fun ownerFailure(owner: UserId): IncomingSyncRunOutcome? {
        val liveOwner = try {
            currentOwner()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return IncomingSyncRunOutcome.Retryable(IncomingSyncFailure.DATABASE_FAILURE)
        }
        return when {
            liveOwner == owner -> null
            liveOwner == null -> IncomingSyncRunOutcome.Terminal(IncomingSyncFailure.NO_ACTIVE_SESSION)
            else -> IncomingSyncRunOutcome.Terminal(IncomingSyncFailure.ACCOUNT_CHANGED)
        }
    }
}
