package postmark.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun migrationOneToTwoAddsOutboxStatementColumnsWithoutDataLoss() {
        helper.createDatabase(DB_MIGRATION_NAME, 1).use { v1 ->
            v1.execSQL(
                "INSERT INTO outbox_capsule (" +
                    "capsule_id, idempotency_key, recipient_user_id, recipient_key_bundle_id, state, " +
                    "recognition_manifest_path, content_manifest_path, envelope_path, last_error_code" +
                    ") VALUES ('cap-1', 'idem-1', 'recipient', 'bundle', 'ENCRYPTED', " +
                    "'/tmp/rec.bin', '/tmp/con.bin', '/tmp/env.bin', NULL)",
            )
            assertTrue(v1.isDatabaseIntegrityOk)
        }

        val migrated = helper.runMigrationsAndValidate(
            DB_MIGRATION_NAME,
            2,
            true,
            PostmarkLocalDatabase.MIGRATION_1_2,
        )
        // The pre-migration row survives and gains usable statement columns.
        migrated.query(
            "UPDATE outbox_capsule SET publish_statement_path = '/tmp/statement.bin', " +
                "publish_statement_signature_path = '/tmp/signature.bin' WHERE capsule_id = 'cap-1'",
        ).use { it.moveToFirst() }
        migrated.query("SELECT capsule_id, state FROM outbox_capsule").use { cursor ->
            cursor.moveToFirst()
            assertEquals("cap-1", cursor.getString(0))
            assertEquals("ENCRYPTED", cursor.getString(1))
        }
        migrated.query("SELECT publish_statement_path, publish_statement_signature_path FROM outbox_capsule").use { cursor ->
            cursor.moveToFirst()
            assertEquals("/tmp/statement.bin", cursor.getString(0))
            assertEquals("/tmp/signature.bin", cursor.getString(1))
        }
        migrated.close()
    }

    @Test
    fun migrationTwoToThreeSeparatesSenderIdentityWithoutDataLoss() {
        helper.createDatabase(DB_MIGRATION_NAME, 2).use { v2 ->
            v2.execSQL(
                "INSERT INTO outbox_capsule (" +
                    "capsule_id, idempotency_key, recipient_user_id, recipient_key_bundle_id, state, " +
                    "recognition_manifest_path, content_manifest_path, envelope_path, " +
                    "publish_statement_path, publish_statement_signature_path, last_error_code" +
                    ") VALUES ('cap-legacy', 'idem-legacy', 'recipient-uuid', 'recipient-bundle-uuid', " +
                    "'ENCRYPTED', NULL, NULL, '/tmp/env.bin', '/tmp/st.bin', '/tmp/sig.bin', NULL)",
            )
            assertTrue(v2.isDatabaseIntegrityOk)
        }

        val migrated = helper.runMigrationsAndValidate(
            DB_MIGRATION_NAME,
            3,
            true,
            PostmarkLocalDatabase.MIGRATION_2_3,
        )
        // The legacy row survives with NULL sender identity columns; consumers
        // fall back to the authenticated account for those rows.
        migrated.query("SELECT capsule_id, sender_user_id, sender_key_bundle_id, sender_signing_public_keyset_b64 FROM outbox_capsule")
            .use { cursor ->
                cursor.moveToFirst()
                assertEquals("cap-legacy", cursor.getString(0))
                assertNull(cursor.getString(1))
                assertNull(cursor.getString(2))
                assertNull(cursor.getString(3))
            }
        // Distinct sender identities can be persisted after migration.
        migrated.execSQL(
            "UPDATE outbox_capsule SET sender_user_id = 'sender-uuid', " +
                "sender_key_bundle_id = 'sender-bundle-uuid', " +
                "sender_signing_public_keyset_b64 = 'cHViaGlj' WHERE capsule_id = 'cap-legacy'",
        )
        migrated.query("SELECT sender_user_id, sender_key_bundle_id FROM outbox_capsule").use { cursor ->
            cursor.moveToFirst()
            assertEquals("sender-uuid", cursor.getString(0))
            assertEquals("sender-bundle-uuid", cursor.getString(1))
        }
        assertTrue(migrated.isDatabaseIntegrityOk)
        migrated.close()
    }

    @Test
    fun exportedSchemaCreatesAndValidatesAtVersionThree() {
        helper.createDatabase(DB_V3_NAME, 3).use { created ->
            assertTrue(created.isDatabaseIntegrityOk)
            created.close()
        }
        // Validates the reopened database against the current entity definitions.
        helper.runMigrationsAndValidate(DB_V3_NAME, 3, true)
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
        const val DB_MIGRATION_NAME = "postmark-migration-test.db"
        const val DB_V3_NAME = "postmark-schema-v3-test.db"
        const val REOPEN_DB_NAME = "postmark-reopen-test.db"
    }
}
