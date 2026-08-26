package dev.hryshyn.remanence.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxDaosTest {

    private companion object {
        const val OWNER = "0198f0a0-0000-7000-8000-00000000ow01"
        const val OTHER_OWNER = "0198f0a0-0000-7000-8000-00000000ow02"
    }

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var capsuleDao: OutboxCapsuleDao
    private lateinit var blobDao: OutboxBlobDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        capsuleDao = database.outboxCapsuleDao()
        blobDao = database.outboxBlobDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun capsule(
        capsuleId: String = "0198f0a0-0000-7000-8000-00000000ca01",
        idempotencyKey: String = "0198f0a0-0000-7000-8000-00000000id01",
        state: OutboxCapsuleState = OutboxCapsuleState.PREPARING,
    ) = OutboxCapsuleEntity(
        capsuleId = capsuleId,
        idempotencyKey = idempotencyKey,
        ownerUserId = "0198f0a0-0000-7000-8000-00000000ow01",
        senderUserId = "0198f0a0-0000-7000-8000-00000000se01",
        recipientUserId = "0198f0a0-0000-7000-8000-00000000re01",
        senderKeyBundleId = "0198f0a0-0000-7000-8000-00000000sk01",
        recipientKeyBundleId = "0198f0a0-0000-7000-8000-00000000rk01",
        senderSigningPublicKeysetB64 = null,
        state = state,
        recognitionManifestPath = null,
        contentManifestPath = null,
        envelopePath = null,
        publishStatementPath = null,
        publishStatementSignaturePath = null,
        lastErrorCode = null,
    )

    private fun blob(blobId: String, kind: String, ordinal: Int?) = OutboxBlobEntity(
        blobId = blobId,
        ownerUserId = "0198f0a0-0000-7000-8000-00000000ow01",
        capsuleId = "0198f0a0-0000-7000-8000-00000000ca01",
        kind = kind,
        ordinal = ordinal,
        localCiphertextPath = "files/outbox/ca01/$blobId.bin",
        sizeBytes = 1_024,
        sha256 = ByteArray(32) { (it + 3).toByte() },
        uploadState = OutboxBlobUploadState.PENDING,
        attemptCount = 0,
    )

    @Test
    fun upsertThenReadReturnsOutboxRecord() = runBlocking {
        val record = capsule(state = OutboxCapsuleState.ENCRYPTED)
        capsuleDao.upsert(record)
        assertEquals(OutboxCapsuleState.ENCRYPTED, capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!.state)
    }

    @Test
    fun idempotencyKeyNeverDuplicatesAcrossCapsules() = runBlocking {
        capsuleDao.upsert(capsule(capsuleId = "capsule-a"))
        // Same idempotency key under another capsule ID resolves onto the
        // original record instead of creating a second draft.
        capsuleDao.upsert(capsule(capsuleId = "capsule-b", idempotencyKey = "0198f0a0-0000-7000-8000-00000000id01"))
        assertNull(capsuleDao.getByCapsuleIdAndOwner("capsule-b", OWNER))
        assertEquals("capsule-a", capsuleDao.getByCapsuleIdAndOwner("capsule-a", OWNER)!!.capsuleId)
        assertEquals(1, countOutboxRows())
    }

    private fun countOutboxRows(): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM outbox_capsule").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    @Test
    fun capsuleStateTransitionHonorsAllowedOriginsAndRecordsError() = runBlocking {
        val record = capsule()
        capsuleDao.upsert(record)

        assertEquals(0, capsuleDao.transitionStateForOwner(record.capsuleId, OWNER, OutboxCapsuleState.UPLOADING, listOf(OutboxCapsuleState.PUBLISHED)))
        assertEquals(1, capsuleDao.transitionStateForOwner(record.capsuleId, OWNER, OutboxCapsuleState.ENCRYPTED, listOf(OutboxCapsuleState.PREPARING)))
        assertEquals(1, capsuleDao.transitionStateForOwner(record.capsuleId, OWNER, OutboxCapsuleState.UPLOADING, listOf(OutboxCapsuleState.ENCRYPTED)))

        assertEquals(
            1,
            capsuleDao.transitionStateWithErrorForOwner(
                record.capsuleId,
                OWNER,
                OutboxCapsuleState.RETRYABLE_FAILURE,
                listOf(OutboxCapsuleState.UPLOADING),
                "BLOB_HASH_MISMATCH",
            ),
        )
        val loaded = capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!
        assertEquals(OutboxCapsuleState.RETRYABLE_FAILURE, loaded.state)
        assertEquals("BLOB_HASH_MISMATCH", loaded.lastErrorCode)
    }

    @Test
    fun publishedTerminalStateCannotReturnToUploading() = runBlocking {
        val record = capsule()
        capsuleDao.upsert(record)
        capsuleDao.transitionStateForOwner(record.capsuleId, OWNER, OutboxCapsuleState.FINALIZING, listOf(OutboxCapsuleState.UPLOADING))
        capsuleDao.transitionStateForOwner(record.capsuleId, OWNER, OutboxCapsuleState.PUBLISHED, listOf(OutboxCapsuleState.FINALIZING))
        assertEquals(0, capsuleDao.transitionStateForOwner(record.capsuleId, OWNER, OutboxCapsuleState.UPLOADING, listOf(OutboxCapsuleState.PUBLISHED)))
    }

    @Test
    fun blobCardinalityQueriesExposeArtifactCounts() = runBlocking {
        blobDao.upsertAll(
            listOf(
                blob("blob-rec", "RECOGNITION_MANIFEST", null),
                blob("blob-con", "CONTENT_MANIFEST", null),
                blob("blob-p0", "PHOTO", 0),
                blob("blob-p1", "PHOTO", 1),
                blob("blob-p2", "PHOTO", 2),
            ),
        )
        assertEquals(1, blobDao.countByKindAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER, "RECOGNITION_MANIFEST"))
        assertEquals(1, blobDao.countByKindAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER, "CONTENT_MANIFEST"))
        assertEquals(3, blobDao.countByKindAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER, "PHOTO"))
        assertEquals(5, blobDao.getAllByCapsuleIdAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER).size)

        blobDao.upsertAll(listOf(blob("blob-p3", "PHOTO", 3), blob("blob-p4", "PHOTO", 4)))
        assertEquals(5, blobDao.countByKindAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER, "PHOTO"))

        // M2-P02: an occupied (kind, ordinal) slot is a HARD conflict - blob
        // inserts abort instead of upserting, so a colliding write can never
        // be converted into an update that silently reassigns another
        // account's row onto this relationship.
        val ordinalZeroBefore = blobDao.getAllByCapsuleIdAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER)
            .single { it.ordinal == 0 }
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking {
                blobDao.upsertAll(listOf(blob("blob-dup-ordinal", "PHOTO", 0)))
            }
        }
        assertEquals(5, blobDao.countByKindAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER, "PHOTO"))
        assertEquals(
            ordinalZeroBefore.blobId,
            blobDao.getAllByCapsuleIdAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER)
                .single { it.ordinal == 0 }
                .blobId,
        )
        val ordinals = blobDao.getAllByCapsuleIdAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER)
            .filter { it.kind == "PHOTO" }
            .map { it.ordinal }
            .filterNotNull()
            .sorted()
        assertEquals(listOf(0, 1, 2, 3, 4), ordinals)
    }

    @Test
    fun blobUploadTransitionAndAttemptCounter() = runBlocking {
        val record = blob("blob-up", "PHOTO", 4)
        blobDao.upsertAll(listOf(record))

        assertEquals(0, blobDao.incrementAttemptCountForOwner("missing-blob", OWNER))
        assertEquals(1, blobDao.incrementAttemptCountForOwner("blob-up", OWNER))
        assertEquals(1, blobDao.incrementAttemptCountForOwner("blob-up", OWNER))

        assertEquals(1, blobDao.transitionUploadStateForOwner("blob-up", OWNER, OutboxBlobUploadState.STORED, listOf(OutboxBlobUploadState.PENDING)))
        assertEquals(0, blobDao.transitionUploadStateForOwner("blob-up", OWNER, OutboxBlobUploadState.STORED, listOf(OutboxBlobUploadState.PENDING)))

        val stored = blobDao.getAllByCapsuleIdAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER).single { it.blobId == "blob-up" }
        assertEquals(OutboxBlobUploadState.STORED, stored.uploadState)
        assertEquals(2, stored.attemptCount)
    }

    @Test
    fun deleteByCapsuleRemovesOnlyThatCapsuleBlobs() = runBlocking {
        capsuleDao.upsert(capsule())
        val otherCapsule = capsule(capsuleId = "0198f0a0-0000-7000-8000-00000000ca02")
        capsuleDao.upsert(otherCapsule)
        blobDao.upsertAll(
            listOf(
                blob("blob-a", "PHOTO", 0).copy(capsuleId = otherCapsule.capsuleId),
                blob("blob-b", "PHOTO", 1),
            ),
        )
        blobDao.deleteByCapsuleIdAndOwner(otherCapsule.capsuleId, OWNER)
        assertNull(blobDao.getAllByCapsuleIdAndOwner(otherCapsule.capsuleId, OWNER).firstOrNull())
        assertEquals(1, blobDao.countByKindAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER, "PHOTO"))
    }

    /**
     * M2-P03: the ONLY account-owned surface left is owner-required, so
     * another local account can neither observe nor mutate this account's
     * durable outbox material.
     */
    @Test
    fun wrongOwnerSeesNothingAndMutatesNothing() = runBlocking {
        val record = capsule()
        capsuleDao.upsert(record)
        blobDao.upsertAll(listOf(blob("blob-up", "PHOTO", 4)))

        assertNull(capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OTHER_OWNER))
        assertTrue(blobDao.getAllByCapsuleIdAndOwner(record.capsuleId, OTHER_OWNER).isEmpty())
        assertEquals(0, blobDao.countByKindAndOwner(record.capsuleId, OTHER_OWNER, "PHOTO"))

        assertEquals(
            0,
            capsuleDao.transitionStateForOwner(
                record.capsuleId,
                OTHER_OWNER,
                OutboxCapsuleState.PUBLISHED,
                listOf(OutboxCapsuleState.ENCRYPTED),
            ),
        )
        assertEquals(
            0,
            capsuleDao.transitionStateWithErrorForOwner(
                record.capsuleId,
                OTHER_OWNER,
                OutboxCapsuleState.TERMINAL_FAILURE,
                listOf(OutboxCapsuleState.PREPARING),
                "X",
            ),
        )
        assertEquals(
            0,
            blobDao.transitionUploadStateForOwner(
                "blob-up",
                OTHER_OWNER,
                OutboxBlobUploadState.STORED,
                listOf(OutboxBlobUploadState.PENDING),
            ),
        )
        assertEquals(0, blobDao.incrementAttemptCountForOwner("blob-up", OTHER_OWNER))
        assertEquals(0, blobDao.deleteByCapsuleIdAndOwner(record.capsuleId, OTHER_OWNER))

        // The owner's material is untouched and still fully resolvable.
        assertNotNull(capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!)
        assertEquals(OutboxCapsuleState.PREPARING, capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!.state)
    }
}
