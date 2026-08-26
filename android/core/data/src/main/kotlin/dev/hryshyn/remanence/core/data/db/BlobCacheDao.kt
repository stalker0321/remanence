package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * M2-P02/P03: every account-owned lookup, list, compare-and-set, and cleanup
 * REQUIRES the row's immutable owner_user_id; no unscoped account-owned
 * query exists.
 */
@Dao
interface BlobCacheDao {

    /** Row creation/replay write; reads, transitions, and deletes stay owner-guarded. */
    @Upsert
    suspend fun upsert(blob: BlobCacheEntity)

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
