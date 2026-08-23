package postmark.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface SyncCursorDao {

    @Query("SELECT * FROM sync_cursor WHERE user_id = :userId AND stream_name = :streamName")
    suspend fun get(userId: String, streamName: String): SyncCursorEntity?

    /**
     * Moves the stored cursor forward to [candidate] and reports whether the
     * position changed. Replays carrying an equal or older sync timestamp are
     * ignored, so re-delivered pages can never rewind the stream.
     */
    @Transaction
    suspend fun advance(candidate: SyncCursorEntity): Boolean {
        val updated = updateIfNewer(
            userId = candidate.userId,
            streamName = candidate.streamName,
            serverCursor = candidate.serverCursor,
            lastSyncedAtEpochMs = candidate.lastSyncedAtEpochMs,
        )
        if (updated > 0) return true
        val existing = get(candidate.userId, candidate.streamName) ?: run {
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
    suspend fun updateIfNewer(
        userId: String,
        streamName: String,
        serverCursor: String?,
        lastSyncedAtEpochMs: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(cursor: SyncCursorEntity)

    @Query("DELETE FROM sync_cursor WHERE user_id = :userId")
    suspend fun deleteByUser(userId: String)

    @Query("DELETE FROM sync_cursor")
    suspend fun clear()
}
