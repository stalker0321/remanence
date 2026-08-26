package dev.hryshyn.remanence.core.data.db

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
class RemanenceLocalSchemaTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RemanenceLocalDatabase::class.java,
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
            RemanenceLocalDatabase.MIGRATION_1_2,
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
            RemanenceLocalDatabase.MIGRATION_2_3,
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
    fun exportedSchemaCreatesAndValidatesAtVersionFour() {
        helper.createDatabase(DB_V4_NAME, 4).use { created ->
            assertTrue(created.isDatabaseIntegrityOk)
            created.close()
        }
        // Validates the reopened database against the current entity definitions,
        // including every owner_user_id column and its NOT NULL DEFAULT ''.
        helper.runMigrationsAndValidate(DB_V4_NAME, 4, true)
    }

    /**
     * M2-P02 canonical attribution: the M1 device held exactly ONE local
     * account, so when exactly one `local_account` row exists at upgrade time
     * its immutable user ID is stamped onto EVERY legacy material row - the
     * only attribution the migration ever makes.
     */
    @Test
    fun migrationThreeToFourStampsLegacyRowsWithTheSingleLocalAccount() {
        helper.createDatabase(DB_STAMP_NAME, 3).use { v3 ->
            insertLegacyMaterialRows(v3)
            // Exactly one account: unambiguous ownership.
            v3.execSQL(
                "INSERT INTO local_account (user_id, handle_normalized, active_key_bundle_id, " +
                    "registered_at_epoch_ms, last_authenticated_at_epoch_ms) " +
                    "VALUES ('0198f0a0-0000-7000-8000-00000000ow01', 'mykola', 'bundle-a', 10, 11)",
            )
        }
        val migrated = helper.runMigrationsAndValidate(
            DB_STAMP_NAME,
            4,
            true,
            RemanenceLocalDatabase.MIGRATION_3_4,
        )
        for (table in SCOPED_TABLES) {
            migrated.query("SELECT DISTINCT owner_user_id FROM $table").use { cursor ->
                cursor.moveToFirst()
                assertEquals(
                    "table $table must carry the single account as its only owner",
                    "0198f0a0-0000-7000-8000-00000000ow01",
                    cursor.getString(0),
                )
            }
        }
        // Legacy data itself survived untouched.
        migrated.query("SELECT capsule_id, state FROM outbox_capsule").use { cursor ->
            cursor.moveToFirst()
            assertEquals("cap-l", cursor.getString(0))
            assertEquals("ENCRYPTED", cursor.getString(1))
        }
        assertTrue(migrated.isDatabaseIntegrityOk)
        migrated.close()
    }

    /**
     * Refusal-to-guess policy: without an attributable account (logged-out
     * device at upgrade time) every legacy row stays at the '' sentinel -
     * never misattributed, never deleted - and '' can never equal a real
     * owner UUID, so such rows are unreachable through owner-scoped queries.
     */
    @Test
    fun migrationThreeToFourLeavesRowsUnattributedWithoutALocalAccount() {
        helper.createDatabase(DB_UNATTRIBUTED_NAME, 3).use { v3 ->
            insertLegacyMaterialRows(v3)
        }
        val migrated = helper.runMigrationsAndValidate(
            DB_UNATTRIBUTED_NAME,
            4,
            true,
            RemanenceLocalDatabase.MIGRATION_3_4,
        )
        for (table in SCOPED_TABLES) {
            migrated.query("SELECT DISTINCT owner_user_id FROM $table").use { cursor ->
                cursor.moveToFirst()
                assertEquals("table $table must keep the '' sentinel", "", cursor.getString(0))
            }
        }
        assertTrue(migrated.isDatabaseIntegrityOk)
        migrated.close()
    }

    /**
     * Refusal-to-guess policy, adversarial variant: if the account table ever
     * held multiple rows the migration must NOT pick one; rows stay ''.
     */
    @Test
    fun migrationThreeToFourRefusesToGuessBetweenMultipleAccounts() {
        helper.createDatabase(DB_MULTI_ACCOUNT_NAME, 3).use { v3 ->
            insertLegacyMaterialRows(v3)
            v3.execSQL(
                "INSERT INTO local_account (user_id, handle_normalized, active_key_bundle_id, " +
                    "registered_at_epoch_ms, last_authenticated_at_epoch_ms) " +
                    "VALUES ('0198f0a0-0000-7000-8000-00000000ow01', 'mykola', 'bundle-a', 10, 11)",
            )
            v3.execSQL(
                "INSERT INTO local_account (user_id, handle_normalized, active_key_bundle_id, " +
                    "registered_at_epoch_ms, last_authenticated_at_epoch_ms) " +
                    "VALUES ('0198f0a0-0000-7000-8000-00000000ow02', 'other', 'bundle-b', 20, 21)",
            )
        }
        val migrated = helper.runMigrationsAndValidate(
            DB_MULTI_ACCOUNT_NAME,
            4,
            true,
            RemanenceLocalDatabase.MIGRATION_3_4,
        )
        for (table in SCOPED_TABLES) {
            migrated.query("SELECT DISTINCT owner_user_id FROM $table").use { cursor ->
                cursor.moveToFirst()
                assertEquals("table $table must stay unattributed", "", cursor.getString(0))
            }
        }
        assertTrue(migrated.isDatabaseIntegrityOk)
        migrated.close()
    }

    @Test
    fun exportedSchemaCoversAllM1Tables() {
        val schemaJson = String(
            context.assets.open("dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase/1.json").readBytes(),
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
            .addMigrations()
            .allowMainThreadQueries()
            .build()
        val loaded = second.localAccountDao().getAccount()
        second.close()
        assertEquals("mykola", loaded!!.handleNormalized)

        dbFile.delete()
    }

    private companion object {
        const val DB_NAME = "remanence-schema-test.db"
        const val DB_MIGRATION_NAME = "remanence-migration-test.db"
        const val DB_V3_NAME = "remanence-schema-v3-test.db"
        const val DB_V4_NAME = "remanence-schema-v4-test.db"
        const val REOPEN_DB_NAME = "remanence-reopen-test.db"
        const val DB_STAMP_NAME = "remanence-v3to4-stamp-test.db"
        const val DB_UNATTRIBUTED_NAME = "remanence-v3to4-unattributed-test.db"
        const val DB_MULTI_ACCOUNT_NAME = "remanence-v3to4-multi-account-test.db"

        /** Material tables that carry the immutable owning account from v4 on. */
        val SCOPED_TABLES =
            listOf(
                "outbox_capsule",
                "outbox_blob",
                "incoming_capsule",
                "incoming_envelope",
                "blob_cache",
                "recognition_fingerprint",
            )


        /** One valid legacy row per scoped material table, pre-v4 shapes. */
        fun insertLegacyMaterialRows(v3: androidx.sqlite.db.SupportSQLiteDatabase) {
            v3.execSQL(
                "INSERT INTO outbox_capsule (capsule_id, idempotency_key, sender_user_id, recipient_user_id, " +
                    "sender_key_bundle_id, recipient_key_bundle_id, sender_signing_public_keyset_b64, state, " +
                    "envelope_path, last_error_code) " +
                    "VALUES ('cap-l', 'idem-l', 'sender-l', 'recipient-l', 'sbundle-l', 'rbundle-l', NULL, " +
                    "'ENCRYPTED', '/tmp/env-l.bin', NULL)",
            )
            v3.execSQL(
                "INSERT INTO outbox_blob (blob_id, capsule_id, kind, ordinal, local_ciphertext_path, size_bytes, sha256, " +
                    "upload_state, attempt_count) VALUES ('blob-l', 'cap-l', 'PHOTO', 0, '/tmp/blob-l.bin', 10, x'00', 'PENDING', 0)",
            )
            v3.execSQL(
                "INSERT INTO incoming_capsule (capsule_id, sender_user_id, recipient_user_id, " +
                    "sender_signing_key_bundle_id, recipient_encryption_key_bundle_id, protocol_version, " +
                    "server_status, ready_at_epoch_ms, signed_statement_bytes, material_state) " +
                    "VALUES ('cap-i', 'sender-i', 'recipient-i', 'skb-i', 'rkb-i', 1, 'READY', 1, x'01', 'DISCOVERED')",
            )
            v3.execSQL(
                "INSERT INTO incoming_envelope (capsule_id, recipient_key_bundle_id, hpke_ciphertext, " +
                    "transport_sha256, received_at_epoch_ms) VALUES ('cap-i', 'rkb-i', x'02', x'03', 2)",
            )
            v3.execSQL(
                "INSERT INTO blob_cache (blob_id, capsule_id, kind, ordinal, expected_size_bytes, " +
                    "expected_sha256, local_path, cache_state) " +
                    "VALUES ('blc-l', 'cap-i', 'RECOGNITION_MANIFEST', NULL, 5, x'04', '/tmp/blc-l.bin', 'CACHED')",
            )
            v3.execSQL(
                "INSERT INTO recognition_fingerprint (fingerprint_id, capsule_id, side, origin, " +
                    "fingerprint_profile_id, encrypted_path, created_at_epoch_ms, preferred) " +
                    "VALUES ('fp-l', 'cap-i', 'FRONT', 'SENDER', 'mvp-orb-v1', 'fp/fp-l.bin', 3, 0)",
            )
        }
    }
}
