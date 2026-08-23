package postmark.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
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
class PostmarkLocalSchemaTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PostmarkLocalDatabase::class.java,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun exportedSchemaCreatesAndValidatesAtVersionOne() {
        helper.createDatabase(DB_NAME, 1).use { created ->
            assertTrue(created.isDatabaseIntegrityOk)
            created.close()
        }
        // Validates the reopened database against the current entity definitions.
        helper.runMigrationsAndValidate(DB_NAME, 1, true)
    }

    @Test
    fun exportedSchemaCoversAllM1Tables() {
        val schemaJson = String(
            context.assets.open("postmark.core.data.db.PostmarkLocalDatabase/1.json").readBytes(),
            Charsets.UTF_8,
        )
        for (table in listOf(
            "local_account",
            "incoming_capsule",
            "incoming_envelope",
            "blob_cache",
            "outbox_capsule",
            "outbox_blob",
            "recognition_fingerprint",
            "sync_cursor",
        )) {
            assertTrue("missing table $table", "\"tableName\": \"$table\"" in schemaJson || "\"tableName\":\"$table\"" in schemaJson)
        }
    }

    @Test
    fun reopenPersistsRowsAcrossClose(): Unit = runBlocking {
        val dbFile = context.getDatabasePath(REOPEN_DB_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        val first = Room.databaseBuilder(context, PostmarkLocalDatabase::class.java, REOPEN_DB_NAME)
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

        val second = Room.databaseBuilder(context, PostmarkLocalDatabase::class.java, REOPEN_DB_NAME)
            .addMigrations()
            .allowMainThreadQueries()
            .build()
        val loaded = second.localAccountDao().getAccount()
        second.close()
        assertEquals("mykola", loaded!!.handleNormalized)

        dbFile.delete()
    }

    private companion object {
        const val DB_NAME = "postmark-schema-test.db"
        const val REOPEN_DB_NAME = "postmark-reopen-test.db"
    }
}
