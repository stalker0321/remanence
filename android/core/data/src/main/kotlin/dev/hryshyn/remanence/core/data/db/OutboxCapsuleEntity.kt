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
 *
 * M2-P02 account scoping: [ownerUserId] is the immutable local account that
 * owns this row (docs/architecture.md section 6). Capsule IDs stay globally
 * unique client-generated UUIDs - uniqueness constraints deliberately remain
 * capsule-scoped, NOT owner-composed, so a second account can never join its
 * blobs onto another account's capsule. Only the canonical migration policy
 * may write the empty sentinel: legacy v3 rows upgraded without exactly one
 * `local_account` row stay unattributed ('') and are unreachable through
 * every owner-scoped DAO primitive (fail-safe isolation, never guessed).
 *
 * M2-P08 schema-only continuation: [senderRetryKeysetPath] is an optional
 * app-private pointer to the on-disk account-scoped retry-material file
 * for THIS capsule. The column is NULL for every legacy v4 row and stays
 * NULL until the lifecycle (out of scope here) writes one. The pointer
 * is a local filesystem path ONLY - never keyset bytes, never the
 * recipient envelope, never a handle, never an email. Resolved reads
 * stay owner-scoped through the existing DAO contract.
 */
@Entity(
    tableName = "outbox_capsule",
    indices = [
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["owner_user_id"]),
    ],
)
data class OutboxCapsuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "capsule_id")
    val capsuleId: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    /** Immutable owning local account UUID string; '' only for legacy rows migrated without an attributable account. */
    @ColumnInfo(name = "owner_user_id", defaultValue = "")
    val ownerUserId: String,
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
    /**
     * M2-P08: app-private pointer to the on-disk account-scoped retry-
     * material file for this capsule, when one is set. NULL for every
     * pre-v5 row, and NULL until a future lifecycle step writes one.
     * The value is a local filesystem path ONLY - never keyset bytes,
     * never the recipient envelope, never a handle, never an email.
     * The column has no default at the SQLite layer; existing rows
     * migrate with NULL. Defaults to null at the data-class level so
     * existing callers (notably the production outbox stager) compile
     * and continue to write NULL for every row they stage.
     */
    @ColumnInfo(name = "sender_retry_keyset_path")
    val senderRetryKeysetPath: String? = null,
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String?,
)
