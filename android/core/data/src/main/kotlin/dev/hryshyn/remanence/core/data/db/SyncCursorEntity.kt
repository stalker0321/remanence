package dev.hryshyn.remanence.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Opaque per-stream synchronization position of one authenticated account
 * (architecture section 6). One row per `(user_id, stream_name)`.
 */
@Entity(
    tableName = "sync_cursor",
    primaryKeys = ["user_id", "stream_name"],
    indices = [
        androidx.room.Index(value = ["user_id"]),
    ],
)
data class SyncCursorEntity(
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "stream_name")
    val streamName: String,
    @ColumnInfo(name = "server_cursor")
    val serverCursor: String?,
    @ColumnInfo(name = "last_synced_at_epoch_ms")
    val lastSyncedAtEpochMs: Long,
)
