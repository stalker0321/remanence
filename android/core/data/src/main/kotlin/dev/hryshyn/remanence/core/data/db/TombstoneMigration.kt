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

/** Explicit v9 -> v10 migration for owner-local exact duplicate history. */
val MIGRATION_9_10_LOCAL_SEND_DUPLICATES = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_send_duplicate` (
                `reservation_id` TEXT NOT NULL,
                `owner_user_id` TEXT NOT NULL,
                `front_sha256` BLOB NOT NULL,
                `capsule_id` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `created_at_epoch_ms` INTEGER NOT NULL,
                `reservation_expires_at_epoch_ms` INTEGER NOT NULL,
                PRIMARY KEY(`reservation_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_send_duplicate_owner_user_id_front_sha256` " +
                "ON `local_send_duplicate` (`owner_user_id`, `front_sha256`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_send_duplicate_owner_user_id_state_created_at_epoch_ms_reservation_id` " +
                "ON `local_send_duplicate` " +
                "(`owner_user_id`, `state`, `created_at_epoch_ms`, `reservation_id`)",
        )
    }
}
