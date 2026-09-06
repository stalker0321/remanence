package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Minimal owner-scoped blob identity needed before filesystem deletion. */
data class TombstoneBlobRow(
    @ColumnInfo(name = "blob_id") val blobId: String,
    @ColumnInfo(name = "capsule_id") val capsuleId: String,
    @ColumnInfo(name = "owner_user_id") val ownerUserId: String,
    @ColumnInfo(name = "local_path") val localPath: String,
) {
    override fun toString(): String = "TombstoneBlobRow(<redacted>)"
}

/**
 * One transactional tombstone application boundary.  The marker, incoming
 * capsule status, envelope/blob row purge, and watermark are one Room
 * transaction. Files are deleted by the repository before this transaction;
 * deletion is exact and idempotent, so a crash before the Room commit
 * converges on replay without advancing the watermark prematurely.
 */
@Dao
abstract class RecipientTombstoneDao {

    @Query(
        "SELECT * FROM tombstone_watermark WHERE owner_user_id = :ownerUserId LIMIT 1",
    )
    abstract suspend fun getWatermarkForOwner(ownerUserId: String): TombstoneWatermarkEntity?

    /** Returns only ciphertext file identities belonging to this owner/capsule. */
    @Query(
        "SELECT b.blob_id AS blob_id, b.capsule_id AS capsule_id, " +
            "b.owner_user_id AS owner_user_id, b.local_path AS local_path " +
            "FROM blob_cache AS b " +
            "WHERE b.owner_user_id = :ownerUserId AND b.capsule_id IN (:capsuleIds)",
    )
    abstract suspend fun getBlobRowsForOwner(
        ownerUserId: String,
        capsuleIds: List<String>,
    ): List<TombstoneBlobRow>

    @Query(
        "SELECT * FROM recipient_tombstone " +
            "WHERE owner_user_id = :ownerUserId AND capsule_id = :capsuleId LIMIT 1",
    )
    abstract suspend fun getForOwner(ownerUserId: String, capsuleId: String): RecipientTombstoneEntity?

    /**
     * Applies one validated feed page. A marker's timestamp is immutable;
     * receiving a different timestamp for the same owner/capsule is a
     * database failure rather than an overwrite.
     */
    @Transaction
    open suspend fun applyPage(
        ownerUserId: String,
        expectedCursor: String?,
        tombstones: List<RecipientTombstoneEntity>,
        nextCursor: String?,
        committedAtEpochMs: Long,
    ) {
        require(ownerUserId.isNotBlank()) { "tombstone owner is required" }
        require(committedAtEpochMs >= 0L) { "tombstone commit time is invalid" }
        require(tombstones.map { it.capsuleId }.toSet().size == tombstones.size) {
            "tombstone page contains duplicate capsule IDs"
        }
        tombstones.forEach { tombstone ->
            require(tombstone.ownerUserId == ownerUserId)
            require(tombstone.capsuleId.isNotBlank())
            require(tombstone.revokedAtEpochMs >= 0L)
        }
        val stored = findWatermark(ownerUserId)
        require(stored?.serverCursor == expectedCursor) {
            "tombstone watermark changed before commit"
        }

        for (tombstone in tombstones) {
            val existingOwner = findCapsuleOwner(tombstone.capsuleId)
            require(existingOwner == null || existingOwner == ownerUserId) {
                "tombstone capsule is owned by another local account"
            }
            val marker = findTombstone(ownerUserId, tombstone.capsuleId)
            if (marker != null) {
                require(marker.revokedAtEpochMs == tombstone.revokedAtEpochMs) {
                    "recipient tombstone revoked_at is immutable"
                }
            } else {
                insertTombstone(tombstone)
            }
            if (existingOwner == ownerUserId) {
                val current = findCapsule(tombstone.capsuleId, ownerUserId)
                checkNotNull(current)
                if (current.serverStatus != REVOKED_STATUS) {
                    check(markRevoked(tombstone.capsuleId, ownerUserId) == 1) {
                        "recipient tombstone status update lost its compare-and-set"
                    }
                }
                deleteEnvelope(tombstone.capsuleId, ownerUserId)
                deleteBlobs(tombstone.capsuleId, ownerUserId)
            }
        }

        if (stored == null) {
            insertWatermark(
                TombstoneWatermarkEntity(
                    ownerUserId = ownerUserId,
                    serverCursor = nextCursor,
                    lastSyncedAtEpochMs = committedAtEpochMs,
                ),
            )
        } else {
            check(
                updateWatermark(
                    ownerUserId = ownerUserId,
                    serverCursor = nextCursor,
                    lastSyncedAtEpochMs = committedAtEpochMs,
                ) == 1,
            ) { "recipient tombstone watermark update failed" }
        }
    }

    @Query("SELECT * FROM tombstone_watermark WHERE owner_user_id = :ownerUserId LIMIT 1")
    protected abstract suspend fun findWatermark(ownerUserId: String): TombstoneWatermarkEntity?

    @Query("SELECT owner_user_id FROM incoming_capsule WHERE capsule_id = :capsuleId LIMIT 1")
    protected abstract suspend fun findCapsuleOwner(capsuleId: String): String?

    @Query(
        "SELECT * FROM incoming_capsule " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId LIMIT 1",
    )
    protected abstract suspend fun findCapsule(capsuleId: String, ownerUserId: String): IncomingCapsuleEntity?

    @Query(
        "SELECT * FROM recipient_tombstone " +
            "WHERE owner_user_id = :ownerUserId AND capsule_id = :capsuleId LIMIT 1",
    )
    protected abstract suspend fun findTombstone(ownerUserId: String, capsuleId: String): RecipientTombstoneEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertTombstone(tombstone: RecipientTombstoneEntity)

    @Query(
        "UPDATE incoming_capsule SET server_status = 'REVOKED' " +
            "WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId " +
            "AND server_status != 'REVOKED'",
    )
    protected abstract suspend fun markRevoked(capsuleId: String, ownerUserId: String): Int

    @Query("DELETE FROM incoming_envelope WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId")
    protected abstract suspend fun deleteEnvelope(capsuleId: String, ownerUserId: String)

    @Query("DELETE FROM blob_cache WHERE capsule_id = :capsuleId AND owner_user_id = :ownerUserId")
    protected abstract suspend fun deleteBlobs(capsuleId: String, ownerUserId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertWatermark(watermark: TombstoneWatermarkEntity)

    @Query(
        "UPDATE tombstone_watermark SET server_cursor = :serverCursor, " +
            "last_synced_at_epoch_ms = :lastSyncedAtEpochMs " +
            "WHERE owner_user_id = :ownerUserId",
    )
    protected abstract suspend fun updateWatermark(
        ownerUserId: String,
        serverCursor: String?,
        lastSyncedAtEpochMs: Long,
    ): Int

    private companion object {
        const val REVOKED_STATUS = "REVOKED"
    }
}
