package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

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

    /** Exact owner + capsule current-send state for the mounted Create flow. */
    @Query(
        "SELECT state, last_error_code FROM outbox_capsule " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    abstract fun observeStatusByCapsuleIdAndOwner(
        capsuleId: String,
        ownerUserId: String,
    ): Flow<OutboxCapsuleStatus?>

    /**
     * Returns only owner-scoped capsule IDs whose persisted state can be
     * replayed by the upload worker. Capsule ID is the stable ordering key;
     * no paths, keys, or other capsule material cross this discovery boundary.
     */
    @Query(
        "SELECT capsule_id FROM outbox_capsule " +
            "WHERE owner_user_id = :ownerUserId AND (" +
            "(state IN ('ENCRYPTED', 'UPLOADING') " +
            "    AND (last_error_code IS NULL OR last_error_code NOT IN " +
            "        ('RECIPIENT_KEY_STALE', 'RECIPIENT_KEY_STALE_DRAFT', " +
            "         'RECIPIENT_KEY_STALE_FINALIZE'))) " +
            "OR (state = 'FINALIZING' " +
            "    AND (last_error_code IS NULL OR last_error_code NOT IN " +
            "        ('RECIPIENT_KEY_STALE', 'RECIPIENT_KEY_STALE_DRAFT'))) " +
            "OR (state = 'RETRYABLE_FAILURE' " +
            "    AND (last_error_code IS NULL OR last_error_code NOT IN " +
            "        ('RECIPIENT_KEY_STALE', 'RECIPIENT_KEY_STALE_FINALIZE'))) " +
            "OR (state IN ('PUBLISHED', 'TERMINAL_FAILURE') " +
            "    AND sender_retry_keyset_path IS NOT NULL " +
            "    AND (last_error_code IS NULL OR last_error_code NOT IN " +
            "        ('RECIPIENT_KEY_STALE', 'RECIPIENT_KEY_STALE_DRAFT', " +
            "         'RECIPIENT_KEY_STALE_FINALIZE')))" +
            ") ORDER BY capsule_id",
    )
    abstract suspend fun getCapsuleIdsNeedingUploadForOwner(ownerUserId: String): List<String>

    /** Owner-guarded PREPARING -> ENCRYPTED CAS; 0 rows means refused. */
    @Query(
        "UPDATE outbox_capsule SET state = 'ENCRYPTED' " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId " +
            "AND state = 'PREPARING'",
    )
    abstract suspend fun markEncryptedForOwner(capsuleId: String, ownerUserId: String): Int

    /** Owner-guarded ENCRYPTED/RETRYABLE_FAILURE -> UPLOADING CAS. */
    @Query(
        "UPDATE outbox_capsule SET state = 'UPLOADING' " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId " +
            "AND state IN ('ENCRYPTED', 'RETRYABLE_FAILURE')",
    )
    abstract suspend fun beginUploadForOwner(capsuleId: String, ownerUserId: String): Int

    /** A06 draft-origin stale rewrap: retryable parked row -> fresh draft phase. */
    @Query(
        "UPDATE outbox_capsule SET " +
            "recipient_key_bundle_id = :newRecipientKeyBundleId, " +
            "envelope_path = :newEnvelopePath, " +
            "publish_statement_path = :newPublishStatementPath, " +
            "publish_statement_signature_path = :newPublishStatementSignaturePath, " +
            "state = 'ENCRYPTED', last_error_code = NULL " +
            "WHERE capsule_id = :capsuleId " +
            "AND owner_user_id = :ownerUserId " +
            "AND recipient_user_id = :recipientUserId " +
            "AND recipient_key_bundle_id = :expectedRecipientKeyBundleId " +
            "AND state = 'RETRYABLE_FAILURE' " +
            "AND last_error_code = 'RECIPIENT_KEY_STALE_DRAFT'",
    )
    abstract suspend fun applyDraftRecipientKeyRewrapForOwner(
        capsuleId: String,
        ownerUserId: String,
        recipientUserId: String,
        expectedRecipientKeyBundleId: String,
        newRecipientKeyBundleId: String,
        newEnvelopePath: String,
        newPublishStatementPath: String,
        newPublishStatementSignaturePath: String,
    ): Int

    /** A06 finalize-origin stale rewrap: parked finalize phase -> finalize phase. */
    @Query(
        "UPDATE outbox_capsule SET " +
            "recipient_key_bundle_id = :newRecipientKeyBundleId, " +
            "envelope_path = :newEnvelopePath, " +
            "publish_statement_path = :newPublishStatementPath, " +
            "publish_statement_signature_path = :newPublishStatementSignaturePath, " +
            "state = 'FINALIZING', last_error_code = NULL " +
            "WHERE capsule_id = :capsuleId " +
            "AND owner_user_id = :ownerUserId " +
            "AND recipient_user_id = :recipientUserId " +
            "AND recipient_key_bundle_id = :expectedRecipientKeyBundleId " +
            "AND state = 'FINALIZING' " +
            "AND last_error_code = 'RECIPIENT_KEY_STALE_FINALIZE'",
    )
    abstract suspend fun applyFinalizeRecipientKeyRewrapForOwner(
        capsuleId: String,
        ownerUserId: String,
        recipientUserId: String,
        expectedRecipientKeyBundleId: String,
        newRecipientKeyBundleId: String,
        newEnvelopePath: String,
        newPublishStatementPath: String,
        newPublishStatementSignaturePath: String,
    ): Int

    /** Owner-guarded UPLOADING -> FINALIZING CAS; 0 rows means refused. */
    @Query(
        "UPDATE outbox_capsule SET state = 'FINALIZING' " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId " +
            "AND state = 'UPLOADING'",
    )
    abstract suspend fun beginFinalizeForOwner(capsuleId: String, ownerUserId: String): Int

    /** Owner-guarded FINALIZING -> PUBLISHED CAS; 0 rows means refused. */
    @Query(
        "UPDATE outbox_capsule SET state = 'PUBLISHED' " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId " +
            "AND state = 'FINALIZING'",
    )
    abstract suspend fun markPublishedForOwner(capsuleId: String, ownerUserId: String): Int

    /** Owner-guarded finalize retry marker that preserves FINALIZING for replay. */
    @Query(
        "UPDATE outbox_capsule SET last_error_code = :errorCode " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId " +
            "AND state = 'FINALIZING'",
    )
    abstract suspend fun markFinalizeRetryableForOwner(
        capsuleId: String,
        ownerUserId: String,
        errorCode: String?,
    ): Int

    /** Owner-guarded recoverable failure transition with its structured code. */
    @Query(
        "UPDATE outbox_capsule SET last_error_code = :errorCode, state = 'RETRYABLE_FAILURE' " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId " +
            "AND state IN ('ENCRYPTED', 'UPLOADING', 'FINALIZING')",
    )
    abstract suspend fun markRetryableFailureForOwner(
        capsuleId: String,
        ownerUserId: String,
        errorCode: String?,
    ): Int

    /** Owner-guarded terminal failure transition with its structured code. */
    @Query(
        "UPDATE outbox_capsule SET last_error_code = :errorCode, state = 'TERMINAL_FAILURE' " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId " +
            "AND state IN ('PREPARING', 'ENCRYPTED', 'UPLOADING', 'FINALIZING', 'RETRYABLE_FAILURE')",
    )
    abstract suspend fun markTerminalFailureForOwner(
        capsuleId: String,
        ownerUserId: String,
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
