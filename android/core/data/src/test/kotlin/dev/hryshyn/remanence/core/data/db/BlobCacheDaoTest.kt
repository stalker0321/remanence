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
    fun stateTransitionOnlyFromAllowedOriginStates() = runBlocking {
        val record = blob()
        dao.upsertForOwner(OWNER, record)

        val illegal = dao.transitionStateForOwner(record.blobId, OWNER, BlobCacheState.CACHED, listOf(BlobCacheState.CORRUPT))
        assertEquals(0, illegal)

        val legal = dao.transitionStateForOwner(record.blobId, OWNER, BlobCacheState.CACHED, listOf(BlobCacheState.DOWNLOADING))
        assertEquals(1, legal)
        assertEquals(BlobCacheState.CACHED, dao.getByBlobIdAndOwner(record.blobId, OWNER)!!.cacheState)
    }

    @Test
    fun cachedBlobCanBeMarkedCorruptForRepair() = runBlocking {
        val record = blob(state = BlobCacheState.DOWNLOADING)
        dao.upsertForOwner(OWNER, record)
        dao.transitionStateForOwner(record.blobId, OWNER, BlobCacheState.CACHED, listOf(BlobCacheState.DOWNLOADING))
        val rows = dao.transitionStateForOwner(record.blobId, OWNER, BlobCacheState.CORRUPT, listOf(BlobCacheState.CACHED))
        assertEquals(1, rows)
        assertEquals(BlobCacheState.CORRUPT, dao.getByBlobIdAndOwner(record.blobId, OWNER)!!.cacheState)
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
                )
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
