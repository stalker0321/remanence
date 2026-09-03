package dev.hryshyn.remanence.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local infrastructure database. Contains no content plaintext, no gallery or
 * inbox projection. Version 8 is a clean-reset schema; old local rows are
 * intentionally disposable and no legacy migration path is registered.
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
    version = 8,
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

    abstract fun incomingPageDao(): IncomingPageDao

    abstract fun incomingIndexAcceptanceDao(): IncomingIndexAcceptanceDao

    abstract fun incomingPrefetchDao(): IncomingPrefetchDao

    companion object {
        const val DATABASE_NAME: String = "remanence-local.db"
    }
}
