package postmark.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Sender-side capsule lifecycle (protocol.md section 9). */
enum class OutboxCapsuleState {
    PREPARING,
    ENCRYPTED,
    UPLOADING,
    FINALIZING,
    PUBLISHED,
    RETRYABLE_FAILURE,
    TERMINAL_FAILURE,
}

/**
 * One outgoing ciphertext-only capsule. Holds routing snapshots, the signed
 * publish statement plus signature (public REST material required to finalize
 * after any process restart), and encrypted artifact paths only: never
 * plaintext note text or image bytes.
 */
@Entity(
    tableName = "outbox_capsule",
    indices = [
        Index(value = ["idempotency_key"], unique = true),
    ],
)
data class OutboxCapsuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "capsule_id")
    val capsuleId: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "recipient_user_id")
    val recipientUserId: String,
    @ColumnInfo(name = "recipient_key_bundle_id")
    val recipientKeyBundleId: String,
    @ColumnInfo(name = "state")
    val state: OutboxCapsuleState,
    @ColumnInfo(name = "recognition_manifest_path")
    val recognitionManifestPath: String?,
    @ColumnInfo(name = "content_manifest_path")
    val contentManifestPath: String?,
    @ColumnInfo(name = "envelope_path")
    val envelopePath: String?,
    @ColumnInfo(name = "publish_statement_path")
    val publishStatementPath: String?,
    @ColumnInfo(name = "publish_statement_signature_path")
    val publishStatementSignaturePath: String?,
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String?,
)
