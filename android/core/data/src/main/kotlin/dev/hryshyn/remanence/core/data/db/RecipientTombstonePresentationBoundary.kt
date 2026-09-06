package dev.hryshyn.remanence.core.data.db

import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-process owner boundary shared by tombstone application and presentation
 * handoff. A tombstone page and a future presentation for the same account
 * cannot cross the final database-state check and handoff boundary.
 *
 * The lock is deliberately owner-scoped: accounts do not block each other,
 * while all capsules in one page use the same deterministic account lock.
 * Room still supplies the durable transaction; this boundary supplies the
 * same-process ordering between that transaction and an offline open.
 */
class RecipientTombstonePresentationBoundary {
    private val ownerLocks = ConcurrentHashMap<UUID, Mutex>()

    suspend fun <T> withCapsule(
        ownerUserId: UserId,
        _capsuleId: CapsuleId,
        block: suspend () -> T,
    ): T = withOwner(ownerUserId, block)

    suspend fun <T> withCapsules(
        ownerUserId: UserId,
        _capsuleIds: Collection<CapsuleId>,
        block: suspend () -> T,
    ): T = withOwner(ownerUserId, block)

    private suspend fun <T> withOwner(
        ownerUserId: UserId,
        block: suspend () -> T,
    ): T {
        val lock = ownerLocks.computeIfAbsent(ownerUserId.value) { Mutex() }
        return lock.withLock { block() }
    }
}
