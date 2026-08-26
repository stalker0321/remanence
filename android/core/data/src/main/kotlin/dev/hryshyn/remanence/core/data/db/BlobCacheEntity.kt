package dev.hryshyn.remanence.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Private local cache lifecycle of one declared capsule blob. */
enum class BlobCacheState {
    DOWNLOADING,
    CACHED,
    CORRUPT,
}

/**
 * Reference to one locally cached ciphertext blob. The path is always an
 * app-private file path, never a content URI shareable with other apps.
 */
@Entity(tableName = "blob_cache")
data class BlobCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "blob_id")
    val blobId: String,
    @ColumnInfo(name = "capsule_id")
    val capsuleId: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "ordinal")
    val ordinal: Int?,
    @ColumnInfo(name = "expected_size_bytes")
    val expectedSizeBytes: Long,
    @ColumnInfo(name = "expected_sha256")
    val expectedSha256: ByteArray,
    @ColumnInfo(name = "local_path")
    val localPath: String,
    @ColumnInfo(name = "cache_state")
    val cacheState: BlobCacheState,
) {
    override fun equals(other: Any?): Boolean =
        other is BlobCacheEntity &&
            blobId == other.blobId &&
            expectedSha256.contentEquals(other.expectedSha256)

    override fun hashCode(): Int = 31 * blobId.hashCode() + expectedSha256.contentHashCode()
}
