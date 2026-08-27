package dev.hryshyn.remanence.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class SyncCursorDao {

    @Query("SELECT * FROM sync_cursor WHERE user_id = :userId AND stream_name = :streamName")
    abstract suspend fun get(userId: String, streamName: String): SyncCursorEntity?

    /**
     * Moves the stored cursor forward to [candidate] and reports whether the
     * position changed. Replays carrying an equal or older sync timestamp are
     * ignored, so re-delivered pages can never rewind the stream.
     */
    @Transaction
    open suspend fun advance(ownerUserId: String, candidate: SyncCursorEntity): Boolean {
        require(candidate.userId == ownerUserId) {
            "sync cursor user does not match the authoritative owner"
        }
        val updated = updateIfNewer(
            userId = ownerUserId,
            streamName = candidate.streamName,
            serverCursor = candidate.serverCursor,
            lastSyncedAtEpochMs = candidate.lastSyncedAtEpochMs,
        )
        if (updated > 0) return true
        val existing = get(ownerUserId, candidate.streamName) ?: run {
            insertIgnoring(candidate)
            return true
        }
        // Equal-timestamp replay: keep the stored position untouched.
        return existing.serverCursor != candidate.serverCursor && existing.lastSyncedAtEpochMs < candidate.lastSyncedAtEpochMs
    }

    @Query(
        "UPDATE sync_cursor SET server_cursor = :serverCursor, last_synced_at_epoch_ms = :lastSyncedAtEpochMs " +
            "WHERE user_id = :userId AND stream_name = :streamName AND last_synced_at_epoch_ms < :lastSyncedAtEpochMs",
    )
    protected abstract suspend fun updateIfNewer(
        userId: String,
        streamName: String,
        serverCursor: String?,
        lastSyncedAtEpochMs: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnoring(cursor: SyncCursorEntity)

    @Query("DELETE FROM sync_cursor WHERE user_id = :userId")
    abstract suspend fun clearForOwner(userId: String)

}
