package dev.hryshyn.remanence.session

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority

/**
 * FIX-REVIEW2-03: ONE lifecycle-bound timer per presented grant. It sleeps
 * until the grant's EXACT expiry instant (no polling loop), wakes, and asks
 * THE owner-bound [PresentationGrantAuthority] to decide; a dead or expired grant
 * revokes the presentation through [onRevoked]. The owning scope is the root
 * ViewModel's, so process death and logout kill the timer with the context,
 * and every explicit close/exit cancels it - no leaked timers.
 */
internal class CapsuleExpiryWatch(
    private val scope: CoroutineScope,
    private val grants: PresentationGrantAuthority,
    private val currentOwner: () -> UserId?,
    private val clockMillis: () -> Long,
    private val onRevoked: (grantId: String) -> Unit,
) {

    private val lock = Any()
    private var job: Job? = null
    private var watchedGrantId: String? = null

    /**
     * Arms (or re-arms, e.g. after a rotation rebuilt the route) the watch
     * for THIS grant; at most one pending timer exists at any time.
     */
    fun watch(grantId: String) {
        cancel()
        val uuid = runCatching { UUID.fromString(grantId) }.getOrNull() ?: return
        val owner = currentOwner() ?: run {
            onRevoked(grantId)
            return
        }
        synchronized(lock) {
            watchedGrantId = grantId
        }
        val newJob = scope.launch {
            val expiresAt = grants.expiresAtMillis(uuid, owner)
            if (expiresAt == null) {
                // Dead before we even started: nothing may stay presented.
                if (claimForRevocation(grantId)) onRevoked(grantId)
                return@launch
            }
            val remaining = expiresAt - clockMillis()
            if (remaining > 0) delay(remaining)
            // Authoritative re-check after the wake-up: close/consume may have
            // happened meanwhile; only an actually-dead grant revokes.
            val liveOwner = currentOwner()
            if ((liveOwner == null || grants.resolve(uuid, liveOwner) == null) &&
                claimForRevocation(grantId)
            ) {
                onRevoked(grantId)
            }
        }
        synchronized(lock) {
            if (watchedGrantId == grantId) {
                job = newJob
            } else {
                // The coroutine may have discovered a dead grant and revoked
                // it synchronously before launch returned.
                newJob.cancel()
            }
        }
    }

    /** Cancels any pending expiry timer without touching the grant itself. */
    fun cancel() {
        synchronized(lock) {
            cancelLocked()
        }
    }

    /** Cancels only the timer still owned by [grantId]; never a replacement. */
    fun cancel(grantId: String) {
        synchronized(lock) {
            if (watchedGrantId == grantId) cancelLocked()
        }
    }

    private fun claimForRevocation(grantId: String): Boolean = synchronized(lock) {
        if (watchedGrantId != grantId) return@synchronized false
        watchedGrantId = null
        job = null
        true
    }

    private fun cancelLocked() {
        job?.cancel()
        job = null
        watchedGrantId = null
    }
}
