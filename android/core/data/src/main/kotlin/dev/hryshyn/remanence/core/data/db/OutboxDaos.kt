package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * M2-P02/P03: every account-owned lookup, list, and compare-and-set on
 * outbox capsules REQUIRES [OutboxCapsuleEntity.ownerUserId]; no unscoped
 * account-owned query exists anymore, so another login can neither observe
 * nor resume this account's material.
 */
@Dao
abstract class OutboxCapsuleDao {

    /**
     * Strict owner-authorized row creation. Foreign immutable-key collisions
     * are rejected during preflight; same-owner duplicate keys still raise
     * the existing SQLite constraint exception. Unlike an upsert, this can
     * NEVER convert a collision into an update that overwrites a durable row.
     */
    @Transaction
    open suspend fun insertOrAbort(ownerUserId: String, capsule: OutboxCapsuleEntity) {
        require(capsule.ownerUserId == ownerUserId) {
            "capsule ${capsule.capsuleId} owner does not match the authoritative owner"
        }
        findOwnersOfImmutableIds(capsule.capsuleId, capsule.idempotencyKey).forEach { existingOwner ->
            if (existingOwner != ownerUserId) {
                throw IllegalStateException(
                    "capsule ${capsule.capsuleId} already belongs to another local account",
                )
            }
        }
        insertStrict(capsule)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertStrict(capsule: OutboxCapsuleEntity)

    /** Minimal immutable-key ownership probe used only during authorized insert preflight. */
    @Query(
        "SELECT owner_user_id FROM outbox_capsule " +
            "WHERE capsule_id = :capsuleId OR idempotency_key = :idempotencyKey",
    )
    protected abstract suspend fun findOwnersOfImmutableIds(capsuleId: String, idempotencyKey: String): List<String>

    /** Owner-scoped teardown: removes only rows belonging to [ownerUserId]. */
    @Query("DELETE FROM outbox_capsule WHERE owner_user_id = :ownerUserId")
    abstract suspend fun clearForOwner(ownerUserId: String)

    /** Resolves the capsule ONLY when it is owned by [ownerUserId]. */
    @Query(
        "SELECT * FROM outbox_capsule " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    abstract suspend fun getByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): OutboxCapsuleEntity?

    /** Owner-guarded compare-and-set state transition; 0 rows means refused. */
    @Query(
        "UPDATE outbox_capsule SET state = :newState " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId AND state IN (:allowedFrom)",
    )
    abstract suspend fun transitionStateForOwner(
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
    abstract suspend fun transitionStateWithErrorForOwner(
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
    abstract suspend fun clearSenderRetryKeysetPath(
        capsuleId: String,
        ownerUserId: String,
        expectedPath: String?,
    ): Int
}

/**
 * M2-P02/P03: strict owner-authorized insert on conflict for row creation (a
 * colliding blob ID or capsule-scoped `(capsule_id, kind, ordinal)` relationship
 * can NEVER be converted into an update that silently reassigns another
 * account's row), and owner-required lookups/lists/CAS afterwards.
 */
@Dao
abstract class OutboxBlobDao {

    @Transaction
    open suspend fun upsertAll(ownerUserId: String, blobs: List<OutboxBlobEntity>) {
        blobs.forEach { blob ->
            require(blob.ownerUserId == ownerUserId) {
                "blob ${blob.blobId} owner does not match the authoritative owner"
            }
        }
        blobs.forEach { blob ->
            val existingIdOwner = findOwnerOfBlobId(blob.blobId)
            if (existingIdOwner != null && existingIdOwner != ownerUserId) {
                throw IllegalStateException(
                    "blob ${blob.blobId} already belongs to another local account",
                )
            }
            val existingSlotOwner = findOwnerOfCapsuleSlot(blob.capsuleId, blob.kind, blob.ordinal)
            if (existingSlotOwner != null && existingSlotOwner != ownerUserId) {
                throw IllegalStateException(
                    "blob slot ${blob.capsuleId}/${blob.kind}/${blob.ordinal} already belongs to another local account",
                )
            }
        }
        insertStrict(blobs)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertStrict(blobs: List<OutboxBlobEntity>)

    /** Minimal immutable-ID ownership probe used only during authorized insert preflight. */
    @Query("SELECT owner_user_id FROM outbox_blob WHERE blob_id = :blobId LIMIT 1")
    protected abstract suspend fun findOwnerOfBlobId(blobId: String): String?

    /** Minimal relationship-key ownership probe used to fail closed before a batch write. */
    @Query(
        "SELECT owner_user_id FROM outbox_blob " +
            "WHERE capsule_id = :capsuleId AND kind = :kind " +
            "AND (ordinal = :ordinal OR (ordinal IS NULL AND :ordinal IS NULL)) LIMIT 1",
    )
    protected abstract suspend fun findOwnerOfCapsuleSlot(capsuleId: String, kind: String, ordinal: Int?): String?

    /** Owner-scoped teardown: removes only rows belonging to [ownerUserId]. */
    @Query("DELETE FROM outbox_blob WHERE owner_user_id = :ownerUserId")
    abstract suspend fun clearForOwner(ownerUserId: String)

    /** Lists the capsule's blobs ONLY when they are owned by [ownerUserId]. */
    @Query(
        "SELECT * FROM outbox_blob " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    abstract suspend fun getAllByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): List<OutboxBlobEntity>

    @Query(
        "SELECT COUNT(*) FROM outbox_blob " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId AND kind = :kind",
    )
    abstract suspend fun countByKindAndOwner(capsuleId: String, ownerUserId: String, kind: String): Int

    /** Owner-guarded PENDING -> STORED CAS; 0 rows means refused. */
    @Query(
        "UPDATE outbox_blob SET upload_state = 'STORED' " +
            "WHERE blob_id = :blobId AND owner_user_id = :ownerUserId " +
            "AND upload_state = 'PENDING'",
    )
    abstract suspend fun markStoredForOwner(blobId: String, ownerUserId: String): Int

    /** Counts a retry attempt only for the owning account; returns 0 when refused. */
    @Query(
        "UPDATE outbox_blob SET attempt_count = attempt_count + 1 " +
            "WHERE blob_id = :blobId AND owner_user_id = :ownerUserId",
    )
    abstract suspend fun incrementAttemptCountForOwner(blobId: String, ownerUserId: String): Int

    /** Reports rows removed; refuses any capsule not owned by [ownerUserId]. */
    @Query(
        "DELETE FROM outbox_blob WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    abstract suspend fun deleteByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): Int
}
