package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

/**
 * M2-P02/P03: every account-owned lookup, list, and compare-and-set on
 * outbox capsules REQUIRES [OutboxCapsuleEntity.ownerUserId]; no unscoped
 * account-owned query exists anymore, so another login can neither observe
 * nor resume this account's material.
 */
@Dao
interface OutboxCapsuleDao {

    /** Row creation only; the persisted [OutboxCapsuleEntity.ownerUserId] is authoritative afterwards. */
    @Upsert
    suspend fun upsert(capsule: OutboxCapsuleEntity)

    @Query("DELETE FROM outbox_capsule")
    suspend fun clear()

    /** Resolves the capsule ONLY when it is owned by [ownerUserId]. */
    @Query(
        "SELECT * FROM outbox_capsule " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    suspend fun getByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): OutboxCapsuleEntity?

    /** Owner-guarded compare-and-set state transition; 0 rows means refused. */
    @Query(
        "UPDATE outbox_capsule SET state = :newState " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId AND state IN (:allowedFrom)",
    )
    suspend fun transitionStateForOwner(
        capsuleId: String,
        ownerUserId: String,
        newState: OutboxCapsuleState,
        allowedFrom: List<OutboxCapsuleState>,
    ): Int

    /** Owner-guarded CAS with a structured error code; 0 rows means refused. */
    @Query(
        "UPDATE outbox_capsule SET last_error_code = :errorCode, state = :newState " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId AND state IN (:allowedFrom)",
    )
    suspend fun transitionStateWithErrorForOwner(
        capsuleId: String,
        ownerUserId: String,
        newState: OutboxCapsuleState,
        allowedFrom: List<OutboxCapsuleState>,
        errorCode: String?,
    ): Int
}

/**
 * M2-P02/P03: strict insert on conflict for row creation (a colliding blob ID
 * or capsule-scoped `(capsule_id, kind, ordinal)` relationship can NEVER be
 * converted into an update that silently reassigns another account's row),
 * and owner-required lookups/lists/CAS afterwards.
 */
@Dao
interface OutboxBlobDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun upsertAll(blobs: List<OutboxBlobEntity>)

    @Query("DELETE FROM outbox_blob")
    suspend fun clear()

    /** Lists the capsule's blobs ONLY when they are owned by [ownerUserId]. */
    @Query(
        "SELECT * FROM outbox_blob " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    suspend fun getAllByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): List<OutboxBlobEntity>

    @Query(
        "SELECT COUNT(*) FROM outbox_blob " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId AND kind = :kind",
    )
    suspend fun countByKindAndOwner(capsuleId: String, ownerUserId: String, kind: String): Int

    /** Owner-guarded upload-state CAS; 0 rows means refused. */
    @Query(
        "UPDATE outbox_blob SET upload_state = :newState " +
            "WHERE blob_id = :blobId AND owner_user_id = :ownerUserId AND upload_state IN (:allowedFrom)",
    )
    suspend fun transitionUploadStateForOwner(
        blobId: String,
        ownerUserId: String,
        newState: OutboxBlobUploadState,
        allowedFrom: List<OutboxBlobUploadState>,
    ): Int

    /** Counts a retry attempt only for the owning account; returns 0 when refused. */
    @Query(
        "UPDATE outbox_blob SET attempt_count = attempt_count + 1 " +
            "WHERE blob_id = :blobId AND owner_user_id = :ownerUserId",
    )
    suspend fun incrementAttemptCountForOwner(blobId: String, ownerUserId: String): Int

    /** Reports rows removed; refuses any capsule not owned by [ownerUserId]. */
    @Query(
        "DELETE FROM outbox_blob WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    suspend fun deleteByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): Int
}
