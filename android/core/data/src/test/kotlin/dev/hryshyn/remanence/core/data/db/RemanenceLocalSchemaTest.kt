package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemanenceLocalSchemaTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RemanenceLocalDatabase::class.java,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun reopenPersistsRowsAcrossClose(): Unit = runBlocking {
        val dbFile = context.getDatabasePath(REOPEN_DB_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        val first = Room.databaseBuilder(context, RemanenceLocalDatabase::class.java, REOPEN_DB_NAME)
            .allowMainThreadQueries()
            .build()
        first.localAccountDao().replaceAccount(
            LocalAccountEntity(
                userId = "0198f0a0-0000-7000-8000-00000000us01",
                handleNormalized = "mykola",
                activeKeyBundleId = "0198f0a0-0000-7000-8000-00000000ba01",
                registeredAtEpochMs = 42,
                lastAuthenticatedAtEpochMs = 43,
            ),
        )
        first.close()

        val second = Room.databaseBuilder(context, RemanenceLocalDatabase::class.java, REOPEN_DB_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .allowMainThreadQueries()
            .build()
        val loaded = second.localAccountDao().getAccount()
        second.close()
        assertEquals("mykola", loaded!!.handleNormalized)

        dbFile.delete()
    }

    @Test
    fun explicitV8ToV9MigrationCreatesRecipientTombstoneTables() {
        val legacy = migrationHelper.createDatabase(MIGRATION_DB_NAME, 8)
        legacy.close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            MIGRATION_DB_NAME,
            9,
            true,
            MIGRATION_8_9_RECIPIENT_TOMBSTONES,
        )
        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name IN ('recipient_tombstone', 'tombstone_watermark') " +
                "ORDER BY name",
        ).use { cursor ->
            val names = buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            assertEquals(listOf("recipient_tombstone", "tombstone_watermark"), names)
        }
        migrated.close()
    }

    @Test
    fun explicitV9ToV10MigrationCreatesExactDuplicateTableAndIndexes() {
        val legacy = migrationHelper.createDatabase(MIGRATION_V9_DB_NAME, 9)
        legacy.close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            MIGRATION_V9_DB_NAME,
            10,
            true,
            MIGRATION_9_10_LOCAL_SEND_DUPLICATES,
        )
        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name = 'local_send_duplicate'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("local_send_duplicate", cursor.getString(0))
        }
        migrated.query("PRAGMA index_list('local_send_duplicate')").use { cursor ->
            val indexNames = buildList {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
            assertTrue(indexNames.contains("index_local_send_duplicate_owner_user_id_front_sha256"))
        }
        migrated.close()
    }

    private companion object {
        const val REOPEN_DB_NAME = "remanence-reopen-test.db"
        const val MIGRATION_DB_NAME = "remanence-tombstone-migration-test.db"
        const val MIGRATION_V9_DB_NAME = "remanence-exact-duplicate-migration-test.db"
    }
}
