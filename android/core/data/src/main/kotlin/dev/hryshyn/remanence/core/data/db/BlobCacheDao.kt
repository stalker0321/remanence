package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface BlobCacheDao {

    @Upsert
    suspend fun upsert(blob: BlobCacheEntity)

    @Query("SELECT * FROM blob_cache WHERE blob_id = :blobId")
    suspend fun getByBlobId(blobId: String): BlobCacheEntity?

    @Query("SELECT * FROM blob_cache WHERE capsule_id = :capsuleId")
    suspend fun getAllByCapsuleId(capsuleId: String): List<BlobCacheEntity>

    @Query(
        "UPDATE blob_cache SET cache_state = :newState " +
            "WHERE blob_id = :blobId AND cache_state IN (:allowedFrom)",
    )
    suspend fun transitionState(
        blobId: String,
        newState: BlobCacheState,
        allowedFrom: List<BlobCacheState>,
    ): Int

    @Query("DELETE FROM blob_cache WHERE capsule_id = :capsuleId")
    suspend fun deleteByCapsuleId(capsuleId: String)

    @Query("DELETE FROM blob_cache")
    suspend fun clear()

    // M2-P02 account-scoped primitives: ownership-guarded reads, CAS
    // transitions, and cleanup for P03's production conversion.

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
