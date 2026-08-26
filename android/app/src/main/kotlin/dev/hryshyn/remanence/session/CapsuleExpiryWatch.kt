package dev.hryshyn.remanence.session

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.hryshyn.remanence.core.recognition.ScanGrantManager

/**
 * FIX-REVIEW2-03: ONE lifecycle-bound timer per presented grant. It sleeps
 * until the grant's EXACT expiry instant (no polling loop), wakes, and asks
 * THE authoritative [ScanGrantManager] to decide; a dead or expired grant
 * revokes the presentation through [onRevoked]. The owning scope is the root
 * ViewModel's, so process death and logout kill the timer with the context,
 * and every explicit close/exit cancels it - no leaked timers.
 */
class CapsuleExpiryWatch(
    private val scope: CoroutineScope,
    private val grants: ScanGrantManager,
    private val clockMillis: () -> Long,
    private val onRevoked: (grantId: String) -> Unit,
) {

    private var job: Job? = null

    /**
     * Arms (or re-arms, e.g. after a rotation rebuilt the route) the watch
     * for THIS grant; at most one pending timer exists at any time.
     */
    fun watch(grantId: String) {
        cancel()
        val uuid = runCatching { UUID.fromString(grantId) }.getOrNull() ?: return
        job = scope.launch {
            val expiresAt = grants.expiresAtMillis(uuid)
            if (expiresAt == null) {
                // Dead before we even started: nothing may stay presented.
                onRevoked(grantId)
                return@launch
            }
            val remaining = expiresAt - clockMillis()
            if (remaining > 0) delay(remaining)
            // Authoritative re-check after the wake-up: close/consume may have
            // happened meanwhile; only an actually-dead grant revokes.
            if (grants.resolveCapsuleId(uuid) == null) {
                onRevoked(grantId)
            }
        }
    }

    /** Cancels any pending expiry timer without touching the grant itself. */
    fun cancel() {
        job?.cancel()
        job = null
    }
}
