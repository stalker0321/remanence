package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * M2-P02/P03 + review fix: every account-owned lookup, list, compare-and-set,
 * and cleanup REQUIRES the row's immutable owner_user_id. Writes go ONLY
 * through [upsertForOwner]: the same local account may replay/update its own
 * cache entry's transport fields, while a different account presenting the
 * same immutable blob ID is REFUSED with the original row left unchanged.
 */
@Dao
interface BlobCacheDao {

    /**
     * Owner-preserving idempotent cache write. [BlobCacheEntity.cacheState]
     * is NOT a replay field - it changes only through the owner-guarded CAS -
     * so replaying never rewinds a download state.
     */
    @Transaction
    suspend fun upsertForOwner(blob: BlobCacheEntity) {
        val existingOwner = findOwnerOf(blob.blobId)
        if (existingOwner != null && existingOwner != blob.ownerUserId) {
            throw IllegalStateException(
                "blob cache ${blob.blobId} already cached for another local account",
            )
        }
        insertIgnoring(blob)
        updateReplayFieldsForOwner(
            blobId = blob.blobId,
            ownerUserId = blob.ownerUserId,
            kind = blob.kind,
            ordinal = blob.ordinal,
            expectedSizeBytes = blob.expectedSizeBytes,
            expectedSha256 = blob.expectedSha256,
            localPath = blob.localPath,
        )
    }

    /** Minimal ownership probe: never returns full unscoped rows. */
    @Query("SELECT owner_user_id FROM blob_cache WHERE blob_id = :blobId LIMIT 1")
    suspend fun findOwnerOf(blobId: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(blob: BlobCacheEntity)

    @Query(
        "UPDATE blob_cache SET kind = :kind, ordinal = :ordinal, " +
            "expected_size_bytes = :expectedSizeBytes, expected_sha256 = :expectedSha256, " +
            "local_path = :localPath " +
            "WHERE blob_id = :blobId AND owner_user_id = :ownerUserId",
    )
    suspend fun updateReplayFieldsForOwner(
        blobId: String,
        ownerUserId: String,
        kind: String,
        ordinal: Int?,
        expectedSizeBytes: Long,
        expectedSha256: ByteArray,
        localPath: String,
    )

    @Query("DELETE FROM blob_cache")
    suspend fun clear()

    /** Resolves the cache entry ONLY when owned by [ownerUserId]. */
    @Query(
        "SELECT * FROM blob_cache " +
            "WHERE blob_id = :blobId AND owner_user_id = :ownerUserId",
    )
    suspend fun getByBlobIdAndOwner(blobId: String, ownerUserId: String): BlobCacheEntity?

    /** Lists the capsule's cached blobs ONLY when owned by [ownerUserId]. */
    @Query(
        "SELECT * FROM blob_cache " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    suspend fun getAllByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): List<BlobCacheEntity>

    /** Owner-guarded cache-state CAS; 0 rows means refused. */
    @Query(
        "UPDATE blob_cache SET cache_state = :newState " +
            "WHERE blob_id = :blobId AND owner_user_id = :ownerUserId AND cache_state IN (:allowedFrom)",
    )
    suspend fun transitionStateForOwner(
        blobId: String,
        ownerUserId: String,
        newState: BlobCacheState,
        allowedFrom: List<BlobCacheState>,
    ): Int

    /** Reports rows removed; refuses any capsule not owned by [ownerUserId]. */
    @Query(
        "DELETE FROM blob_cache WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    suspend fun deleteByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): Int
}
