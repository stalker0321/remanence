package postmark.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxDaosTest {

    private lateinit var database: PostmarkLocalDatabase
    private lateinit var capsuleDao: OutboxCapsuleDao
    private lateinit var blobDao: OutboxBlobDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PostmarkLocalDatabase::class.java)
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
        recipientUserId = "0198f0a0-0000-7000-8000-00000000re01",
        recipientKeyBundleId = "0198f0a0-0000-7000-8000-00000000rk01",
        state = state,
        recognitionManifestPath = null,
        contentManifestPath = null,
        envelopePath = null,
        lastErrorCode = null,
    )

    private fun blob(blobId: String, kind: String, ordinal: Int?) = OutboxBlobEntity(
        blobId = blobId,
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
        assertEquals(OutboxCapsuleState.ENCRYPTED, capsuleDao.getByCapsuleId(record.capsuleId)!!.state)
    }

    @Test
    fun idempotencyKeyNeverDuplicatesAcrossCapsules() = runBlocking {
        capsuleDao.upsert(capsule(capsuleId = "capsule-a"))
        // Same idempotency key under another capsule ID resolves onto the
        // original record instead of creating a second draft.
        capsuleDao.upsert(capsule(capsuleId = "capsule-b", idempotencyKey = "0198f0a0-0000-7000-8000-00000000id01"))
        assertNull(capsuleDao.getByCapsuleId("capsule-b"))
        assertEquals("capsule-a", capsuleDao.getByCapsuleId("capsule-a")!!.capsuleId)
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

        assertEquals(0, capsuleDao.transitionState(record.capsuleId, OutboxCapsuleState.UPLOADING, listOf(OutboxCapsuleState.PUBLISHED)))
        assertEquals(1, capsuleDao.transitionState(record.capsuleId, OutboxCapsuleState.ENCRYPTED, listOf(OutboxCapsuleState.PREPARING)))
        assertEquals(1, capsuleDao.transitionState(record.capsuleId, OutboxCapsuleState.UPLOADING, listOf(OutboxCapsuleState.ENCRYPTED)))

        assertEquals(
            1,
            capsuleDao.transitionStateWithError(
                record.capsuleId,
                OutboxCapsuleState.RETRYABLE_FAILURE,
                listOf(OutboxCapsuleState.UPLOADING),
                "BLOB_HASH_MISMATCH",
            ),
        )
        val loaded = capsuleDao.getByCapsuleId(record.capsuleId)!!
        assertEquals(OutboxCapsuleState.RETRYABLE_FAILURE, loaded.state)
        assertEquals("BLOB_HASH_MISMATCH", loaded.lastErrorCode)
    }

    @Test
    fun publishedTerminalStateCannotReturnToUploading() = runBlocking {
        val record = capsule()
        capsuleDao.upsert(record)
        capsuleDao.transitionState(record.capsuleId, OutboxCapsuleState.FINALIZING, listOf(OutboxCapsuleState.UPLOADING))
        capsuleDao.transitionState(record.capsuleId, OutboxCapsuleState.PUBLISHED, listOf(OutboxCapsuleState.FINALIZING))
        assertEquals(0, capsuleDao.transitionState(record.capsuleId, OutboxCapsuleState.UPLOADING, listOf(OutboxCapsuleState.PUBLISHED)))
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
        assertEquals(1, blobDao.countByKind("0198f0a0-0000-7000-8000-00000000ca01", "RECOGNITION_MANIFEST"))
        assertEquals(1, blobDao.countByKind("0198f0a0-0000-7000-8000-00000000ca01", "CONTENT_MANIFEST"))
        assertEquals(3, blobDao.countByKind("0198f0a0-0000-7000-8000-00000000ca01", "PHOTO"))
        assertEquals(5, blobDao.getAllByCapsuleId("0198f0a0-0000-7000-8000-00000000ca01").size)

        blobDao.upsertAll(listOf(blob("blob-p3", "PHOTO", 3), blob("blob-p4", "PHOTO", 4)))
        assertEquals(5, blobDao.countByKind("0198f0a0-0000-7000-8000-00000000ca01", "PHOTO"))

        // Re-upserting an occupied (kind, ordinal) slot replaces that slot's
        // row instead of adding a duplicate ordinal.
        blobDao.upsertAll(listOf(blob("blob-dup-ordinal", "PHOTO", 0)))
        assertEquals(5, blobDao.countByKind("0198f0a0-0000-7000-8000-00000000ca01", "PHOTO"))
        val ordinals = blobDao.getAllByCapsuleId("0198f0a0-0000-7000-8000-00000000ca01")
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

        assertEquals(0, blobDao.incrementAttemptCount("missing-blob"))
        assertEquals(1, blobDao.incrementAttemptCount("blob-up"))
        assertEquals(1, blobDao.incrementAttemptCount("blob-up"))

        assertEquals(1, blobDao.transitionUploadState("blob-up", OutboxBlobUploadState.STORED, listOf(OutboxBlobUploadState.PENDING)))
        assertEquals(0, blobDao.transitionUploadState("blob-up", OutboxBlobUploadState.STORED, listOf(OutboxBlobUploadState.PENDING)))

        val stored = blobDao.getAllByCapsuleId("0198f0a0-0000-7000-8000-00000000ca01").single { it.blobId == "blob-up" }
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
        blobDao.deleteByCapsuleId(otherCapsule.capsuleId)
        assertNull(blobDao.getAllByCapsuleId(otherCapsule.capsuleId).firstOrNull())
        assertEquals(1, blobDao.countByKind("0198f0a0-0000-7000-8000-00000000ca01", "PHOTO"))
    }
}
