package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hryshyn.remanence.core.model.LocalMaterialState
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

    @Test
    fun exportedSchemaCreatesAndValidatesAtVersionFive() {
        helper.createDatabase(DB_V5_NAME, 5).use { created ->
            assertTrue(created.isDatabaseIntegrityOk)
            created.close()
        }
        // Validates the reopened database against the v5 entity
        // definitions, including the new sender_retry_keyset_path
        // column on outbox_capsule.
        helper.runMigrationsAndValidate(DB_V5_NAME, 5, true)
    }

    @Test
    fun exportedSchemaCreatesAndValidatesAtVersionSeven() {
        helper.createDatabase(DB_V7_NAME, 7).use { created ->
            assertTrue(created.isDatabaseIntegrityOk)
            created.execSQL(
                "INSERT INTO incoming_capsule (" +
                    "capsule_id, owner_user_id, sender_user_id, recipient_user_id, " +
                    "sender_signing_key_bundle_id, recipient_encryption_key_bundle_id, " +
                    "protocol_version, server_status, ready_at_epoch_ms, signed_statement_bytes, " +
                    "signed_statement_sha256, publish_signature_bytes, material_state" +
                    ") VALUES ('fresh-ack', 'owner-fresh', 'sender-fresh', 'recipient-fresh', " +
                    "'sender-key-fresh', 'recipient-key-fresh', 1, 'READY', 456, x'01', " +
                    "x'02', x'03', 'MATERIAL_CACHED')",
            )
            created.query("SELECT material_ack_state FROM incoming_capsule WHERE capsule_id = 'fresh-ack'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("PENDING", cursor.getString(0))
            }
        }
        helper.runMigrationsAndValidate(DB_V7_NAME, 7, true)
    }

    @Test
    fun migrationFiveToSixAddsIncomingSignatureMaterialWithoutInventingBytes() {
        helper.createDatabase(DB_MIGRATION_5_6_NAME, 5).use { v5 ->
            v5.execSQL(
                "INSERT INTO incoming_capsule (" +
                    "capsule_id, owner_user_id, sender_user_id, recipient_user_id, " +
                    "sender_signing_key_bundle_id, recipient_encryption_key_bundle_id, " +
                    "protocol_version, server_status, ready_at_epoch_ms, " +
                    "signed_statement_bytes, material_state" +
                    ") VALUES ('legacy-incoming', 'owner', 'sender', 'recipient', " +
                    "'sender-key', 'recipient-key', 1, 'READY', 1, x'01', 'DISCOVERED')",
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            DB_MIGRATION_5_6_NAME,
            6,
            true,
            RemanenceLocalDatabase.MIGRATION_5_6,
        )
        migrated.query(
            "SELECT signed_statement_sha256, publish_signature_bytes " +
                "FROM incoming_capsule WHERE capsule_id = 'legacy-incoming'",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getBlob(0).size)
            assertEquals(0, cursor.getBlob(1).size)
        }
        assertTrue(migrated.isDatabaseIntegrityOk)
        migrated.close()
    }

    @Test
    fun migrationSixToSevenAddsPendingAckStateWithoutDataLoss() {
        helper.createDatabase(DB_MIGRATION_6_7_NAME, 6).use { v6 ->
            v6.execSQL(
                "INSERT INTO incoming_capsule (" +
                    "capsule_id, owner_user_id, sender_user_id, recipient_user_id, " +
                    "sender_signing_key_bundle_id, recipient_encryption_key_bundle_id, " +
                    "protocol_version, server_status, ready_at_epoch_ms, signed_statement_bytes, " +
                    "signed_statement_sha256, publish_signature_bytes, material_state" +
                    ") VALUES ('legacy-ack', 'owner-ack', 'sender-ack', 'recipient-ack', " +
                    "'sender-key-ack', 'recipient-key-ack', 1, 'READY', 123, x'0102', " +
                    "x'0304', x'0506', 'MATERIAL_CACHED')",
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            DB_MIGRATION_6_7_NAME,
            7,
            true,
            RemanenceLocalDatabase.MIGRATION_6_7,
        )
        migrated.query(
            "SELECT owner_user_id, server_status, ready_at_epoch_ms, signed_statement_bytes, " +
                "signed_statement_sha256, publish_signature_bytes, material_state, material_ack_state " +
                "FROM incoming_capsule WHERE capsule_id = 'legacy-ack'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("owner-ack", cursor.getString(0))
            assertEquals("READY", cursor.getString(1))
            assertEquals(123L, cursor.getLong(2))
            assertEquals(byteArrayOf(1, 2).toList(), cursor.getBlob(3).toList())
            assertEquals(byteArrayOf(3, 4).toList(), cursor.getBlob(4).toList())
            assertEquals(byteArrayOf(5, 6).toList(), cursor.getBlob(5).toList())
            assertEquals("MATERIAL_CACHED", cursor.getString(6))
            assertEquals("PENDING", cursor.getString(7))
        }
        assertTrue(migrated.isDatabaseIntegrityOk)
        migrated.close()
    }

    /**
     * The Room enum type moved from core:data to core:model, but its persisted
     * names did not change. Prove the v5 column remains compatible without a
     * schema version bump or migration.
     */
    @Test
    fun localMaterialStateTypeChangePreservesV5TextStorageAndNames() {
        helper.createDatabase(DB_LOCAL_STATE_NAME, 5).use { v5 ->
            var materialStateColumnIsText = false
            v5.query("PRAGMA table_info(`incoming_capsule`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val typeIndex = cursor.getColumnIndexOrThrow("type")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == "material_state") {
                        assertEquals("TEXT", cursor.getString(typeIndex))
                        materialStateColumnIsText = true
                    }
                }
            }
            assertTrue(materialStateColumnIsText)

            for ((index, state) in LocalMaterialState.entries.withIndex()) {
                v5.execSQL(
                    "INSERT INTO incoming_capsule (" +
                        "capsule_id, owner_user_id, sender_user_id, recipient_user_id, " +
                        "sender_signing_key_bundle_id, recipient_encryption_key_bundle_id, " +
                        "protocol_version, server_status, ready_at_epoch_ms, " +
                        "signed_statement_bytes, material_state" +
                        ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(
                        "state-cap-$index",
                        "owner-state-test",
                        "sender-state-test",
                        "recipient-state-test",
                        "sender-key-state-test",
                        "recipient-key-state-test",
                        1,
                        "READY",
                        1_755_000_000_000L,
                        byteArrayOf(1, 2, 3),
                        state.name,
                    ),
                )
            }

            val storedNames = mutableListOf<String>()
            v5.query("SELECT material_state FROM incoming_capsule ORDER BY capsule_id").use { cursor ->
                while (cursor.moveToNext()) storedNames += cursor.getString(0)
            }
            assertEquals(LocalMaterialState.entries.map { it.name }, storedNames)
            assertTrue(v5.isDatabaseIntegrityOk)
        }
        helper.runMigrationsAndValidate(DB_LOCAL_STATE_NAME, 5, true)
    }

    /**
     * M2-P08 wiring regression: the full production migration chain
     * (1→2→3→4→5→6→7) must produce a v7 schema without destructive fallback.
     * A v1 database carrying pre-M2 rows is migrated through ALL six
     * migrations, and the final schema must be valid at version 7 and
     * must contain both the sender_retry_keyset_path and material_ack_state
     * columns.
     *
     * This is NOT a per-migration test; it proves the registration
     * list in production is complete.
     */
    @Test
    fun fullMigrationChainReachesV7WithoutDestructiveFallback() {
        val dbName = "remanence-full-chain-v1-to-v7.db"
        helper.createDatabase(dbName, 1).use { v1 ->
            // Insert a minimal pre-M2 row so we can verify it survives
            // the entire chain without being destroyed.
            v1.execSQL(
                "INSERT INTO outbox_capsule " +
                    "(capsule_id, idempotency_key, recipient_user_id, recipient_key_bundle_id, " +
                    "state, recognition_manifest_path, content_manifest_path, envelope_path, " +
                    "last_error_code) " +
                    "VALUES ('chain-cap-1', 'chain-idem-1', 'recipient', 'rbundle', 'ENCRYPTED', " +
                    "'/tmp/rec.bin', '/tmp/con.bin', '/tmp/env.bin', NULL)",
            )
            v1.execSQL(
                "INSERT INTO outbox_blob " +
                    "(blob_id, capsule_id, kind, ordinal, local_ciphertext_path, " +
                    "size_bytes, sha256, upload_state, attempt_count) " +
                    "VALUES ('chain-blob-1', 'chain-cap-1', 'PHOTO', 0, '/tmp/blob.bin', " +
                    "10, x'00', 'PENDING', 0)",
            )
            v1.close()
        }

        // Run the complete migration chain — exactly the same list that
        // the production Room.databaseBuilder registers (including the
        // v7 local material-ack progress migration). If any migration is missing or
        // incorrect, runMigrationsAndValidate will throw.
        val migrated = helper.runMigrationsAndValidate(
            dbName,
            7,
            true,
            RemanenceLocalDatabase.MIGRATION_1_2,
            RemanenceLocalDatabase.MIGRATION_2_3,
            RemanenceLocalDatabase.MIGRATION_3_4,
            RemanenceLocalDatabase.MIGRATION_4_5,
            RemanenceLocalDatabase.MIGRATION_5_6,
            RemanenceLocalDatabase.MIGRATION_6_7,
        )
        assertTrue(migrated.isDatabaseIntegrityOk)

        // Verify the v1 row survived: it now has owner_user_id
        // (from MIGRATION_3_4), sender columns (from MIGRATION_2_3),
        // statement columns (from MIGRATION_1_2), and a NULL
        // sender_retry_keyset_path (from MIGRATION_4_5).
        migrated.query("SELECT * FROM outbox_capsule WHERE capsule_id = 'chain-cap-1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("ENCRYPTED", cursor.getString(cursor.getColumnIndexOrThrow("state")))
            assertEquals("/tmp/env.bin", cursor.getString(cursor.getColumnIndexOrThrow("envelope_path")))
            // M2-P02: owner_user_id was stamped by MIGRATION_3_4.
            val ownerIdx = cursor.getColumnIndexOrThrow("owner_user_id")
            assertTrue("owner_user_id must exist after migration", ownerIdx >= 0)
            // M1 statement columns survived.
            val stmtIdx = cursor.getColumnIndexOrThrow("publish_statement_path")
            assertTrue("publish_statement_path must exist after migration", stmtIdx >= 0)
            // M2-P08: sender_retry_keyset_path is NULL for the legacy row.
            val retryIdx = cursor.getColumnIndexOrThrow("sender_retry_keyset_path")
            assertTrue("sender_retry_keyset_path must exist at v7", retryIdx >= 0)
            assertNull(cursor.getString(retryIdx))
        }

        // Verify outbox_blob row survived.
        migrated.query("SELECT * FROM outbox_blob WHERE blob_id = 'chain-blob-1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("PENDING", cursor.getString(cursor.getColumnIndexOrThrow("upload_state")))
        }

        migrated.query("PRAGMA table_info(`incoming_capsule`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            var foundAckState = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "material_ack_state") {
                    foundAckState = true
                    assertEquals("'PENDING'", cursor.getString(defaultIndex))
                }
            }
            assertTrue("material_ack_state must exist at v7", foundAckState)
        }
    }

    /**
     * M2-P08 schema-only continuation: every pre-v5 row must survive
     * the migration with the new sender_retry_keyset_path column
     * set to NULL. The column has no SQL default; the existing rows
     * land at NULL because the ALTER adds a nullable column.
     */
    @Test
    fun migrationFourToFiveAddsNullableSenderRetryPointerPreservingAllData() {
        helper.createDatabase(DB_MIGRATION_4_5_NAME, 4).use { v4 ->
            // One row per logical outbox material: a v4-shaped
            // outbox_capsule carrying the M2-P02 owner attribution
            // and the M1 publisher material paths.
            v4.execSQL(
                "INSERT INTO local_account (user_id, handle_normalized, active_key_bundle_id, " +
                    "registered_at_epoch_ms, last_authenticated_at_epoch_ms) " +
                    "VALUES ('0198f0a0-0000-7000-8000-00000000ow01', 'mykola', 'bundle-a', 10, 11)",
            )
            v4.execSQL(
                "INSERT INTO outbox_capsule (capsule_id, idempotency_key, owner_user_id, " +
                    "sender_user_id, recipient_user_id, sender_key_bundle_id, recipient_key_bundle_id, " +
                    "sender_signing_public_keyset_b64, state, " +
                    "recognition_manifest_path, content_manifest_path, envelope_path, " +
                    "publish_statement_path, publish_statement_signature_path, last_error_code) " +
                    "VALUES ('cap-v4', 'idem-v4', '0198f0a0-0000-7000-8000-00000000ow01', " +
                    "'sender-v4', 'recipient-v4', 'sbundle-v4', 'rbundle-v4', " +
                    "'cHViaGljLWtleXNldA', 'ENCRYPTED', " +
                    "'/tmp/rec-v4.bin', '/tmp/con-v4.bin', '/tmp/env-v4.bin', " +
                    "'/tmp/st-v4.bin', '/tmp/sig-v4.bin', NULL)",
            )
            assertTrue(v4.isDatabaseIntegrityOk)
        }

        val migrated = helper.runMigrationsAndValidate(
            DB_MIGRATION_4_5_NAME,
            5,
            true,
            RemanenceLocalDatabase.MIGRATION_4_5,
        )

        // The pre-v5 row survives with every existing column intact
        // and the new sender_retry_keyset_path column at NULL.
        migrated.query(
            "SELECT capsule_id, owner_user_id, state, publish_statement_path, " +
                "sender_retry_keyset_path FROM outbox_capsule",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("cap-v4", cursor.getString(0))
            assertEquals("0198f0a0-0000-7000-8000-00000000ow01", cursor.getString(1))
            assertEquals("ENCRYPTED", cursor.getString(2))
            assertEquals("/tmp/st-v4.bin", cursor.getString(3))
            // Nullable column with no SQL default: the legacy row's
            // pointer is NULL after the migration, exactly as
            // documented.
            assertNull(cursor.getString(4))
        }

        // A non-null pointer can be written and read back through
        // the post-migration schema; the migration did not lock the
        // column at a constant.
        migrated.execSQL(
            "UPDATE outbox_capsule SET sender_retry_keyset_path = " +
                "'/files/accounts/0198f0a0-0000-7000-8000-00000000ow01/retry-material/cap-v4.bin' " +
                "WHERE capsule_id = 'cap-v4'",
        )
        migrated.query("SELECT sender_retry_keyset_path FROM outbox_capsule").use { cursor ->
            cursor.moveToFirst()
            assertEquals(
                "/files/accounts/0198f0a0-0000-7000-8000-00000000ow01/retry-material/cap-v4.bin",
                cursor.getString(0),
            )
        }
        assertTrue(migrated.isDatabaseIntegrityOk)
        migrated.close()
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
        const val DB_V5_NAME = "remanence-schema-v5-test.db"
        const val DB_V7_NAME = "remanence-schema-v7-test.db"
        const val DB_LOCAL_STATE_NAME = "remanence-local-state-schema-test.db"
        const val REOPEN_DB_NAME = "remanence-reopen-test.db"
        const val DB_STAMP_NAME = "remanence-v3to4-stamp-test.db"
        const val DB_UNATTRIBUTED_NAME = "remanence-v3to4-unattributed-test.db"
        const val DB_MULTI_ACCOUNT_NAME = "remanence-v3to4-multi-account-test.db"
        const val DB_MIGRATION_4_5_NAME = "remanence-v4to5-migration-test.db"
        const val DB_MIGRATION_5_6_NAME = "remanence-v5to6-migration-test.db"
        const val DB_MIGRATION_6_7_NAME = "remanence-v6to7-migration-test.db"

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
