package dev.hryshyn.remanence.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.hryshyn.remanence.core.model.LocalMaterialState

/**
 * Routed metadata of one incoming ciphertext-only capsule. Contains no note,
 * place, thumbnail, chooser label, or any other plaintext content.
 *
 * M2-P02 account scoping: [ownerUserId] is the immutable local account this
 * delivery belongs to; owner-scoped DAO primitives are the only sanctioned
 * access path from M2 onward. The '' sentinel exists solely for legacy rows
 * the canonical v3→v4 migration could not attribute (see
 * [RemanenceLocalDatabase.MIGRATION_3_4]) and is invisible to every
 * owner-scoped query.
 */
@Entity(
    tableName = "incoming_capsule",
    indices = [
        Index(value = ["owner_user_id"]),
    ],
)
data class IncomingCapsuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "capsule_id")
    val capsuleId: String,
    /** Immutable owning local account UUID string. */
    @ColumnInfo(name = "owner_user_id", defaultValue = "")
    val ownerUserId: String,
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
    @ColumnInfo(name = "signed_statement_sha256", defaultValue = "X''")
    val signedStatementSha256: ByteArray = ByteArray(0),
    @ColumnInfo(name = "publish_signature_bytes", defaultValue = "X''")
    val publishSignatureBytes: ByteArray = ByteArray(0),
    @ColumnInfo(name = "material_state")
    val materialState: LocalMaterialState,
) {
    override fun equals(other: Any?): Boolean =
        other is IncomingCapsuleEntity && other.capsuleId == capsuleId

    override fun hashCode(): Int = capsuleId.hashCode()
}
