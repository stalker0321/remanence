package postmark.core.recognition

import java.util.UUID

/**
 * M1-M10 memory-only scan grant (docs/architecture.md section 5): the
 * capsule screen accepts a random grant ID, never a capsule ID. The manager
 * holds `{grant_id, capsule_id, issued_at, expires_at}` purely in process
 * memory - nothing here persists, so process death invalidates every grant.
 * Exactly one grant is live at a time; issuing a new one invalidates any
 * previous grant. Time comes from an injected clock so tests can move it.
 */
class ScanGrantManager(
    private val clockMillis: () -> Long,
    private val grantLifetimeMillis: Long = DEFAULT_GRANT_LIFETIME_MILLIS,
    private val idGenerator: () -> UUID = UUID::randomUUID,
) {

    init {
        require(grantLifetimeMillis > 0) { "grant lifetime must be positive" }
    }

    private var active: ActiveGrant? = null

    /**
     * Issues the grant for a successfully scanned capsule, replacing any
     * previous grant (there is never more than one live navigation entry).
     */
    fun issue(capsuleId: UUID): ScanGrant {
        val now = clockMillis()
        val grant = ScanGrant(
            grantId = idGenerator(),
            capsuleId = capsuleId,
            issuedAtEpochMillis = now,
            expiresAtEpochMillis = now + grantLifetimeMillis,
        )
        active = ActiveGrant(grant.grantId, grant)
        return grant
    }

    /**
     * Resolves a grant ID to its capsule ID while unexpired. An expired or
     * unknown grant is invalidated and returns null - callers must treat the
     * scan flow as finished.
     */
    fun resolveCapsuleId(grantId: UUID): UUID? {
        val current = active ?: return null
        if (current.grant.grantId != grantId) return null
        if (clockMillis() >= current.grant.expiresAtEpochMillis) {
            active = null
            return null
        }
        return current.grant.capsuleId
    }

    /**
     * FIX-REVIEW2-03: exact expiry instant of the LIVE grant with this ID,
     * read from the same injected clock - or null when unknown, consumed, or
     * already expired (which also invalidates it). Lets presentation schedule
     * one lifecycle-bound timer to the deadline instead of polling.
     */
    fun expiresAtMillis(grantId: UUID): Long? {
        val current = active ?: return null
        if (current.grant.grantId != grantId) return null
        if (clockMillis() >= current.grant.expiresAtEpochMillis) {
            active = null
            return null
        }
        return current.grant.expiresAtEpochMillis
    }

    /** Consumes the grant when its screen is left; later resolves fail. */
    fun consume(grantId: UUID): Boolean {
        val current = active ?: return false
        if (current.grant.grantId != grantId) return false
        active = null
        return true
    }

    /** Logout / account-context teardown: forget everything immediately. */
    fun clearAll() {
        active = null
    }

    private data class ActiveGrant(
        val grantId: UUID,
        val grant: ScanGrant,
    )

    companion object {
        /** docs/architecture.md section 5: ten-minute default, one config site. */
        const val DEFAULT_GRANT_LIFETIME_MILLIS: Long = 10L * 60L * 1000L
    }
}

/** One issued in-memory grant; never persisted anywhere. */
data class ScanGrant(
    val grantId: UUID,
    val capsuleId: UUID,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)
