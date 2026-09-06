package dev.hryshyn.remanence.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Owner-local exact FRONT identities used only for the bounded duplicate-send
 * policy. The digest is the SHA-256 of the captured FRONT bytes; no
 * recognition score, ciphertext, filename, or handle is stored here.
 */
@Entity(
    tableName = "local_send_duplicate",
    primaryKeys = ["reservation_id"],
    indices = [
        Index(value = ["owner_user_id", "front_sha256"], unique = true),
        Index(value = ["owner_user_id", "state", "created_at_epoch_ms", "reservation_id"]),
    ],
)
data class LocalSendDuplicateEntity(
    @ColumnInfo(name = "reservation_id")
    val reservationId: String,
    @ColumnInfo(name = "owner_user_id")
    val ownerUserId: String,
    @ColumnInfo(name = "front_sha256")
    val frontSha256: ByteArray,
    @ColumnInfo(name = "capsule_id")
    val capsuleId: String,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "reservation_expires_at_epoch_ms")
    val reservationExpiresAtEpochMs: Long,
)
