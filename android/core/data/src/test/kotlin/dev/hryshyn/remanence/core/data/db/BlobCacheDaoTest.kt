package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlobCacheDaoTest {

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
        dao.upsert(record)
        assertEquals(record.expectedSha256.toList(), dao.getByBlobId(record.blobId)!!.expectedSha256.toList())
    }

    @Test
    fun stateTransitionOnlyFromAllowedOriginStates() = runBlocking {
        val record = blob()
        dao.upsert(record)

        val illegal = dao.transitionState(record.blobId, BlobCacheState.CACHED, listOf(BlobCacheState.CORRUPT))
        assertEquals(0, illegal)

        val legal = dao.transitionState(record.blobId, BlobCacheState.CACHED, listOf(BlobCacheState.DOWNLOADING))
        assertEquals(1, legal)
        assertEquals(BlobCacheState.CACHED, dao.getByBlobId(record.blobId)!!.cacheState)
    }

    @Test
    fun cachedBlobCanBeMarkedCorruptForRepair() = runBlocking {
        val record = blob(state = BlobCacheState.DOWNLOADING)
        dao.upsert(record)
        dao.transitionState(record.blobId, BlobCacheState.CACHED, listOf(BlobCacheState.DOWNLOADING))
        val rows = dao.transitionState(record.blobId, BlobCacheState.CORRUPT, listOf(BlobCacheState.CACHED))
        assertEquals(1, rows)
        assertEquals(BlobCacheState.CORRUPT, dao.getByBlobId(record.blobId)!!.cacheState)
    }

    @Test
    fun deleteByCapsuleRemovesAllCapsuleBlobsOnly() = runBlocking {
        val other = blob(blobId = "0198f0a0-0000-7000-8000-00000000bl02")
            .copy(capsuleId = "0198f0a0-0000-7000-8000-00000000ca02", ordinal = null, kind = "CONTENT_MANIFEST")
        dao.upsert(blob())
        dao.upsert(other)
        dao.deleteByCapsuleId("0198f0a0-0000-7000-8000-00000000ca01")

        assertNull(dao.getByBlobId("0198f0a0-0000-7000-8000-00000000bl01"))
        assertEquals(other.blobId, dao.getByBlobId(other.blobId)!!.blobId)
    }
}
