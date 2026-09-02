package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Modifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlobCacheDaoTest {

    private companion object {
        const val OWNER = "0198f0a0-0000-7000-8000-00000000ow01"
    }

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var dao: BlobCacheDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.blobCacheDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun blob(
        blobId: String = "0198f0a0-0000-7000-8000-00000000bl01",
        state: BlobCacheState = BlobCacheState.DOWNLOADING,
    ) = BlobCacheEntity(
        blobId = blobId,
        ownerUserId = "0198f0a0-0000-7000-8000-00000000ow01",
        capsuleId = "0198f0a0-0000-7000-8000-00000000ca01",
        kind = "PHOTO",
        ordinal = 0,
        expectedSizeBytes = 8_192,
        expectedSha256 = ByteArray(32) { (it + 7).toByte() },
        localPath = "files/capsules/ca01/blob-bl01.bin",
        cacheState = state,
    )

    @Test
    fun upsertThenReadReturnsBlobReference() = runBlocking {
        val record = blob()
        dao.upsertForOwner(OWNER, record)
        assertEquals(record.expectedSha256.toList(), dao.getByBlobIdAndOwner(record.blobId, OWNER)!!.expectedSha256.toList())
    }

    @Test
    fun fixedCacheTransitionsAcceptOnlyCanonicalEdges() = runBlocking {
        val record = blob()
        dao.upsertForOwner(OWNER, record)

        assertEquals(1, dao.markCachedForOwner(record.blobId, OWNER))
        assertEquals(0, dao.markCachedForOwner(record.blobId, OWNER))
        assertEquals(0, dao.retryDownloadForOwner(record.blobId, OWNER))
        assertEquals(1, dao.markCorruptForOwner(record.blobId, OWNER))
        assertEquals(0, dao.markCorruptForOwner(record.blobId, OWNER))
        assertEquals(1, dao.retryDownloadForOwner(record.blobId, OWNER))
        assertEquals(0, dao.retryDownloadForOwner(record.blobId, OWNER))
        assertEquals(BlobCacheState.DOWNLOADING, dao.getByBlobIdAndOwner(record.blobId, OWNER)!!.cacheState)
    }

    @Test
    fun downloadingBlobCanBeMarkedCorruptWithoutCallerSelectedStates() = runBlocking {
        val record = blob(blobId = "0198f0a0-0000-7000-8000-00000000bl02")
        dao.upsertForOwner(OWNER, record)
        assertEquals(1, dao.markCorruptForOwner(record.blobId, OWNER))
        assertEquals(BlobCacheState.CORRUPT, dao.getByBlobIdAndOwner(record.blobId, OWNER)!!.cacheState)
    }

    @Test
    fun skipsAndRegressionsAreNoOps() = runBlocking {
        val record = blob(blobId = "0198f0a0-0000-7000-8000-00000000bl03")
        dao.upsertForOwner(OWNER, record)

        assertEquals(0, dao.retryDownloadForOwner(record.blobId, OWNER))
        assertEquals(1, dao.markCorruptForOwner(record.blobId, OWNER))
        assertEquals(0, dao.markCachedForOwner(record.blobId, OWNER))
        assertEquals(0, dao.markCorruptForOwner(record.blobId, OWNER))
        assertEquals(BlobCacheState.CORRUPT, dao.getByBlobIdAndOwner(record.blobId, OWNER)!!.cacheState)
    }

    @Test
    fun missingAndWrongOwnerRowsReturnZeroForEveryFixedTransition() = runBlocking {
        val record = blob(blobId = "0198f0a0-0000-7000-8000-00000000bl04")
        dao.upsertForOwner(OWNER, record)
        val otherOwner = "0198f0a0-0000-7000-8000-00000000ow02"

        assertEquals(0, dao.markCachedForOwner("missing-blob", OWNER))
        assertEquals(0, dao.markCorruptForOwner("missing-blob", OWNER))
        assertEquals(0, dao.retryDownloadForOwner("missing-blob", OWNER))
        assertEquals(0, dao.markCachedForOwner(record.blobId, otherOwner))
        assertEquals(0, dao.markCorruptForOwner(record.blobId, otherOwner))
        assertEquals(0, dao.retryDownloadForOwner(record.blobId, otherOwner))
        assertEquals(BlobCacheState.DOWNLOADING, dao.getByBlobIdAndOwner(record.blobId, OWNER)!!.cacheState)
    }

    @Test
    fun repairsOnlyTheExactDownloadingRecognitionBinding() = runBlocking {
        val record = blob(
            blobId = "0198f0a0-0000-7000-8000-00000000bl05",
        ).copy(
            kind = "RECOGNITION_MANIFEST",
            ordinal = null,
        )
        dao.upsertForOwner(OWNER, record)

        assertEquals(
            1,
            dao.repairDownloadingRecognitionPathForOwner(
                ownerUserId = OWNER,
                capsuleId = record.capsuleId,
                blobId = record.blobId,
                expectedSizeBytes = record.expectedSizeBytes,
                expectedSha256 = record.expectedSha256,
                oldLocalPath = record.localPath,
                newLocalPath = "current/incoming/${record.blobId}.ciphertext",
            ),
        )

        val repaired = dao.getByBlobIdAndOwner(record.blobId, OWNER)!!
        assertEquals("current/incoming/${record.blobId}.ciphertext", repaired.localPath)
        assertEquals(BlobCacheState.DOWNLOADING, repaired.cacheState)
        assertEquals(record.capsuleId, repaired.capsuleId)
        assertEquals(record.kind, repaired.kind)
        assertEquals(record.ordinal, repaired.ordinal)
        assertEquals(record.expectedSizeBytes, repaired.expectedSizeBytes)
        assertTrue(record.expectedSha256.contentEquals(repaired.expectedSha256))
    }

    @Test
    fun repairCasLossDoesNotRewriteTheWinningPath() = runBlocking {
        val record = blob(
            blobId = "0198f0a0-0000-7000-8000-00000000bl06",
        ).copy(kind = "RECOGNITION_MANIFEST", ordinal = null)
        dao.upsertForOwner(OWNER, record)
        val winningPath = "winner/${record.blobId}.ciphertext"
        database.openHelper.writableDatabase.execSQL(
            "UPDATE blob_cache SET local_path = ? WHERE blob_id = ? AND owner_user_id = ?",
            arrayOf(winningPath, record.blobId, OWNER),
        )

        assertEquals(
            0,
            dao.repairDownloadingRecognitionPathForOwner(
                ownerUserId = OWNER,
                capsuleId = record.capsuleId,
                blobId = record.blobId,
                expectedSizeBytes = record.expectedSizeBytes,
                expectedSha256 = record.expectedSha256,
                oldLocalPath = record.localPath,
                newLocalPath = "loser/${record.blobId}.ciphertext",
            ),
        )
        assertEquals(winningPath, dao.getByBlobIdAndOwner(record.blobId, OWNER)!!.localPath)
    }

    @Test
    fun repairRefusesForeignOrImmutableMismatchedRows() = runBlocking {
        val record = blob(
            blobId = "0198f0a0-0000-7000-8000-00000000bl07",
        ).copy(kind = "RECOGNITION_MANIFEST", ordinal = null)
        dao.upsertForOwner(OWNER, record)
        val otherOwner = "0198f0a0-0000-7000-8000-00000000ow02"

        val attempts = listOf(
            Triple(otherOwner, record.capsuleId, record.expectedSizeBytes),
            Triple(OWNER, "0198f0a0-0000-7000-8000-00000000ca02", record.expectedSizeBytes),
            Triple(OWNER, record.capsuleId, record.expectedSizeBytes + 1),
        )
        for ((attemptOwner, attemptCapsule, attemptSize) in attempts) {
            assertEquals(
                0,
                dao.repairDownloadingRecognitionPathForOwner(
                    ownerUserId = attemptOwner,
                    capsuleId = attemptCapsule,
                    blobId = record.blobId,
                    expectedSizeBytes = attemptSize,
                    expectedSha256 = record.expectedSha256,
                    oldLocalPath = record.localPath,
                    newLocalPath = "must-not-write/${record.blobId}.ciphertext",
                ),
            )
        }
        assertEquals(
            0,
            dao.repairDownloadingRecognitionPathForOwner(
                ownerUserId = OWNER,
                capsuleId = record.capsuleId,
                blobId = record.blobId,
                expectedSizeBytes = record.expectedSizeBytes,
                expectedSha256 = ByteArray(32) { 99 },
                oldLocalPath = record.localPath,
                newLocalPath = "must-not-write/${record.blobId}.ciphertext",
            ),
        )
        assertEquals(record.localPath, dao.getByBlobIdAndOwner(record.blobId, OWNER)!!.localPath)
    }

    @Test
    fun repairRefusesCachedAndCorruptRows() = runBlocking {
        val ids = listOf(
            "0198f0a0-0000-7000-8000-00000000bl08",
            "0198f0a0-0000-7000-8000-00000000bl09",
        )
        for ((index, state) in listOf(BlobCacheState.CACHED, BlobCacheState.CORRUPT).withIndex()) {
            val record = blob(
                blobId = ids[index],
                state = state,
            ).copy(kind = "RECOGNITION_MANIFEST", ordinal = null)
            dao.upsertForOwner(OWNER, record)

            assertEquals(
                0,
                dao.repairDownloadingRecognitionPathForOwner(
                    ownerUserId = OWNER,
                    capsuleId = record.capsuleId,
                    blobId = record.blobId,
                    expectedSizeBytes = record.expectedSizeBytes,
                    expectedSha256 = record.expectedSha256,
                    oldLocalPath = record.localPath,
                    newLocalPath = "must-not-write/${record.blobId}.ciphertext",
                ),
            )
            val unchanged = dao.getByBlobIdAndOwner(record.blobId, OWNER)!!
            assertEquals(record.localPath, unchanged.localPath)
            assertEquals(state, unchanged.cacheState)
        }
    }

    @Test
    fun deleteByCapsuleRemovesAllCapsuleBlobsOnly() = runBlocking {
        val other = blob(blobId = "0198f0a0-0000-7000-8000-00000000bl02")
            .copy(capsuleId = "0198f0a0-0000-7000-8000-00000000ca02", ordinal = null, kind = "CONTENT_MANIFEST")
        dao.upsertForOwner(OWNER, blob())
        dao.upsertForOwner(OWNER, other)
        dao.deleteByCapsuleIdAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER)

        assertNull(dao.getByBlobIdAndOwner("0198f0a0-0000-7000-8000-00000000bl01", OWNER))
        assertEquals(other.blobId, dao.getByBlobIdAndOwner(other.blobId, OWNER)!!.blobId)
    }

    @Test
    fun foreignOwnerUpsertIsRefusedAndOriginalRowUnchanged() = runBlocking {
        val ownerB = "0198f0a0-0000-7000-8000-00000000ow02"
        val original = blob()
        dao.upsertForOwner(OWNER, original)
        val before = dao.getByBlobIdAndOwner(original.blobId, OWNER)!!

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                dao.upsertForOwner(
                    ownerB,
                    original.copy(ownerUserId = ownerB, localPath = "b/stolen.bin"),
                )
            }
        }

        assertEquals(1, countRows("blob_cache"))
        val after = dao.getByBlobIdAndOwner(original.blobId, OWNER)!!
        assertEquals(before.localPath, after.localPath)
        assertTrue(before.expectedSha256.contentEquals(after.expectedSha256))
        assertEquals(before.cacheState, after.cacheState)
        assertEquals(OWNER, after.ownerUserId)
    }

    @Test
    fun ownerArgumentMismatchIsRejectedBeforeAnyBlobWrite() = runBlocking {
        val ownerB = "0198f0a0-0000-7000-8000-00000000ow02"
        val original = blob()
        dao.upsertForOwner(OWNER, original)
        val candidate = original.copy(ownerUserId = ownerB, localPath = "b/stolen.bin")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dao.upsertForOwner(OWNER, candidate) }
        }

        val after = dao.getByBlobIdAndOwner(original.blobId, OWNER)!!
        assertEquals(original, after)
        assertEquals(OWNER, after.ownerUserId)
    }

    @Test
    fun publicBlobDaoSurfaceHasNoGlobalClearOrRawWritePrimitives() {
        val methods = BlobCacheDao::class.java.methods

        assertTrue(methods.none { it.name == "clear" })
        assertTrue(methods.any { it.name == "clearForOwner" })
        assertTrue(
            methods.none {
                it.name in setOf(
                    "findOwnerOf",
                    "insertIgnoring",
                    "updateReplayFieldsForOwner",
                    "transitionStateForOwner",
                )
            },
        )
        assertTrue(
            methods.none { method ->
                method.parameterTypes.any { it == BlobCacheState::class.java } ||
                    method.genericParameterTypes.any { it.toString().contains("BlobCacheState") }
            },
        )
        val upsert = methods.single { it.name == "upsertForOwner" }
        assertEquals(String::class.java, upsert.parameterTypes.first())
        assertTrue(BlobCacheEntity::class.java in upsert.parameterTypes)
        assertTrue(Modifier.isPublic(upsert.modifiers))
    }

    private fun countRows(table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
