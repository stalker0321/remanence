package dev.hryshyn.remanence.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Upload lifecycle of one declared ciphertext blob inside the outbox. */
enum class OutboxBlobUploadState {
    PENDING,
    STORED,
}

@Entity(
    tableName = "outbox_blob",
    indices = [
        Index(value = ["capsule_id"]),
        Index(value = ["capsule_id", "kind", "ordinal"], unique = true),
        Index(value = ["owner_user_id"]),
    ],
)
data class OutboxBlobEntity(
    @PrimaryKey
    @ColumnInfo(name = "blob_id")
    val blobId: String,
    /** Immutable owning local account UUID string; empty means unattributed. */
    @ColumnInfo(name = "owner_user_id", defaultValue = "")
    val ownerUserId: String,
    @ColumnInfo(name = "capsule_id")
    val capsuleId: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "ordinal")
    val ordinal: Int?,
    @ColumnInfo(name = "local_ciphertext_path")
    val localCiphertextPath: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "sha256")
    val sha256: ByteArray,
    @ColumnInfo(name = "upload_state")
    val uploadState: OutboxBlobUploadState,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is OutboxBlobEntity &&
            blobId == other.blobId &&
            sha256.contentEquals(other.sha256)

    override fun hashCode(): Int = 31 * blobId.hashCode() + sha256.contentHashCode()
}
