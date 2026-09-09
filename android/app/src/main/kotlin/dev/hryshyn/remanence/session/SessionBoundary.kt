package dev.hryshyn.remanence.session

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local fence for account-bound UI work. Invalidation is synchronous
 * at the account boundary; listeners may then cancel their own suspending
 * work before the asynchronous logout teardown begins.
 */
internal class SessionBoundary(
    private val withInvalidationFence: (((() -> Unit) -> Unit)) = { block -> block() },
) {
    private val epoch = AtomicLong(0L)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val lifecycleMonitor = Any()

    fun currentEpoch(): Long = epoch.get()

    fun register(listener: () -> Unit): () -> Unit {
        synchronized(lifecycleMonitor) {
            listeners += listener
        }
        return { synchronized(lifecycleMonitor) { listeners -= listener } }
    }

    fun invalidate() {
        val listenersToNotify = synchronized(lifecycleMonitor) {
            withInvalidationFence { epoch.incrementAndGet() }
            listeners.toList()
        }
        listenersToNotify.forEach { listener ->
            runCatching { listener() }
        }
    }

    /**
     * Linearizes a short synchronous owner-bound action with invalidate().
     * Callers must not suspend or perform network/slow I/O in [block].
     */
    internal fun <T> withCurrentLease(expectedEpoch: Long, block: () -> T): T? =
        synchronized(lifecycleMonitor) {
            if (epoch.get() != expectedEpoch) null else block()
        }
}
