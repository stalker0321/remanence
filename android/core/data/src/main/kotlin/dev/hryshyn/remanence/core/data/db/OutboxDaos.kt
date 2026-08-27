package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * M2-P02/P03: every account-owned lookup, list, and compare-and-set on
 * outbox capsules REQUIRES [OutboxCapsuleEntity.ownerUserId]; no unscoped
 * account-owned query exists anymore, so another login can neither observe
 * nor resume this account's material.
 */
@Dao
interface OutboxCapsuleDao {

    /**
     * Strict row creation: ANY capsule_id collision - including a foreign-
     * owned one the staging pre-check cannot see by design - raises a SQLite
     * constraint exception. Unlike an upsert it can NEVER be converted into
     * an update that overwrites another local account's durable row.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOrAbort(capsule: OutboxCapsuleEntity)

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

    /**
     * M2-P09: owner-scoped conditional clear of the retry material
     * pointer. The update fires ONLY when the capsule is owned by
     * [ownerUserId] AND the stored pointer equals [expectedPath]
     * (or both are NULL). Returns 1 when the pointer was cleared,
     * 0 when refused (wrong owner, wrong capsule, or pointer
     * already cleared/mismatched). The caller MUST delete or
     * confirm the file is missing BEFORE calling this; if death
     * occurs between file deletion and pointer clear, replay sees
     * a missing file and re-clears harmlessly.
     */
    @Query(
        "UPDATE outbox_capsule SET sender_retry_keyset_path = NULL " +
            "WHERE capsule_id = :capsuleId " +
            "AND owner_user_id = :ownerUserId " +
            "AND (sender_retry_keyset_path = :expectedPath " +
            "     OR (sender_retry_keyset_path IS NULL AND :expectedPath IS NULL))",
    )
    suspend fun clearSenderRetryKeysetPath(
        capsuleId: String,
        ownerUserId: String,
        expectedPath: String?,
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
