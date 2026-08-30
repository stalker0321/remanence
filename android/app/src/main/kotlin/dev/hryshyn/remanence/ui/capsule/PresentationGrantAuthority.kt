package dev.hryshyn.remanence.ui.capsule

import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.ScanGrant
import dev.hryshyn.remanence.core.recognition.ScanGrantManager
import java.util.UUID

/** The presentation plane is independent from recognition provenance. */
enum class CapsulePresentationSource {
    INCOMING,
    OUTBOX,
}

/**
 * The complete in-memory binding for one presentation grant. The prepared
 * incoming handle is deliberately kept behind this internal authority; it
 * never enters navigation state or saved state.
 */
internal class PresentationGrantBinding internal constructor(
    val grantId: UUID,
    val ownerUserId: UserId,
    val capsuleId: UUID,
    val source: CapsulePresentationSource,
    val scanGeneration: Int,
    val incomingPresentation: PreparedIncomingPresentation?,
) {
    init {
        require((source == CapsulePresentationSource.INCOMING) == (incomingPresentation != null))
        require(incomingPresentation == null ||
            (incomingPresentation.ownerUserId == ownerUserId &&
                incomingPresentation.capsuleId.value == capsuleId))
    }

    override fun toString(): String = "PresentationGrantBinding(<redacted>)"
}

/**
 * App-scoped owner of the memory-only presentation binding. Scan creates a
 * binding after verification; Root and route lifecycle events revoke it. The
 * underlying recognition manager remains the expiry/one-active-grant clock
 * authority, while this layer owns the exact incoming prepared material.
 */
internal class PresentationGrantAuthority(
    private val grants: ScanGrantManager = ScanGrantManager(
        clockMillis = System::currentTimeMillis,
    ),
) {
    private val lock = Any()
    private var active: PresentationGrantBinding? = null
    private var contextEpoch: Long = 0L

    /** Snapshot taken before scan preparation; clearAll invalidates it. */
    fun currentEpoch(): Long = synchronized(lock) { contextEpoch }

    fun issue(
        ownerUserId: UserId,
        capsuleId: UUID,
        source: CapsulePresentationSource,
        scanGeneration: Int,
        expectedEpoch: Long = currentEpoch(),
        incomingPresentation: PreparedIncomingPresentation? = null,
    ): ScanGrant {
        require((source == CapsulePresentationSource.INCOMING) == (incomingPresentation != null))
        require(incomingPresentation == null ||
            (incomingPresentation.ownerUserId == ownerUserId &&
                incomingPresentation.capsuleId.value == capsuleId))

        synchronized(lock) {
            if (expectedEpoch != contextEpoch) {
                closeQuietly(incomingPresentation)
                throw IllegalStateException("presentation context is no longer current")
            }
            clearActiveLocked()
            val grant = try {
                grants.issue(capsuleId)
            } catch (failure: Throwable) {
                closeQuietly(incomingPresentation)
                throw failure
            }
            active = PresentationGrantBinding(
                grantId = grant.grantId,
                ownerUserId = ownerUserId,
                capsuleId = capsuleId,
                source = source,
                scanGeneration = scanGeneration,
                incomingPresentation = incomingPresentation,
            )
            return grant
        }
    }

    /** Returns a live binding only for the exact authenticated owner. */
    fun resolve(grantId: UUID, ownerUserId: UserId): PresentationGrantBinding? =
        synchronized(lock) {
            val binding = active
            if (binding == null || binding.grantId != grantId) {
                return@synchronized null
            }
            if (binding.ownerUserId != ownerUserId) {
                clearActiveLocked()
                return@synchronized null
            }
            if (grants.resolveCapsuleId(grantId) != binding.capsuleId) {
                clearActiveLocked()
                return@synchronized null
            }
            binding
        }

    /** Returns expiry only through the complete owner-bound authority. */
    fun expiresAtMillis(grantId: UUID, ownerUserId: UserId): Long? =
        synchronized(lock) {
            val binding = active
            if (binding == null || binding.grantId != grantId) return@synchronized null
            if (binding.ownerUserId != ownerUserId) {
                clearActiveLocked()
                return@synchronized null
            }
            val expiresAt = grants.expiresAtMillis(grantId)
            if (expiresAt == null) clearActiveLocked()
            expiresAt
        }

    /** Revokes one grant and closes its incoming material, regardless of why. */
    fun revoke(grantId: UUID): Boolean = synchronized(lock) {
        val binding = active
        if (binding?.grantId == grantId) {
            active = null
            grants.consume(grantId)
            closeQuietly(binding.incomingPresentation)
            true
        } else {
            grants.consume(grantId)
        }
    }

    /** Invalidates every grant before account/session teardown or scan reset. */
    fun clearAll() = synchronized(lock) {
        contextEpoch += 1
        clearActiveLocked()
        grants.clearAll()
    }

    internal fun activeForTests(): PresentationGrantBinding? = synchronized(lock) { active }

    private fun clearActiveLocked() {
        val binding = active ?: return
        active = null
        grants.consume(binding.grantId)
        closeQuietly(binding.incomingPresentation)
    }

    private fun closeQuietly(presentation: PreparedIncomingPresentation?) {
        try {
            presentation?.close()
        } catch (_: Throwable) {
            // Revocation must never be replaced by cleanup failure.
        }
    }
}
