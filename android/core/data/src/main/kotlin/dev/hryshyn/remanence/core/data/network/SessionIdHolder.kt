package dev.hryshyn.remanence.core.data.network

import dev.hryshyn.remanence.core.model.UserId

/**
 * T186 M7c: minimal fenced auth session-id storage (memory-only).
 *
 * The login/refresh `session_id` is needed for R11 claim intents but is
 * not part of the sealed refresh record. This holder keeps it in process
 * memory, bound to its owner: readers must present the live lease owner
 * ([currentFor]), so a rotated/switched account never borrows another
 * session id. Cleared on logout. Survives nothing by design — re-login
 * re-establishes it; refresh rotations that change the id server-side
 * fail closed at claim time (session mismatch) until re-login.
 */
class SessionIdHolder {
    @Volatile
    private var bound: Pair<UserId, String>? = null

    fun set(ownerUserId: UserId, sessionId: String) {
        if (sessionId.isBlank()) return
        bound = ownerUserId to sessionId
    }

    /** Returns the id only when bound to [ownerUserId]. */
    fun currentFor(ownerUserId: UserId): String? =
        bound?.takeIf { it.first == ownerUserId }?.second

    fun clear() {
        bound = null
    }

    override fun toString(): String = "SessionIdHolder(<redacted>)"
}
