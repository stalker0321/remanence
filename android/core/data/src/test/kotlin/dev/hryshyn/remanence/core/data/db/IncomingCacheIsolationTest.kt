package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M2-P03 account-switch lifecycle proof for the INCOMING material cache and
 * sync cursor surfaces (docs/architecture.md section 6): rows written while
 * local account A is authenticated survive logout under the same-account
 * retention policy, but after login as B every owner-required lookup, list,
 * compare-and-set, and cleanup resolves NOTHING of A's material. Only the
 * `(user_id, stream_name)` cursor pair may legitimately coexist per account.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingCacheIsolationTest {

    private val ownerA = "0198f0a0-0000-7000-8000-00000000ow01"
    private val ownerB = "0198f0a0-0000-7000-8000-00000000ow02"
    private val capsuleA = "0198f0a0-0000-7000-8000-00000000ca01"
    private val blobA = "0198f0a0-0000-7000-8000-00000000bl01"

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

    private fun incomingCapsule(capsuleId: String, ownerId: String) = IncomingCapsuleEntity(
        capsuleId = capsuleId,
        ownerUserId = ownerId,
        senderUserId = "0198f0a0-0000-7000-8000-00000000se01",
        recipientUserId = ownerId,
        senderSigningKeyBundleId = "0198f0a0-0000-7000-8000-00000000sk01",
        recipientEncryptionKeyBundleId = "0198f0a0-0000-7000-8000-00000000rk01",
        protocolVersion = 1,
        serverStatus = "READY",
        readyAtEpochMs = 1_755_000_000_000,
        signedStatementBytes = byteArrayOf(1, 2, 3),
        materialState = IncomingMaterialState.DISCOVERED,
    )

    private fun incomingEnvelope(capsuleId: String, ownerId: String) = IncomingEnvelopeEntity(
        capsuleId = capsuleId,
        ownerUserId = ownerId,
        recipientKeyBundleId = "0198f0a0-0000-7000-8000-00000000rk01",
        hpkeCiphertext = byteArrayOf(9, 8, 7),
        transportSha256 = ByteArray(32) { 4 },
        receivedAtEpochMs = 1_755_000_100_000,
    )

    private fun blobCache(blobId: String, capsuleId: String, ownerId: String) = BlobCacheEntity(
        blobId = blobId,
        ownerUserId = ownerId,
        capsuleId = capsuleId,
        kind = "PHOTO",
        ordinal = 0,
        expectedSizeBytes = 8_192,
        expectedSha256 = ByteArray(32) { (it + 7).toByte() },
        localPath = "files/capsules/$capsuleId/$blobId.bin",
        cacheState = BlobCacheState.DOWNLOADING,
    )

    @Test
    fun afterLogoutAAndLoginBZeroARowsAreExposedOrMutable() = runBlocking {
        // --- logged in as A: sync page writes A-owned cached material ---
        database.incomingCapsuleDao().upsertAll(listOf(incomingCapsule(capsuleA, ownerA)))
        database.incomingEnvelopeDao().upsert(incomingEnvelope(capsuleA, ownerA))
        database.blobCacheDao().upsert(blobCache(blobA, capsuleA, ownerA))
        database.syncCursorDao().advance(SyncCursorEntity(ownerA, "incoming", "page-a3", 300))

        // ... material fully resolved under its owning account:
        assertNotNull(database.incomingCapsuleDao().getByCapsuleIdAndOwner(capsuleA, ownerA))
        assertEquals("page-a3", database.syncCursorDao().get(ownerA, "incoming")!!.serverCursor)

        // --- LOGOUT A (retention policy keeps durable ciphertext), LOGIN B ---
        // Every owner-required read/list sees nothing of A's.
        assertNull(database.incomingCapsuleDao().getByCapsuleIdAndOwner(capsuleA, ownerB))
        assertNull(database.incomingEnvelopeDao().getByCapsuleIdAndOwner(capsuleA, ownerB))
        assertTrue(database.blobCacheDao().getAllByCapsuleIdAndOwner(capsuleA, ownerB).isEmpty())
        assertNull(database.blobCacheDao().getByBlobIdAndOwner(blobA, ownerB))
        assertNull(database.syncCursorDao().get(ownerB, "incoming"))

        // Every CAS transition refuses B (0 rows).
        assertEquals(
            0,
            database.incomingCapsuleDao()
                .transitionMaterialStateForOwner(
                    capsuleA,
                    ownerB,
                    IncomingMaterialState.INDEX_CACHED,
                    listOf(IncomingMaterialState.DISCOVERED),
                ),
        )
        assertEquals(
            0,
            database.blobCacheDao()
                .transitionStateForOwner(
                    blobA,
                    ownerB,
                    BlobCacheState.CACHED,
                    listOf(BlobCacheState.DOWNLOADING),
                ),
        )

        // Cleanup refuses B; A's durable rows survive it untouched.
        assertEquals(0, database.blobCacheDao().deleteByCapsuleIdAndOwner(capsuleA, ownerB))

        // B's own stream position exists independently of A's replayed page;
        // an older B replay can never move A's position either.
        assertTrue(
            database.syncCursorDao()
                .advance(SyncCursorEntity(ownerB, "incoming", "page-b-first", 500)),
        )
        assertEquals("page-b-first", database.syncCursorDao().get(ownerB, "incoming")!!.serverCursor)
        assertEquals("page-a3", database.syncCursorDao().get(ownerA, "incoming")!!.serverCursor)

        // --- Re-login as A proves the retention policy held everything ---
        assertNotNull(database.incomingCapsuleDao().getByCapsuleIdAndOwner(capsuleA, ownerA))
        assertNotNull(database.incomingEnvelopeDao().getByCapsuleIdAndOwner(capsuleA, ownerA))
        assertEquals(
            BlobCacheState.DOWNLOADING,
            database.blobCacheDao().getByBlobIdAndOwner(blobA, ownerA)!!.cacheState,
        )
        assertEquals(
            IncomingMaterialState.DISCOVERED,
            database.incomingCapsuleDao().getByCapsuleIdAndOwner(capsuleA, ownerA)!!.materialState,
        )
        assertEquals("page-a3", database.syncCursorDao().get(ownerA, "incoming")!!.serverCursor)
    }

    @Test
    fun bAccumulatesItsOwnIncomingMaterialAlongsideARetainedRows() = runBlocking {
        val capsuleB = UUID.randomUUID().toString()
        // A caches first; then B accumulates its own pages independently.
        database.incomingCapsuleDao().upsertAll(listOf(incomingCapsule(capsuleA, ownerA)))
        database.incomingCapsuleDao().upsertAll(listOf(incomingCapsule(capsuleB, ownerB)))
        database.blobCacheDao().upsert(blobCache(blobA, capsuleA, ownerA))

        // B advances only its own material state.
        assertEquals(
            1,
            database.incomingCapsuleDao()
                .transitionMaterialStateForOwner(
                    capsuleB,
                    ownerB,
                    IncomingMaterialState.INDEX_CACHED,
                    listOf(IncomingMaterialState.DISCOVERED),
                ),
        )
        assertEquals(
            IncomingMaterialState.DISCOVERED,
            database.incomingCapsuleDao().getByCapsuleIdAndOwner(capsuleA, ownerA)!!.materialState,
        )
        assertEquals(1, database.blobCacheDao().getAllByCapsuleIdAndOwner(capsuleA, ownerA).size)
        assertFalse(
            database.blobCacheDao().getAllByCapsuleIdAndOwner(capsuleB, ownerB).isNotEmpty(),
        )
    }
}
