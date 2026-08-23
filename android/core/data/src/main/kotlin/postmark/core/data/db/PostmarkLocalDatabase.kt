package postmark.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local infrastructure database. Contains no content plaintext, no gallery or
 * inbox projection, and grows only through explicit versioned migrations.
 */
@Database(
    entities = [LocalAccountEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PostmarkLocalDatabase : RoomDatabase() {
    abstract fun localAccountDao(): LocalAccountDao

    companion object {
        const val DATABASE_NAME: String = "postmark-local.db"
    }
}
