package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface OutboxCapsuleDao {

    @Upsert
    suspend fun upsert(capsule: OutboxCapsuleEntity)

    @Query("SELECT * FROM outbox_capsule WHERE capsule_id = :capsuleId")
    suspend fun getByCapsuleId(capsuleId: String): OutboxCapsuleEntity?

    @Query(
        "UPDATE outbox_capsule SET state = :newState " +
            "WHERE capsule_id = :capsuleId AND state IN (:allowedFrom)",
    )
    suspend fun transitionState(
        capsuleId: String,
        newState: OutboxCapsuleState,
        allowedFrom: List<OutboxCapsuleState>,
    ): Int

    @Query(
        "UPDATE outbox_capsule SET last_error_code = :errorCode, state = :newState " +
            "WHERE capsule_id = :capsuleId AND state IN (:allowedFrom)",
    )
    suspend fun transitionStateWithError(
        capsuleId: String,
        newState: OutboxCapsuleState,
        allowedFrom: List<OutboxCapsuleState>,
        errorCode: String?,
    ): Int

    @Query("DELETE FROM outbox_capsule")
    suspend fun clear()

    // ------------------------------------------------------------------
    // M2-P02 account-scoped primitives. These guard every durable read and
    // compare-and-set transition by the row's immutable owning account, so a
    // wrong- or absent-owner call can neither observe nor mutate material.
    // P03 converts the production flows onto these.
    // ------------------------------------------------------------------

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

@Dao
interface OutboxBlobDao {

    /**
     * M2-P02: strict insert with abort-on-conflict. Unlike an upsert, a
     * collision on the globally unique blob ID or the capsule-scoped
     * `(capsule_id, kind, ordinal)` relationship can NEVER be converted into
     * an update - which would silently reassign another account's row.
     * Staging pre-checks make legitimate conflicts impossible.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun upsertAll(blobs: List<OutboxBlobEntity>)

    @Query("SELECT * FROM outbox_blob WHERE capsule_id = :capsuleId")
    suspend fun getAllByCapsuleId(capsuleId: String): List<OutboxBlobEntity>

    @Query("SELECT COUNT(*) FROM outbox_blob WHERE capsule_id = :capsuleId AND kind = :kind")
    suspend fun countByKind(capsuleId: String, kind: String): Int

    @Query(
        "UPDATE outbox_blob SET upload_state = :newState " +
            "WHERE blob_id = :blobId AND upload_state IN (:allowedFrom)",
    )
    suspend fun transitionUploadState(
        blobId: String,
        newState: OutboxBlobUploadState,
        allowedFrom: List<OutboxBlobUploadState>,
    ): Int

    /** Counts a retry attempt; returns 1 when the blob exists, else 0. */
    @Query("UPDATE outbox_blob SET attempt_count = attempt_count + 1 WHERE blob_id = :blobId")
    suspend fun incrementAttemptCount(blobId: String): Int

    @Query("DELETE FROM outbox_blob WHERE capsule_id = :capsuleId")
    suspend fun deleteByCapsuleId(capsuleId: String)

    @Query("DELETE FROM outbox_blob")
    suspend fun clear()

    // M2-P02 account-scoped primitives (see OutboxCapsuleDao).

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
