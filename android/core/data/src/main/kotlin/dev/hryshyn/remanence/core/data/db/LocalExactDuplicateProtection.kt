package dev.hryshyn.remanence.core.data.db

import androidx.room.withTransaction
import java.util.UUID

/** The losing same-owner reservation reports this stable local policy error. */
class ExactDuplicateBlockedException : IllegalStateException("exact duplicate blocked")

/** A durable handle that can be committed atomically with the outbox rows. */
class ExactDuplicateReservation internal constructor(
    val reservationId: String,
    val ownerUserId: String,
    val frontSha256: ByteArray,
    val capsuleId: String,
)

/**
 * Offline, owner-local exact duplicate protection for the sender's captured
 * FRONT. Cleanup is deliberately owned by this service: every reservation
 * prunes expired rows, and every successful commit enforces the recent-count
 * bound. There is no global index or background worker.
 */
class LocalExactDuplicateProtection(
    private val database: RemanenceLocalDatabase,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val retentionWindowMs: Long = DEFAULT_RETENTION_WINDOW_MS,
    private val maxRecentCount: Int = DEFAULT_MAX_RECENT_COUNT,
    private val reservationLeaseMs: Long = DEFAULT_RESERVATION_LEASE_MS,
) {

    init {
        require(retentionWindowMs > 0) { "retention window must be positive" }
        require(maxRecentCount > 0) { "recent count must be positive" }
        require(reservationLeaseMs > 0) { "reservation lease must be positive" }
    }

    /** Atomically wins the owner+digest reservation or throws exact-block. */
    suspend fun reserve(
        ownerUserId: String,
        capsuleId: String,
        frontSha256: ByteArray,
    ): ExactDuplicateReservation = database.withTransaction {
        require(ownerUserId.isNotBlank()) { "owner account id is required" }
        val canonicalOwner = runCatching { UUID.fromString(ownerUserId) }.getOrNull()
        require(canonicalOwner != null && canonicalOwner.toString() == ownerUserId) {
            "owner account id must be a canonical UUID string"
        }
        require(capsuleId.isNotBlank()) { "capsule id is required" }
        require(frontSha256.size == SHA256_BYTES) { "FRONT identity must be a SHA-256 digest" }
        val now = nowEpochMs()
        val dao = database.localSendDuplicateDao()
        cleanupExpiredInTransaction(dao, ownerUserId, now)
        val reservation = ExactDuplicateReservation(
            reservationId = UUID.randomUUID().toString(),
            ownerUserId = ownerUserId,
            frontSha256 = frontSha256.copyOf(),
            capsuleId = capsuleId,
        )
        val inserted = dao.insertIfAbsent(
            LocalSendDuplicateEntity(
                reservationId = reservation.reservationId,
                ownerUserId = ownerUserId,
                frontSha256 = reservation.frontSha256,
                capsuleId = capsuleId,
                state = STATE_RESERVED,
                createdAtEpochMs = now,
                reservationExpiresAtEpochMs = now + reservationLeaseMs,
            ),
        )
        // Room returns SQLite's newly inserted row id, not a row-count; every
        // successful insert may therefore have a different positive value.
        if (inserted == -1L) throw ExactDuplicateBlockedException()
        reservation
    }

    /** Convenience form for callers that do not need outbox atomicity. */
    suspend fun commit(reservation: ExactDuplicateReservation): Boolean = database.withTransaction {
        commitReservedInTransaction(reservation)
    }

    /**
     * Commits the reservation while the caller's Room transaction is open.
     * The outbox stager uses this to make the exact-history row and ciphertext
     * row set one atomic durable decision.
     */
    internal suspend fun commitReservedInTransaction(reservation: ExactDuplicateReservation): Boolean {
        val committed = database.localSendDuplicateDao().commitReservation(
            reservationId = reservation.reservationId,
            ownerUserId = reservation.ownerUserId,
            frontSha256 = reservation.frontSha256,
            capsuleId = reservation.capsuleId,
            nowEpochMs = nowEpochMs(),
        ) == 1
        if (committed) pruneCommittedInTransaction(reservation.ownerUserId)
        return committed
    }

    /** Releases only this still-active owner reservation; committed history is immutable. */
    suspend fun release(reservation: ExactDuplicateReservation): Boolean = database.withTransaction {
        database.localSendDuplicateDao().releaseReservation(
            reservationId = reservation.reservationId,
            ownerUserId = reservation.ownerUserId,
            frontSha256 = reservation.frontSha256,
            capsuleId = reservation.capsuleId,
        ) == 1
    }

    private suspend fun cleanupExpiredInTransaction(
        dao: LocalSendDuplicateDao,
        ownerUserId: String,
        now: Long,
    ) {
        dao.deleteExpiredReservations(ownerUserId, now)
        dao.deleteExpiredHistory(ownerUserId, now - retentionWindowMs)
    }

    private suspend fun pruneCommittedInTransaction(ownerUserId: String) {
        val dao = database.localSendDuplicateDao()
        val oldIds = dao.committedIdsBeyondRecentLimit(ownerUserId, maxRecentCount)
        if (oldIds.isNotEmpty()) dao.deleteByOwnerAndReservationIds(ownerUserId, oldIds)
    }

    companion object {
        const val SHA256_BYTES = 32
        const val DEFAULT_RETENTION_WINDOW_MS = 24L * 60L * 60L * 1000L
        const val DEFAULT_MAX_RECENT_COUNT = 100
        /** A crashed reservation is reclaimable after the same bounded window. */
        const val DEFAULT_RESERVATION_LEASE_MS = DEFAULT_RETENTION_WINDOW_MS
        const val STATE_RESERVED = "RESERVED"
        const val STATE_COMMITTED = "COMMITTED"
    }
}
