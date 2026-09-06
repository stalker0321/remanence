package dev.hryshyn.remanence.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Durable owner-scoped revocation marker.  The marker is retained after the
 * incoming envelope and ciphertext rows are purged so an out-of-order
 * incoming page cannot resurrect a revoked capsule.
 */
@Entity(
    tableName = "recipient_tombstone",
    primaryKeys = ["owner_user_id", "capsule_id"],
    indices = [Index(value = ["owner_user_id"])],
)
data class RecipientTombstoneEntity(
    @ColumnInfo(name = "owner_user_id") val ownerUserId: String,
    @ColumnInfo(name = "capsule_id") val capsuleId: String,
    @ColumnInfo(name = "revoked_at_epoch_ms") val revokedAtEpochMs: Long,
) {
    override fun toString(): String = "RecipientTombstoneEntity(<redacted>)"
}

/** One durable, account-scoped opaque position for the tombstone feed. */
@Entity(
    tableName = "tombstone_watermark",
    primaryKeys = ["owner_user_id"],
    indices = [Index(value = ["owner_user_id"])],
)
data class TombstoneWatermarkEntity(
    @ColumnInfo(name = "owner_user_id") val ownerUserId: String,
    @ColumnInfo(name = "server_cursor") val serverCursor: String?,
    @ColumnInfo(name = "last_synced_at_epoch_ms") val lastSyncedAtEpochMs: Long,
) {
    override fun toString(): String = "TombstoneWatermarkEntity(<redacted>)"
}
