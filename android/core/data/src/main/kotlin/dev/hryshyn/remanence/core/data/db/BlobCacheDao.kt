package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * M2-P02/P03 + review fix + D01: every account-owned lookup, list,
 * compare-and-set, and cleanup REQUIRES the row's immutable owner_user_id.
 * Writes go ONLY through [upsertForOwner]: the same local account may
 * replay/update its own cache entry's transport fields, while a different
 * account presenting the same immutable blob ID is REFUSED with the original
 * row left unchanged.
 *
 * D01: converted from interface to abstract class. Internal probe/write
 * primitives are [protected]; only owner-scoped public surface remains.
     */
@Dao
abstract class BlobCacheDao {

    /**
     * Owner-preserving idempotent cache write. [ownerUserId] is authoritative;
     * the entity owner must match it before any database work. [BlobCacheEntity.cacheState]
     * is NOT a replay field - it changes only through the owner-guarded CAS -
     * so replaying never rewinds a download state.
    */
    @Transaction
    open suspend fun upsertForOwner(ownerUserId: String, blob: BlobCacheEntity) {
        require(blob.ownerUserId == ownerUserId) {
            "blob cache ${blob.blobId} owner does not match the authoritative owner"
        }
        val existingOwner = findOwnerOf(blob.blobId)
        if (existingOwner != null && existingOwner != ownerUserId) {
            throw IllegalStateException(
                "blob cache ${blob.blobId} already cached for another local account",
            )
        }
        insertIgnoring(blob)
        updateReplayFieldsForOwner(
            blobId = blob.blobId,
            ownerUserId = ownerUserId,
            kind = blob.kind,
            ordinal = blob.ordinal,
            expectedSizeBytes = blob.expectedSizeBytes,
            expectedSha256 = blob.expectedSha256,
            localPath = blob.localPath,
        )
    }

    /** Minimal ownership probe: never returns full unscoped rows. */
    @Query("SELECT owner_user_id FROM blob_cache WHERE blob_id = :blobId LIMIT 1")
    protected abstract suspend fun findOwnerOf(blobId: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnoring(blob: BlobCacheEntity)

    @Query(
        "UPDATE blob_cache SET kind = :kind, ordinal = :ordinal, " +
            "expected_size_bytes = :expectedSizeBytes, expected_sha256 = :expectedSha256, " +
            "local_path = :localPath " +
            "WHERE blob_id = :blobId AND owner_user_id = :ownerUserId",
    )
    protected abstract suspend fun updateReplayFieldsForOwner(
        blobId: String,
        ownerUserId: String,
        kind: String,
        ordinal: Int?,
        expectedSizeBytes: Long,
        expectedSha256: ByteArray,
        localPath: String,
    )

    /** Owner-scoped teardown: removes only rows belonging to [ownerUserId]. */
    @Query("DELETE FROM blob_cache WHERE owner_user_id = :ownerUserId")
    abstract suspend fun clearForOwner(ownerUserId: String)

    /** Resolves the cache entry ONLY when owned by [ownerUserId]. */
    @Query(
        "SELECT * FROM blob_cache " +
            "WHERE blob_id = :blobId AND owner_user_id = :ownerUserId",
    )
    abstract suspend fun getByBlobIdAndOwner(blobId: String, ownerUserId: String): BlobCacheEntity?

    /** Lists the capsule's cached blobs ONLY when owned by [ownerUserId]. */
    @Query(
        "SELECT * FROM blob_cache " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    abstract suspend fun getAllByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): List<BlobCacheEntity>

    /** Owner-guarded DOWNLOADING -> CACHED CAS; 0 rows means refused. */
    @Query(
        "UPDATE blob_cache SET cache_state = 'CACHED' " +
            "WHERE blob_id = :blobId AND owner_user_id = :ownerUserId " +
            "AND cache_state = 'DOWNLOADING'",
    )
    abstract suspend fun markCachedForOwner(
        blobId: String,
        ownerUserId: String,
    ): Int

    /** Owner-guarded DOWNLOADING/CACHED -> CORRUPT CAS; 0 rows means refused. */
    @Query(
        "UPDATE blob_cache SET cache_state = 'CORRUPT' " +
            "WHERE blob_id = :blobId AND owner_user_id = :ownerUserId " +
            "AND cache_state IN ('DOWNLOADING', 'CACHED')",
    )
    abstract suspend fun markCorruptForOwner(blobId: String, ownerUserId: String): Int

    /** Owner-guarded CORRUPT -> DOWNLOADING CAS; 0 rows means refused. */
    @Query(
        "UPDATE blob_cache SET cache_state = 'DOWNLOADING' " +
            "WHERE blob_id = :blobId AND owner_user_id = :ownerUserId " +
            "AND cache_state = 'CORRUPT'",
    )
    abstract suspend fun retryDownloadForOwner(blobId: String, ownerUserId: String): Int

    /** Reports rows removed; refuses any capsule not owned by [ownerUserId]. */
    @Query(
        "DELETE FROM blob_cache WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId",
    )
    abstract suspend fun deleteByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): Int
}
