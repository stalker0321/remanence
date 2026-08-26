package dev.hryshyn.remanence.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The single HPKE recipient envelope for one incoming capsule, stored as
 * opaque ciphertext bytes plus the transport hash. One envelope per capsule.
 *
 * M2-P02 account scoping: [ownerUserId] binds this envelope to its immutable
 * local account ('' only for legacy rows the v3→v4 migration could not
 * attribute).
 */
@Entity(
    tableName = "incoming_envelope",
    indices = [
        Index(value = ["owner_user_id"]),
    ],
)
data class IncomingEnvelopeEntity(
    @PrimaryKey
    @ColumnInfo(name = "capsule_id")
    val capsuleId: String,
    /** Immutable owning local account UUID string. */
    @ColumnInfo(name = "owner_user_id", defaultValue = "")
    val ownerUserId: String,
    @ColumnInfo(name = "recipient_key_bundle_id")
    val recipientKeyBundleId: String,
    @ColumnInfo(name = "hpke_ciphertext")
    val hpkeCiphertext: ByteArray,
    @ColumnInfo(name = "transport_sha256")
    val transportSha256: ByteArray,
    @ColumnInfo(name = "received_at_epoch_ms")
    val receivedAtEpochMs: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is IncomingEnvelopeEntity &&
            capsuleId == other.capsuleId &&
            recipientKeyBundleId == other.recipientKeyBundleId &&
            hpkeCiphertext.contentEquals(other.hpkeCiphertext) &&
            transportSha256.contentEquals(other.transportSha256) &&
            receivedAtEpochMs == other.receivedAtEpochMs

    override fun hashCode(): Int {
        var result = capsuleId.hashCode()
        result = 31 * result + hpkeCiphertext.contentHashCode()
        return result
    }
}
