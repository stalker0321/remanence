package dev.hryshyn.remanence.session

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local fence for account-bound UI work. Invalidation is synchronous
 * at the account boundary; listeners may then cancel their own suspending
 * work before the asynchronous logout teardown begins.
 */
internal class SessionBoundary {
    private val epoch = AtomicLong(0L)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun currentEpoch(): Long = epoch.get()

    fun register(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun invalidate() {
        epoch.incrementAndGet()
        listeners.toList().forEach { listener ->
            runCatching { listener() }
        }
    }
}
