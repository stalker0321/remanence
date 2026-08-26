package dev.hryshyn.remanence.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class FingerprintSide {
    FRONT,
    BACK,
}

enum class FingerprintOrigin {
    /** Captured by the sender before mailing; arrives inside the encrypted recognition manifest. */
    SENDER,

    /** Built from the delivered postcard on this device after verified receipt. */
    RECIPIENT,
}

/**
 * Reference to one locally encrypted postcard fingerprint. Only opaque
 * encrypted bytes live behind [encryptedPath]; no raw bitmap is ever stored.
 *
 * M2-P02 account scoping: [ownerUserId] binds the baseline to its immutable
 * local account so another login can never enumerate or resume it
 * ('' only for legacy rows the v3→v4 migration could not attribute). The
 * (capsule, side, origin) uniqueness stays capsule-scoped on purpose: capsule
 * IDs are globally unique client-generated UUIDs, so owner-composing the key
 * would only weaken integrity, not add isolation.
 */
@Entity(
    tableName = "recognition_fingerprint",
    indices = [
        Index(value = ["capsule_id", "side", "origin"], unique = true),
        Index(value = ["owner_user_id"]),
    ],
)
data class RecognitionFingerprintEntity(
    @PrimaryKey
    @ColumnInfo(name = "fingerprint_id")
    val fingerprintId: String,
    /** Immutable owning local account UUID string. */
    @ColumnInfo(name = "owner_user_id", defaultValue = "")
    val ownerUserId: String,
    @ColumnInfo(name = "capsule_id")
    val capsuleId: String,
    @ColumnInfo(name = "side")
    val side: FingerprintSide,
    @ColumnInfo(name = "origin")
    val origin: FingerprintOrigin,
    @ColumnInfo(name = "fingerprint_profile_id")
    val fingerprintProfileId: String,
    @ColumnInfo(name = "encrypted_path")
    val encryptedPath: String,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "preferred")
    val preferred: Boolean,
)
