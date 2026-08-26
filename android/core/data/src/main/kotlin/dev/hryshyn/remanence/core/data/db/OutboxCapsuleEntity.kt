package dev.hryshyn.remanence.core.data.db

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
 *
 * FIX-REVIEW-04: the SENDER identity is persisted separately from the
 * RECIPIENT identity (immutable user IDs and key-bundle IDs plus the sender's
 * public signing keyset export) so verification never conflates the two.
 * Legacy same-account rows pre-dating v3 keep NULL here and consumers fall
 * back to the authenticated account - self-send stays natural without any
 * equality assumption.
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
    @ColumnInfo(name = "sender_user_id")
    val senderUserId: String?,
    @ColumnInfo(name = "recipient_user_id")
    val recipientUserId: String,
    @ColumnInfo(name = "sender_key_bundle_id")
    val senderKeyBundleId: String?,
    @ColumnInfo(name = "recipient_key_bundle_id")
    val recipientKeyBundleId: String,
    /** Sender Ed25519 PUBLIC keyset export (base64url); verification material only. */
    @ColumnInfo(name = "sender_signing_public_keyset_b64")
    val senderSigningPublicKeysetB64: String?,
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
