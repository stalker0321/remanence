package postmark.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

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
    ],
    version = 1,
    exportSchema = true,
)
abstract class PostmarkLocalDatabase : RoomDatabase() {
    abstract fun localAccountDao(): LocalAccountDao

    abstract fun incomingCapsuleDao(): IncomingCapsuleDao

    abstract fun incomingEnvelopeDao(): IncomingEnvelopeDao

    abstract fun blobCacheDao(): BlobCacheDao

    abstract fun outboxCapsuleDao(): OutboxCapsuleDao

    abstract fun outboxBlobDao(): OutboxBlobDao

    companion object {
        const val DATABASE_NAME: String = "postmark-local.db"
    }
}
