package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.core.model.LocalMaterialState
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        materialState = LocalMaterialState.DISCOVERED,
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
        database.incomingCapsuleDao().upsertAllForOwner(ownerA, listOf(incomingCapsule(capsuleA, ownerA)))
        database.incomingEnvelopeDao().upsertForOwner(ownerA, incomingEnvelope(capsuleA, ownerA))
        database.blobCacheDao().upsertForOwner(ownerA, blobCache(blobA, capsuleA, ownerA))
        database.syncCursorDao().advance(ownerA, SyncCursorEntity(ownerA, "incoming", "page-a3", 300))

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

        // B sees no owner-scoped row and therefore receives the distinct
        // missing result; it cannot transition A's material.
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
                .advance(ownerB, SyncCursorEntity(ownerB, "incoming", "page-b-first", 500)),
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
            LocalMaterialState.DISCOVERED,
            database.incomingCapsuleDao().getByCapsuleIdAndOwner(capsuleA, ownerA)!!.materialState,
        )
        assertEquals("page-a3", database.syncCursorDao().get(ownerA, "incoming")!!.serverCursor)
    }

    /**
     * M2 review regression: B cannot reassign A's routed capsule row by
     * replaying A's immutable capsule ID under B's ownership - refused and
     * every original field left byte-for-byte identical.
     */
    @Test
    fun foreignOwnerCapsuleReplayIsRefusedAndOriginalRowUnchanged() = runBlocking {
        val original = incomingCapsule(capsuleA, ownerA)
        database.incomingCapsuleDao().upsertAllForOwner(ownerA, listOf(original))
        val before = database.incomingCapsuleDao().getByCapsuleIdAndOwner(capsuleA, ownerA)!!

        val bReplaysSameCapsuleId =
            listOf(original.copy(ownerUserId = ownerB, serverStatus = "READY"))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { database.incomingCapsuleDao().upsertAllForOwner(ownerB, bReplaysSameCapsuleId) }
        }

        assertEquals(1, countRows("incoming_capsule"))
        val after = database.incomingCapsuleDao().getByCapsuleIdAndOwner(capsuleA, ownerA)!!
        assertEquals(before, after)
        assertEquals(ownerA, after.ownerUserId)
    }

    /** Same-owner page replay updates exactly the allowed fields, nothing else. */
    @Test
    fun sameOwnerCapsuleReplayUpdatesOnlyAllowedFields() = runBlocking {
        val original = incomingCapsule(capsuleA, ownerA)
        database.incomingCapsuleDao().upsertAllForOwner(ownerA, listOf(original))

        database.incomingCapsuleDao().upsertAllForOwner(
            ownerA,
            listOf(
                original.copy(
                    serverStatus = "READY",
                    readyAtEpochMs = 1_755_000_999_999,
                    signedStatementBytes = byteArrayOf(9, 9),
                ),
            ),
        )

        val replayed = database.incomingCapsuleDao().getByCapsuleIdAndOwner(capsuleA, ownerA)!!
        assertEquals("READY", replayed.serverStatus)
        assertEquals(1_755_000_999_999, replayed.readyAtEpochMs)
        assertTrue(replayed.signedStatementBytes.contentEquals(byteArrayOf(9, 9)))
        // Immutable routing/state columns survive a replay untouched.
        assertEquals(1, countRows("incoming_capsule"))
        assertEquals(LocalMaterialState.DISCOVERED, replayed.materialState)
        assertTrue(original.senderUserId == replayed.senderUserId)
        assertTrue(original.recipientEncryptionKeyBundleId == replayed.recipientEncryptionKeyBundleId)
    }

    @Test
    fun foreignOwnerEnvelopeReplayIsRefusedAndOriginalRowUnchanged() = runBlocking {
        val original = incomingEnvelope(capsuleA, ownerA)
        database.incomingEnvelopeDao().upsertForOwner(ownerA, original)

        val before = database.incomingEnvelopeDao().getByCapsuleIdAndOwner(capsuleA, ownerA)!!

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                database.incomingEnvelopeDao()
                    .upsertForOwner(ownerB, original.copy(ownerUserId = ownerB, hpkeCiphertext = byteArrayOf(1)))
            }
        }

        assertEquals(1, countRows("incoming_envelope"))
        val after = database.incomingEnvelopeDao().getByCapsuleIdAndOwner(capsuleA, ownerA)!!
        assertTrue(before.hpkeCiphertext.contentEquals(after.hpkeCiphertext))
        assertTrue(before.transportSha256.contentEquals(after.transportSha256))
        assertEquals(before.receivedAtEpochMs, after.receivedAtEpochMs)
        assertEquals(ownerA, after.ownerUserId)
    }

    @Test
    fun foreignOwnerBlobCacheReplayIsRefusedAndOriginalRowUnchanged() = runBlocking {
        val original = blobCache(blobA, capsuleA, ownerA)
        database.blobCacheDao().upsertForOwner(ownerA, original)

        val before = database.blobCacheDao().getByBlobIdAndOwner(blobA, ownerA)!!

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                database.blobCacheDao()
                    .upsertForOwner(ownerB, original.copy(ownerUserId = ownerB, localPath = "b/stolen.bin"))
            }
        }

        assertEquals(1, countRows("blob_cache"))
        val after = database.blobCacheDao().getByBlobIdAndOwner(blobA, ownerA)!!
        assertEquals(before.localPath, after.localPath)
        assertTrue(before.expectedSha256.contentEquals(after.expectedSha256))
        assertEquals(before.cacheState, after.cacheState)
        assertEquals(ownerA, after.ownerUserId)
    }

    /** Same-owner cache replay may fix transport metadata but never rewinds state. */
    @Test
    fun sameOwnerBlobCacheReplayUpdatesFieldsWithoutTouchingCacheState() = runBlocking {
        val original = blobCache(blobA, capsuleA, ownerA)
        database.blobCacheDao().upsertForOwner(ownerA, original)
        assertEquals(
            1,
            database.blobCacheDao()
                .transitionStateForOwner(blobA, ownerA, BlobCacheState.CACHED, listOf(BlobCacheState.DOWNLOADING)),
        )

        database.blobCacheDao().upsertForOwner(
            ownerA,
            original.copy(localPath = "files/capsules/ca01/blob-bl01-replayed.bin"),
        )

        val replayed = database.blobCacheDao().getByBlobIdAndOwner(blobA, ownerA)!!
        assertEquals("files/capsules/ca01/blob-bl01-replayed.bin", replayed.localPath)
        // Replay must NOT rewind a progressed lifecycle state.
        assertEquals(BlobCacheState.CACHED, replayed.cacheState)
    }

    private fun countRows(table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    @Test
    fun bAccumulatesItsOwnIncomingMaterialAlongsideARetainedRows() = runBlocking {
        val capsuleB = UUID.randomUUID().toString()
        // A caches first; then B accumulates its own pages independently.
        database.incomingCapsuleDao().upsertAllForOwner(ownerA, listOf(incomingCapsule(capsuleA, ownerA)))
        database.incomingCapsuleDao().upsertAllForOwner(ownerB, listOf(incomingCapsule(capsuleB, ownerB)))
        database.blobCacheDao().upsertForOwner(ownerA, blobCache(blobA, capsuleA, ownerA))

        // B advances only its own material state.
        assertTrue(
            database.incomingCapsuleDao()
                .transitionMaterialStateForOwner(
                    ownerUserId = ownerB,
                    capsuleId = capsuleB,
                    requestedTarget = LocalMaterialState.INDEX_CACHED,
                ) is LocalMaterialTransitionResult.Accepted,
        )
        assertEquals(
            LocalMaterialState.DISCOVERED,
            database.incomingCapsuleDao().getByCapsuleIdAndOwner(capsuleA, ownerA)!!.materialState,
        )
        assertEquals(1, database.blobCacheDao().getAllByCapsuleIdAndOwner(capsuleA, ownerA).size)
        assertFalse(
            database.blobCacheDao().getAllByCapsuleIdAndOwner(capsuleB, ownerB).isNotEmpty(),
        )
    }
}
