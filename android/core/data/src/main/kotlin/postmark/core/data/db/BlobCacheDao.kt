package postmark.core.data.db

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
}
