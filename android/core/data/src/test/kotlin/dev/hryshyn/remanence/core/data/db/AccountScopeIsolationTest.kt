package dev.hryshyn.remanence.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.core.model.LocalMaterialState
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M2-P02 A/B isolation proof for the owner-bearing local substrate
 * (docs/architecture.md section 6): account B's owner-scoped primitives can
 * neither observe nor mutate any of account A's material, colliding logical
 * IDs are permitted exactly where architecture says so (cursor streams, keyed
 * by `(user_id, stream_name)`), capsule/blob/fingerprint identity stays a
 * globally unique client-generated UUID so no cross-owner join or duplicate
 * relationship can exist, and legacy '' sentinel rows are invisible to every
 * real-owner query.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountScopeIsolationTest {

    private val ownerA = "0198f0a0-0000-7000-8000-00000000ow01"
    private val ownerB = "0198f0a0-0000-7000-8000-00000000ow02"
    private val capsuleA = "0198f0a0-0000-7000-8000-00000000ca01"
    private val blobA1 = "0198f0a0-0000-7000-8000-00000000bl01"
    private val blobA2 = "0198f0a0-0000-7000-8000-00000000bl02"

    private lateinit var database: RemanenceLocalDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun outboxCapsule(
        capsuleId: String,
        ownerUserId: String,
        state: OutboxCapsuleState = OutboxCapsuleState.ENCRYPTED,
    ) = OutboxCapsuleEntity(
        capsuleId = capsuleId,
        idempotencyKey = "idem-$capsuleId",
        ownerUserId = ownerUserId,
        senderUserId = "0198f0a0-0000-7000-8000-00000000se01",
        recipientUserId = "0198f0a0-0000-7000-8000-00000000re01",
        senderKeyBundleId = "0198f0a0-0000-7000-8000-00000000sk01",
        recipientKeyBundleId = "0198f0a0-0000-7000-8000-00000000rk01",
        senderSigningPublicKeysetB64 = null,
        state = state,
        recognitionManifestPath = null,
        contentManifestPath = null,
        envelopePath = "/tmp/env-$capsuleId.bin",
        publishStatementPath = "/tmp/st-$capsuleId.bin",
        publishStatementSignaturePath = "/tmp/sig-$capsuleId.bin",
        lastErrorCode = null,
    )

    private fun outboxBlob(blobId: String, capsuleId: String, ownerUserId: String) = OutboxBlobEntity(
        blobId = blobId,
        ownerUserId = ownerUserId,
        capsuleId = capsuleId,
        kind = "PHOTO",
        ordinal = 0,
        localCiphertextPath = "files/outbox/$capsuleId/$blobId.bin",
        sizeBytes = 1_024,
        sha256 = ByteArray(32) { (it + 3).toByte() },
        uploadState = OutboxBlobUploadState.PENDING,
        attemptCount = 0,
    )

    private fun incomingCapsule(capsuleId: String, ownerUserId: String) = IncomingCapsuleEntity(
        capsuleId = capsuleId,
        ownerUserId = ownerUserId,
        senderUserId = "0198f0a0-0000-7000-8000-00000000se01",
        recipientUserId = ownerUserId,
        senderSigningKeyBundleId = "0198f0a0-0000-7000-8000-00000000sk01",
        recipientEncryptionKeyBundleId = "0198f0a0-0000-7000-8000-00000000rk01",
        protocolVersion = 1,
        serverStatus = "READY",
        readyAtEpochMs = 1_755_000_000_000,
        signedStatementBytes = byteArrayOf(1, 2, 3),
        materialState = LocalMaterialState.DISCOVERED,
    )

    private fun incomingEnvelope(capsuleId: String, ownerUserId: String) = IncomingEnvelopeEntity(
        capsuleId = capsuleId,
        ownerUserId = ownerUserId,
        recipientKeyBundleId = "0198f0a0-0000-7000-8000-00000000rk01",
        hpkeCiphertext = byteArrayOf(9, 8, 7),
        transportSha256 = ByteArray(32) { 4 },
        receivedAtEpochMs = 1_755_000_100_000,
    )

    private fun blobCache(blobId: String, capsuleId: String, ownerUserId: String) = BlobCacheEntity(
        blobId = blobId,
        ownerUserId = ownerUserId,
        capsuleId = capsuleId,
        kind = "PHOTO",
        ordinal = 0,
        expectedSizeBytes = 8_192,
        expectedSha256 = ByteArray(32) { (it + 7).toByte() },
        localPath = "files/capsules/$capsuleId/$blobId.bin",
        cacheState = BlobCacheState.DOWNLOADING,
    )

    private fun fingerprint(id: String, capsuleId: String, ownerUserId: String) =
        RecognitionFingerprintEntity(
            fingerprintId = id,
            ownerUserId = ownerUserId,
            capsuleId = capsuleId,
            side = FingerprintSide.FRONT,
            origin = FingerprintOrigin.SENDER,
            fingerprintProfileId = "mvp-orb-v1",
            encryptedPath = "files/fingerprints/$id.bin",
            createdAtEpochMs = 1_755_000_000_000,
            preferred = false,
        )

    private suspend fun seedAccountAMaterial() {
        database.outboxCapsuleDao().insertOrAbort(outboxCapsule(capsuleA, ownerA))
        database.outboxBlobDao().upsertAll(listOf(outboxBlob(blobA1, capsuleA, ownerA)))
        database.incomingCapsuleDao().upsertAllForOwner(ownerA, listOf(incomingCapsule(capsuleA, ownerA)))
        database.incomingEnvelopeDao().upsertForOwner(ownerA, incomingEnvelope(capsuleA, ownerA))
        database.blobCacheDao().upsertForOwner(ownerA, blobCache(blobA1, capsuleA, ownerA))
        database.recognitionFingerprintDao().insertAll(listOf(fingerprint("fp-a", capsuleA, ownerA)))
    }

    @Test
    fun bOwnerPrimitivesObserveAndMutateZeroAMaterial() = runBlocking {
        seedAccountAMaterial()

        // Reads: every owner-scoped lookup of A ids under B returns nothing.
        assertNull(database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleA, ownerB))
        assertTrue(database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleA, ownerB).isEmpty())
        assertEquals(0, database.outboxBlobDao().countByKindAndOwner(capsuleA, ownerB, "PHOTO"))
        assertNull(database.incomingCapsuleDao().getByCapsuleIdAndOwner(capsuleA, ownerB))
        assertNull(database.incomingEnvelopeDao().getByCapsuleIdAndOwner(capsuleA, ownerB))
        assertNull(database.blobCacheDao().getByBlobIdAndOwner(blobA1, ownerB))
        assertTrue(database.blobCacheDao().getAllByCapsuleIdAndOwner(capsuleA, ownerB).isEmpty())
        assertTrue(database.recognitionFingerprintDao().getAllForOwner(ownerB).isEmpty())
        assertNull(database.recognitionFingerprintDao().getByFingerprintIdAndOwner("fp-a", ownerB))
        assertTrue(
            database.recognitionFingerprintDao()
                .getByCapsuleIdAndOriginAndOwner(capsuleA, FingerprintOrigin.SENDER, ownerB)
                .isEmpty(),
        )

        // CAS transitions and counters: B refuses everywhere (0 rows touched).
        assertEquals(
            0,
            database.outboxCapsuleDao()
                .transitionStateForOwner(capsuleA, ownerB, OutboxCapsuleState.UPLOADING, listOf(OutboxCapsuleState.ENCRYPTED)),
        )
        assertEquals(
            0,
            database.outboxCapsuleDao()
                .transitionStateWithErrorForOwner(capsuleA, ownerB, OutboxCapsuleState.TERMINAL_FAILURE, listOf(OutboxCapsuleState.ENCRYPTED), "x"),
        )
        assertEquals(
            0,
            database.outboxBlobDao()
                .transitionUploadStateForOwner(blobA1, ownerB, OutboxBlobUploadState.STORED, listOf(OutboxBlobUploadState.PENDING)),
        )
        assertEquals(0, database.outboxBlobDao().incrementAttemptCountForOwner(blobA1, ownerB))
        assertTrue(
            database.incomingCapsuleDao()
                .transitionMaterialStateForOwner(
                    ownerUserId = ownerB,
                    capsuleId = capsuleA,
                    requestedTarget = LocalMaterialState.INDEX_CACHED,
                ) is LocalMaterialTransitionResult.MissingRow,
        )
        assertEquals(
            0,
            database.blobCacheDao()
                .transitionStateForOwner(blobA1, ownerB, BlobCacheState.CACHED, listOf(BlobCacheState.DOWNLOADING)),
        )
        assertEquals(0, database.recognitionFingerprintDao().deleteByFingerprintIdAndOwner("fp-a", ownerB))
        assertEquals(0, database.outboxBlobDao().deleteByCapsuleIdAndOwner(capsuleA, ownerB))
        assertEquals(0, database.blobCacheDao().deleteByCapsuleIdAndOwner(capsuleA, ownerB))
        assertEquals(0, database.recognitionFingerprintDao().deleteByCapsuleIdAndOwner(capsuleA, ownerB))

        // Nothing changed and A still sees everything through the same primitives.
        assertEquals(OutboxCapsuleState.ENCRYPTED, database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleA, ownerA)!!.state)
        assertEquals(
            OutboxBlobUploadState.PENDING,
            database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleA, ownerA).single().uploadState,
        )
        assertEquals(1, database.recognitionFingerprintDao().getAllForOwner(ownerA).size)
        assertEquals(
            1,
            database.outboxCapsuleDao()
                .transitionStateForOwner(capsuleA, ownerA, OutboxCapsuleState.UPLOADING, listOf(OutboxCapsuleState.ENCRYPTED)),
        )
    }

    @Test
    fun collidingCursorStreamsArePermittedAndIsolatedPerAccount() = runBlocking {
        val cursors = database.syncCursorDao()
        // Same stream name for both accounts - the architecture-permitted ID collision.
        cursors.advance(SyncCursorEntity(ownerA, "incoming", "page-a1", 100))
        cursors.advance(SyncCursorEntity(ownerB, "incoming", "page-b1", 200))

        assertEquals("page-a1", cursors.get(ownerA, "incoming")!!.serverCursor)
        assertEquals("page-b1", cursors.get(ownerB, "incoming")!!.serverCursor)

        // B's teardown cannot touch A's stream position.
        cursors.deleteByUser(ownerB)
        assertNotNull(cursors.get(ownerA, "incoming"))
        assertNull(cursors.get(ownerB, "incoming"))
    }

    @Test
    fun globalIdentityConstraintsRefuseCrossOwnerRelationshipDuplication() = runBlocking {
        seedAccountAMaterial()

        // B cannot attach its own PHOTO ordinal 0 to A's capsule: blob
        // uniqueness stays capsule-scoped because capsule IDs are globally
        // unique client-generated UUIDs.
        val foreignBlobOnACapsule = outboxBlob(UUID.randomUUID().toString(), capsuleA, ownerB)
        val blobViolation = assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking {
                database.outboxBlobDao().upsertAll(listOf(foreignBlobOnACapsule))
            }
        }
        assertTrue(blobViolation.message!!.contains("outbox_blob"))

        // B cannot claim the same (capsule, side, origin) baseline either.
        val fingerprintViolation = assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking {
                database.recognitionFingerprintDao()
                    .insertAll(listOf(fingerprint(UUID.randomUUID().toString(), capsuleA, ownerB)))
            }
        }
        assertTrue(fingerprintViolation.message!!.contains("recognition_fingerprint"))
        Unit
    }

    @Test
    fun legacySentinelRowsStayInvisibleToRealOwnerQueries() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO outbox_capsule (capsule_id, idempotency_key, sender_user_id, recipient_user_id, " +
                "sender_key_bundle_id, recipient_key_bundle_id, sender_signing_public_keyset_b64, state, " +
                "envelope_path, last_error_code, owner_user_id) " +
                "VALUES ('cap-legacy', 'idem-legacy', NULL, 'r', NULL, 'rb', NULL, 'ENCRYPTED', '/tmp/e', NULL, '')",
        )

        // The unattributed row exists physically but no real account resolves it.
        assertEquals(
            1,
            database.openHelper.readableDatabase
                .query("SELECT COUNT(*) FROM outbox_capsule WHERE capsule_id = 'cap-legacy'")
                .use { c -> c.moveToFirst(); c.getInt(0) },
        )
        assertNull(database.outboxCapsuleDao().getByCapsuleIdAndOwner("cap-legacy", ownerA))
        assertNull(database.outboxCapsuleDao().getByCapsuleIdAndOwner("cap-legacy", ownerB))
    }
}
