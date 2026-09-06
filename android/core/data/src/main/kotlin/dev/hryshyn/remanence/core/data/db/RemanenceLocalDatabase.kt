package dev.hryshyn.remanence.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local infrastructure database. Contains no content plaintext, no gallery or
 * inbox projection. Version 9 adds durable recipient tombstone markers and
 * the account-scoped tombstone feed watermark. Version 10 adds bounded
 * owner-local exact FRONT duplicate history. The explicit migrations preserve
 * existing local material; unknown older paths still use the existing
 * destructive fallback policy.
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
        RecipientTombstoneEntity::class,
        TombstoneWatermarkEntity::class,
        LocalSendDuplicateEntity::class,
    ],
    version = 10,
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

    abstract fun recipientTombstoneDao(): RecipientTombstoneDao

    abstract fun incomingPageDao(): IncomingPageDao

    abstract fun localSendDuplicateDao(): LocalSendDuplicateDao

    abstract fun incomingIndexAcceptanceDao(): IncomingIndexAcceptanceDao

    abstract fun incomingPrefetchDao(): IncomingPrefetchDao

    companion object {
        const val DATABASE_NAME: String = "remanence-local.db"
    }
}
