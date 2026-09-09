package dev.hryshyn.remanence.sync

import dev.hryshyn.remanence.core.model.UserId
import kotlin.coroutines.cancellation.CancellationException
import java.util.HashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Internal control flow for a lease that expired before the KEEP enqueue. */
internal class IncomingScheduleLeaseRejected : Exception()

/** Safe, bounded result of asking WorkManager for the owner-scoped KEEP chain. */
sealed interface IncomingSyncSchedulingOutcome {
    val safeStatus: String

    /** The KEEP request was accepted; this does not claim a brand-new chain. */
    data object Queued : IncomingSyncSchedulingOutcome {
        override val safeStatus: String = "queued"
    }

    data class AlreadyWaiting(
        val state: ExistingIncomingWorkState,
    ) : IncomingSyncSchedulingOutcome {
        // This is an observation made before KEEP enqueue, not a claim that
        // the same WorkInfo is still active when enqueue completes.
        override val safeStatus: String = if (state == ExistingIncomingWorkState.ENQUEUED) {
            "KEEP accepted (observed enqueued; retry timing unknown)"
        } else {
            "KEEP accepted (observed ${state.safeName})"
        }
    }

    data object SessionOwnerRejected : IncomingSyncSchedulingOutcome {
        override val safeStatus: String = "session-owner rejected"
    }

    data object EnqueueFailed : IncomingSyncSchedulingOutcome {
        override val safeStatus: String = "enqueue failed"
    }
}

/** Only WorkManager states safe to expose as a bounded scheduling summary. */
enum class ExistingIncomingWorkState(val safeName: String) {
    ENQUEUED("enqueued"),
    RUNNING("running"),
    BLOCKED("blocked"),
}

/**
 * Serializes WorkInfo observation and KEEP enqueue per owner. Entries exist
 * only while callers are waiting or inside the critical section, so the map
 * is bounded by live owner activity and cannot retain every historical owner.
 */
internal class OwnerScopedIncomingScheduleLocks {
    private class Entry {
        val lock = Mutex()
        var users: Int = 0
    }

    private val entriesLock = Any()
    private val entries = HashMap<UserId, Entry>()

    suspend fun <T> withOwner(owner: UserId, block: suspend () -> T): T =
        acquire(owner).let { entry ->
            try {
                entry.lock.withLock { block() }
            } finally {
                synchronized(entriesLock) {
                    entry.users -= 1
                    if (entry.users == 0) entries.remove(owner)
                }
            }
        }

    internal fun activeOwnerCountForTests(): Int = synchronized(entriesLock) { entries.size }

    private fun acquire(owner: UserId): Entry = synchronized(entriesLock) {
        entries.getOrPut(owner) { Entry() }.also { it.users += 1 }
    }
}

/**
 * Snapshot is observability only. KEEP enqueue is always attempted after it,
 * including when the observed work was active, so a finish between query and
 * enqueue cannot discard the caller's retry request.
 */
internal class IncomingKeepScheduleOperation(
    private val observe: suspend () -> ExistingIncomingWorkState?,
    private val enqueue: suspend () -> Unit,
    private val awaitEnqueue: suspend () -> Unit = {},
) {
    internal sealed interface Preparation {
        data class Accepted(val observed: ExistingIncomingWorkState?) : Preparation
        data object SessionOwnerRejected : Preparation
        data object EnqueueFailed : Preparation
    }

    /**
     * Performs only observation and KEEP enqueue. Callers may complete the
     * returned accepted operation after releasing their owner lifecycle lock.
     */
    suspend fun prepare(): Preparation {
        val observed = try {
            observe()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return try {
            enqueue()
            Preparation.Accepted(observed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IncomingScheduleLeaseRejected) {
            Preparation.SessionOwnerRejected
        } catch (_: Exception) {
            Preparation.EnqueueFailed
        }
    }

    /** Awaits an accepted WorkManager operation outside the owner lock. */
    suspend fun complete(preparation: Preparation): IncomingSyncSchedulingOutcome =
        when (preparation) {
            is Preparation.Accepted -> try {
                awaitEnqueue()
                if (preparation.observed != null) {
                    IncomingSyncSchedulingOutcome.AlreadyWaiting(preparation.observed)
                } else {
                    IncomingSyncSchedulingOutcome.Queued
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                IncomingSyncSchedulingOutcome.EnqueueFailed
            }
            Preparation.SessionOwnerRejected -> IncomingSyncSchedulingOutcome.SessionOwnerRejected
            Preparation.EnqueueFailed -> IncomingSyncSchedulingOutcome.EnqueueFailed
        }

    suspend fun run(): IncomingSyncSchedulingOutcome = complete(prepare())
}
