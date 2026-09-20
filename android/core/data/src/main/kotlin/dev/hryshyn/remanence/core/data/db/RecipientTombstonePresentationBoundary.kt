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
    private data class PresentationKey(val owner: UUID, val capsule: UUID)
    private class CallbackBucket {
        val lock = Any()
        val callbacks = LinkedHashSet<() -> Unit>()
        var invalidated = false
    }

    private val preparedRevocations = ConcurrentHashMap<PresentationKey, CallbackBucket>()

    /** Test-only barrier; production leaves this null. */
    internal var onAfterCallbackBucketLookup: (() -> Unit)? = null

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

    /** Registers a prepared handle until it is closed or tombstone-invalidated. */
    fun registerPrepared(
        ownerUserId: UserId,
        capsuleId: CapsuleId,
        onRevoked: () -> Unit,
    ): AutoCloseable {
        val key = PresentationKey(ownerUserId.value, capsuleId.value)
        while (true) {
            val bucket = preparedRevocations.computeIfAbsent(key) { CallbackBucket() }
            onAfterCallbackBucketLookup?.invoke()
            val registered = synchronized(bucket) {
                if (bucket.invalidated || preparedRevocations[key] !== bucket) {
                    false
                } else {
                    bucket.callbacks += onRevoked
                    true
                }
            }
            if (!registered) continue
            return AutoCloseable {
                synchronized(bucket) {
                    bucket.callbacks.remove(onRevoked)
                    if (bucket.callbacks.isEmpty()) {
                        preparedRevocations.remove(key, bucket)
                    }
                }
            }
        }
    }

    /** Called only after the durable tombstone transaction has committed. */
    fun invalidatePrepared(ownerUserId: UserId, capsuleIds: Collection<CapsuleId>) {
        capsuleIds.forEach { capsuleId ->
            val key = PresentationKey(ownerUserId.value, capsuleId.value)
            val bucket = preparedRevocations[key] ?: return@forEach
            val callbacks = synchronized(bucket) {
                if (preparedRevocations[key] !== bucket) {
                    emptyList()
                } else {
                    bucket.invalidated = true
                    preparedRevocations.remove(key, bucket)
                    bucket.callbacks.toList().also { bucket.callbacks.clear() }
                }
            }
            callbacks.forEach { callback ->
                runCatching { callback() }
            }
        }
    }

    private suspend fun <T> withOwner(
        ownerUserId: UserId,
        block: suspend () -> T,
    ): T {
        val lock = ownerLocks.computeIfAbsent(ownerUserId.value) { Mutex() }
        return lock.withLock { block() }
    }
}
