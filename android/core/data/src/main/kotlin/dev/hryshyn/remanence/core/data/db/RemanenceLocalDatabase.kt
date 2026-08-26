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
    version = 3,
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
        const val DATABASE_NAME: String = "postmark-local.db"

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
    }
}
