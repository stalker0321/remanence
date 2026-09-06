package dev.hryshyn.remanence.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Explicit v8 -> v9 migration for recipient tombstones and their watermark. */
val MIGRATION_8_9_RECIPIENT_TOMBSTONES = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `recipient_tombstone` (
                `owner_user_id` TEXT NOT NULL,
                `capsule_id` TEXT NOT NULL,
                `revoked_at_epoch_ms` INTEGER NOT NULL,
                PRIMARY KEY(`owner_user_id`, `capsule_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_recipient_tombstone_owner_user_id` " +
                "ON `recipient_tombstone` (`owner_user_id`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tombstone_watermark` (
                `owner_user_id` TEXT NOT NULL,
                `server_cursor` TEXT,
                `last_synced_at_epoch_ms` INTEGER NOT NULL,
                PRIMARY KEY(`owner_user_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tombstone_watermark_owner_user_id` " +
                "ON `tombstone_watermark` (`owner_user_id`)",
        )
    }
}
