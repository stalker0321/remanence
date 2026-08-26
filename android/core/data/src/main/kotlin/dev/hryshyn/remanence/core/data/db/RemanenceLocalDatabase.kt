package dev.hryshyn.remanence.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Local infrastructure database. Contains no content plaintext, no gallery or
 * inbox projection, and grows only through explicit versioned migrations.
 */
@Database(
    entities = [
        LocalAccountEntity::class,
        IncomingCapsuleEntity::class,
        IncomingEnvelopeEntity::class,
        BlobCacheEntity::class,
        OutboxCapsuleEntity::class,
        OutboxBlobEntity::class,
        RecognitionFingerprintEntity::class,
        SyncCursorEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class RemanenceLocalDatabase : RoomDatabase() {
    abstract fun localAccountDao(): LocalAccountDao

    abstract fun incomingCapsuleDao(): IncomingCapsuleDao

    abstract fun incomingEnvelopeDao(): IncomingEnvelopeDao

    abstract fun blobCacheDao(): BlobCacheDao

    abstract fun outboxCapsuleDao(): OutboxCapsuleDao

    abstract fun outboxBlobDao(): OutboxBlobDao

    abstract fun recognitionFingerprintDao(): RecognitionFingerprintDao

    abstract fun syncCursorDao(): SyncCursorDao

    companion object {
        const val DATABASE_NAME: String = "remanence-local.db"

        /** v2 adds the signed publish statement/signature to the outbox capsule. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outbox_capsule ADD COLUMN publish_statement_path TEXT")
                db.execSQL(
                    "ALTER TABLE outbox_capsule ADD COLUMN publish_statement_signature_path TEXT",
                )
            }
        }

        /**
         * v3 (FIX-REVIEW-04) separates the sender identity from the recipient
         * identity on every persisted outbox capsule. Legacy rows keep NULL
         * and consumers fall back to the authenticated account, so the M1
         * same-account flow keeps working without an equality assumption.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outbox_capsule ADD COLUMN sender_user_id TEXT")
                db.execSQL("ALTER TABLE outbox_capsule ADD COLUMN sender_key_bundle_id TEXT")
                db.execSQL(
                    "ALTER TABLE outbox_capsule ADD COLUMN sender_signing_public_keyset_b64 TEXT",
                )
            }
        }

        /**
         * v4 (M2-P02) adds the immutable owning local account to every capsule
         * material row: `owner_user_id` on outbox_capsule, outbox_blob,
         * incoming_capsule, incoming_envelope, blob_cache, and
         * recognition_fingerprint. sync_cursor is untouched - it already keys
         * ownership as half of its primary key.
         *
         * Canonical legacy-attribution policy (docs/architecture.md section 6:
         * every record is scoped to the authenticated local account; retained
         * rows remain inaccessible to another account):
         *
         * 1. Every pre-v4 column starts as the NOT-NULL sentinel ''.
         * 2. The M1 device held exactly ONE local account. When and only when
         *    exactly one `local_account` row exists at migration time, that
         *    account's immutable user ID is stamped onto ALL legacy rows -
         *    there is nothing else they could belong to, and this is the only
         *    attribution the migration will ever make.
         * 3. With zero or multiple account rows the migration refuses to
         *    guess: rows stay ''. '' can never equal a valid UUID string, so
         *    unattributed rows are unreachable through every owner-scoped DAO
         *    primitive (fail-safe isolation; never misattributed, never
         *    deleted silently).
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outbox_capsule ADD COLUMN owner_user_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE outbox_blob ADD COLUMN owner_user_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE incoming_capsule ADD COLUMN owner_user_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE incoming_envelope ADD COLUMN owner_user_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE blob_cache ADD COLUMN owner_user_id TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "ALTER TABLE recognition_fingerprint ADD COLUMN owner_user_id TEXT NOT NULL DEFAULT ''",
                )

                // Single-account attribution: stamp ONLY under the exact
                // count==1 precondition. COUNT(*)>1 keeps the '' sentinel.
                for (table in SCOPED_TABLES) {
                    db.execSQL(
                        "UPDATE $table SET owner_user_id = (SELECT user_id FROM local_account) " +
                            "WHERE (SELECT COUNT(*) FROM local_account) = 1",
                    )
                }

                // The v4 entities carry owner indices; the migrated schema
                // must match Room's expected names exactly.
                for (table in SCOPED_TABLES) {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_${table}_owner_user_id` " +
                            "ON `$table` (`owner_user_id`)",
                    )
                }
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_blob_cache_owner_user_id_capsule_id` " +
                        "ON `blob_cache` (`owner_user_id`, `capsule_id`)",
                )
            }
        }

        /** Material tables that carry the immutable owning account from v4 on. */
        private val SCOPED_TABLES =
            listOf(
                "outbox_capsule",
                "outbox_blob",
                "incoming_capsule",
                "incoming_envelope",
                "blob_cache",
                "recognition_fingerprint",
            )
    }
}
