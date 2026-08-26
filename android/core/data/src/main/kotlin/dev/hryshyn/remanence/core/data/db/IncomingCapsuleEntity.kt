package dev.hryshyn.remanence.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Local material state of an incoming capsule (architecture section 6). */
enum class IncomingMaterialState {
    DISCOVERED,
    INDEX_CACHED,
    MATERIAL_CACHED,
    FINGERPRINT_ACCEPTED,
    CORRUPT,
}

/**
 * Routed metadata of one incoming ciphertext-only capsule. Contains no note,
 * place, thumbnail, chooser label, or any other plaintext content.
 */
@Entity(tableName = "incoming_capsule")
data class IncomingCapsuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "capsule_id")
    val capsuleId: String,
    @ColumnInfo(name = "sender_user_id")
    val senderUserId: String,
    @ColumnInfo(name = "recipient_user_id")
    val recipientUserId: String,
    @ColumnInfo(name = "sender_signing_key_bundle_id")
    val senderSigningKeyBundleId: String,
    @ColumnInfo(name = "recipient_encryption_key_bundle_id")
    val recipientEncryptionKeyBundleId: String,
    @ColumnInfo(name = "protocol_version")
    val protocolVersion: Int,
    @ColumnInfo(name = "server_status")
    val serverStatus: String,
    @ColumnInfo(name = "ready_at_epoch_ms")
    val readyAtEpochMs: Long,
    @ColumnInfo(name = "signed_statement_bytes")
    val signedStatementBytes: ByteArray,
    @ColumnInfo(name = "material_state")
    val materialState: IncomingMaterialState,
) {
    override fun equals(other: Any?): Boolean =
        other is IncomingCapsuleEntity && other.capsuleId == capsuleId

    override fun hashCode(): Int = capsuleId.hashCode()
}
