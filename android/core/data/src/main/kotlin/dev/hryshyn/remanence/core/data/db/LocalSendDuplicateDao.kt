package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Owner-scoped primitives for the exact local duplicate reservation policy. */
@Dao
abstract class LocalSendDuplicateDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(row: LocalSendDuplicateEntity): Long

    @Query(
        "DELETE FROM local_send_duplicate " +
            "WHERE owner_user_id = :ownerUserId " +
            "AND state = 'RESERVED' " +
            "AND reservation_expires_at_epoch_ms <= :nowEpochMs",
    )
    abstract suspend fun deleteExpiredReservations(ownerUserId: String, nowEpochMs: Long): Int

    @Query(
        "DELETE FROM local_send_duplicate " +
            "WHERE owner_user_id = :ownerUserId " +
            "AND state = 'COMMITTED' " +
            "AND created_at_epoch_ms <= :cutoffEpochMs",
    )
    abstract suspend fun deleteExpiredHistory(ownerUserId: String, cutoffEpochMs: Long): Int

    @Query(
        "UPDATE local_send_duplicate SET state = 'COMMITTED' " +
            "WHERE reservation_id = :reservationId " +
            "AND owner_user_id = :ownerUserId " +
            "AND front_sha256 = :frontSha256 " +
            "AND capsule_id = :capsuleId " +
            "AND state = 'RESERVED' " +
            "AND reservation_expires_at_epoch_ms > :nowEpochMs",
    )
    abstract suspend fun commitReservation(
        reservationId: String,
        ownerUserId: String,
        frontSha256: ByteArray,
        capsuleId: String,
        nowEpochMs: Long,
    ): Int

    @Query(
        "DELETE FROM local_send_duplicate " +
            "WHERE reservation_id = :reservationId " +
            "AND owner_user_id = :ownerUserId " +
            "AND front_sha256 = :frontSha256 " +
            "AND capsule_id = :capsuleId " +
            "AND state = 'RESERVED'",
    )
    abstract suspend fun releaseReservation(
        reservationId: String,
        ownerUserId: String,
        frontSha256: ByteArray,
        capsuleId: String,
    ): Int

    @Query(
        "UPDATE local_send_duplicate SET reservation_expires_at_epoch_ms = :newExpiryEpochMs " +
            "WHERE reservation_id = :reservationId " +
            "AND owner_user_id = :ownerUserId " +
            "AND front_sha256 = :frontSha256 " +
            "AND capsule_id = :capsuleId " +
            "AND state = 'RESERVED' " +
            "AND reservation_expires_at_epoch_ms > :nowEpochMs",
    )
    abstract suspend fun renewReservation(
        reservationId: String,
        ownerUserId: String,
        frontSha256: ByteArray,
        capsuleId: String,
        nowEpochMs: Long,
        newExpiryEpochMs: Long,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM local_send_duplicate " +
            "WHERE owner_user_id = :ownerUserId " +
            "AND capsule_id = :capsuleId " +
            "AND state = 'RESERVED' " +
            "AND reservation_id <> :reservationId",
    )
    abstract suspend fun countOtherReservedForCapsule(
        ownerUserId: String,
        capsuleId: String,
        reservationId: String,
    ): Int

    @Query(
        "SELECT reservation_id FROM local_send_duplicate " +
            "WHERE owner_user_id = :ownerUserId AND state = 'COMMITTED' " +
            "ORDER BY created_at_epoch_ms DESC, reservation_id DESC " +
            "LIMIT -1 OFFSET :keepCount",
    )
    abstract suspend fun committedIdsBeyondRecentLimit(ownerUserId: String, keepCount: Int): List<String>

    @Query(
        "DELETE FROM local_send_duplicate " +
            "WHERE owner_user_id = :ownerUserId AND reservation_id IN (:reservationIds)",
    )
    abstract suspend fun deleteByOwnerAndReservationIds(ownerUserId: String, reservationIds: List<String>): Int

    @Query("SELECT COUNT(*) FROM local_send_duplicate")
    abstract suspend fun countAll(): Int

    @Query(
        "SELECT COUNT(*) FROM local_send_duplicate " +
            "WHERE owner_user_id = :ownerUserId AND state = 'COMMITTED'",
    )
    abstract suspend fun countCommittedForOwner(ownerUserId: String): Int

    @Query(
        "SELECT * FROM local_send_duplicate " +
            "WHERE owner_user_id = :ownerUserId ORDER BY created_at_epoch_ms ASC, reservation_id ASC",
    )
    abstract suspend fun getAllForOwner(ownerUserId: String): List<LocalSendDuplicateEntity>
}
