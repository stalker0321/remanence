package dev.hryshyn.remanence.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * T186 explicit v11 -> v12 migration: `local_account.handle_normalized`
 * becomes nullable so a receive-only account can defer its immutable handle
 * until first send (T184 contract). SQLite cannot alter column nullability
 * in place, so the table is rebuilt; every existing row (all handleful v1
 * accounts) is carried over byte-identical, keys and cursors untouched.
 */
val MIGRATION_11_12_NULLABLE_HANDLE = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_account_new` (
                `user_id` TEXT NOT NULL,
                `handle_normalized` TEXT,
                `active_key_bundle_id` TEXT NOT NULL,
                `registered_at_epoch_ms` INTEGER NOT NULL,
                `last_authenticated_at_epoch_ms` INTEGER NOT NULL,
                PRIMARY KEY(`user_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT INTO `local_account_new` " +
                "(`user_id`, `handle_normalized`, `active_key_bundle_id`, " +
                "`registered_at_epoch_ms`, `last_authenticated_at_epoch_ms`) " +
                "SELECT `user_id`, `handle_normalized`, `active_key_bundle_id`, " +
                "`registered_at_epoch_ms`, `last_authenticated_at_epoch_ms` " +
                "FROM `local_account`",
        )
        db.execSQL("DROP TABLE `local_account`")
        db.execSQL("ALTER TABLE `local_account_new` RENAME TO `local_account`")
    }
}
