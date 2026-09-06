package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.core.data.network.IncomingTombstone
import dev.hryshyn.remanence.core.data.network.IncomingTombstoneFeed
import dev.hryshyn.remanence.core.data.network.IncomingTombstonePage
import dev.hryshyn.remanence.core.data.network.IncomingTombstoneResult
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingTombstoneSyncRepositoryTest {

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var files: File
    private lateinit var roots: AccountScopedFileRoots

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        files = File(context.cacheDir, "tombstone-sync-${UUID.randomUUID()}")
        roots = AccountScopedFileRoots(files)
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        files.deleteRecursively()
    }

    @Test
    fun appliesMarkerPurgesOnlyOwnerCiphertextAndPreservesDerivedIndex() = runTest {
        val blob = BlobId.parseRest(BLOB)
        val capsule = CapsuleId.parseRest(CAPSULE)
        seedIncoming(OWNER, capsule, blob)
        seedIncoming(OTHER_OWNER, CapsuleId.parseRest(OTHER_CAPSULE), BlobId.parseRest(OTHER_BLOB))
        val prefetchTemp = roots.child(OWNER, AccountScopedFileRoots.ChildRoot.TEMP)
            .resolve("incoming-prefetch/${capsule.toRestString()}/${blob.toRestString()}.ciphertext.tmp")
            .apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val acceptanceTemp = roots.child(OWNER, AccountScopedFileRoots.ChildRoot.TEMP)
            .resolve("incoming-recognition/${capsule.toRestString()}/blobs/${blob.toRestString()}.ciphertext.tmp")
            .apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(2)) }
        val derivedIndex = roots.child(OWNER, AccountScopedFileRoots.ChildRoot.FINGERPRINTS)
            .resolve("capsules/${capsule.toRestString()}.index.bundle")
            .apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(7, 8, 9)) }

        val result = repository(
            Feed(
                success(
                    tombstones = listOf(IncomingTombstone(capsule, 1_700_000_000_000L)),
                    nextCursor = "r1",
                ),
            ),
        ).syncNextPage()

        assertIs<IncomingTombstoneSyncResult.Committed>(result)
        assertEquals("r1", database.recipientTombstoneDao()
            .getWatermarkForOwner(OWNER.toRestString())!!.serverCursor)
        assertEquals(
            "REVOKED",
            database.incomingCapsuleDao().getByCapsuleIdAndOwner(
                CAPSULE,
                OWNER.toRestString(),
            )!!.serverStatus,
        )
        assertNull(database.incomingEnvelopeDao().getByCapsuleIdAndOwner(CAPSULE, OWNER.toRestString()))
        assertTrue(database.blobCacheDao().getAllByCapsuleIdAndOwner(CAPSULE, OWNER.toRestString()).isEmpty())
        assertFalse(roots.incomingCiphertextPath(OWNER, capsule, blob).toFile().exists())
        assertFalse(prefetchTemp.exists())
        assertFalse(acceptanceTemp.exists())
        assertTrue(derivedIndex.exists())

        val other = database.incomingCapsuleDao().getByCapsuleIdAndOwner(
            OTHER_CAPSULE,
            OTHER_OWNER.toRestString(),
        )
        assertNotNull(other)
        assertEquals("READY", other.serverStatus)
        assertEquals(
            1,
            database.blobCacheDao().getAllByCapsuleIdAndOwner(
                OTHER_CAPSULE,
                OTHER_OWNER.toRestString(),
            ).size,
        )
        assertNull(database.recipientTombstoneDao().getForOwner(OTHER_OWNER.toRestString(), CAPSULE))
    }

    @Test
    fun replayAfterRepositoryRestartConvergesWithoutRewindingWatermark() = runTest {
        val capsule = CapsuleId.parseRest(CAPSULE)
        val feed = Feed(
            success(listOf(IncomingTombstone(capsule, 22L)), "r1"),
            success(listOf(IncomingTombstone(capsule, 22L)), "r1"),
        )
        val first = repository(feed, clock = { 100L }).syncNextPage()
        assertIs<IncomingTombstoneSyncResult.Committed>(first)

        val restarted = repository(feed, clock = { 200L })
        val replay = restarted.syncNextPage()

        assertIs<IncomingTombstoneSyncResult.Committed>(replay)
        assertEquals(listOf<String?>(null, "r1"), feed.requestedCursors)
        val watermark = database.recipientTombstoneDao()
            .getWatermarkForOwner(OWNER.toRestString())!!
        assertEquals("r1", watermark.serverCursor)
        assertEquals(200L, watermark.lastSyncedAtEpochMs)
        assertEquals(22L, database.recipientTombstoneDao()
            .getForOwner(OWNER.toRestString(), CAPSULE)!!.revokedAtEpochMs)
    }

    @Test
    fun fileFailureLeavesMarkerAndWatermarkForSafeRetry() = runTest {
        val capsule = CapsuleId.parseRest(CAPSULE)
        seedIncoming(OWNER, capsule, BlobId.parseRest(BLOB))
        val feed = Feed(success(listOf(IncomingTombstone(capsule, 33L)), "r1"))
        val failed = repository(
            feed,
            filePurger = { _, _ -> error("simulated local delete failure") },
        ).syncNextPage()

        val failure = assertIs<IncomingTombstoneSyncResult.Failure>(failed)
        assertEquals(IncomingSyncFailure.DATABASE_FAILURE, failure.reason)
        assertTrue(failure.retryable)
        assertNull(database.recipientTombstoneDao().getForOwner(OWNER.toRestString(), CAPSULE))
        assertNull(database.recipientTombstoneDao().getWatermarkForOwner(OWNER.toRestString()))
        assertEquals("READY", database.incomingCapsuleDao()
            .getByCapsuleIdAndOwner(CAPSULE, OWNER.toRestString())!!.serverStatus)
    }

    @Test
    fun conflictingRevokedAtIsImmutableAndDoesNotAdvanceCursor() = runTest {
        val capsule = CapsuleId.parseRest(CAPSULE)
        val first = repository(
            Feed(success(listOf(IncomingTombstone(capsule, 44L)), "r1")),
        ).syncNextPage()
        assertIs<IncomingTombstoneSyncResult.Committed>(first)

        val conflicting = repository(
            Feed(success(listOf(IncomingTombstone(capsule, 45L)), "r2")),
        ).syncNextPage()

        val failure = assertIs<IncomingTombstoneSyncResult.Failure>(conflicting)
        assertEquals(IncomingSyncFailure.DATABASE_FAILURE, failure.reason)
        assertEquals("r1", database.recipientTombstoneDao()
            .getWatermarkForOwner(OWNER.toRestString())!!.serverCursor)
        assertEquals(44L, database.recipientTombstoneDao()
            .getForOwner(OWNER.toRestString(), CAPSULE)!!.revokedAtEpochMs)
    }

    @Test
    fun tombstoneBeforeIncomingPagePreventsOfflineResurrection() = runTest {
        val capsule = CapsuleId.parseRest(CAPSULE)
        database.recipientTombstoneDao().applyPage(
            ownerUserId = OWNER.toRestString(),
            expectedCursor = null,
            tombstones = listOf(RecipientTombstoneEntity(OWNER.toRestString(), CAPSULE, 55L)),
            nextCursor = "r1",
            committedAtEpochMs = 1L,
        )

        val incoming = capsuleEntity(OWNER, capsule)
        val envelope = IncomingEnvelopeEntity(
            capsuleId = CAPSULE,
            ownerUserId = OWNER.toRestString(),
            recipientKeyBundleId = KEY_BUNDLE,
            hpkeCiphertext = byteArrayOf(1),
            transportSha256 = ByteArray(32),
            receivedAtEpochMs = 1L,
        )
        database.incomingPageDao().commitPage(
            ownerUserId = OWNER.toRestString(),
            expectedCursor = null,
            capsules = listOf(incoming),
            envelopes = listOf(envelope),
            blobs = emptyList(),
            nextCursor = "incoming-r1",
            committedAtEpochMs = 1L,
        )

        assertNull(database.incomingCapsuleDao().getByCapsuleIdAndOwner(CAPSULE, OWNER.toRestString()))
        assertNull(database.incomingEnvelopeDao().getByCapsuleIdAndOwner(CAPSULE, OWNER.toRestString()))
        assertEquals("incoming-r1", database.syncCursorDao().get(
            OWNER.toRestString(),
            "incoming",
        )!!.serverCursor)
        assertNotNull(database.recipientTombstoneDao().getForOwner(OWNER.toRestString(), CAPSULE))
    }

    @Test
    fun redactedObjectsDoNotExposeWireMaterial() {
        val tombstone = IncomingTombstone(CapsuleId.parseRest(CAPSULE), 1L)
        assertFalse(tombstone.toString().contains(CAPSULE))
        assertFalse(success(listOf(tombstone), "cursor-secret").toString().contains("cursor-secret"))
        assertFalse(TombstoneBlobRow(BLOB, CAPSULE, OWNER.toRestString(), "/secret/path")
            .toString().contains("/secret/path"))
    }

    private fun repository(
        feed: Feed,
        clock: () -> Long = { 100L },
        filePurger: (suspend (UserId, List<TombstoneBlobRow>) -> Unit)? = null,
    ): IncomingTombstoneSyncRepository = if (filePurger == null) {
        IncomingTombstoneSyncRepository(
            remote = feed,
            database = database,
            roots = roots,
            currentSession = { IncomingSyncSession(OWNER, "access-token") },
            clockEpochMs = clock,
        )
    } else {
        IncomingTombstoneSyncRepository(
            remote = feed,
            database = database,
            roots = roots,
            currentSession = { IncomingSyncSession(OWNER, "access-token") },
            clockEpochMs = clock,
            filePurger = filePurger,
        )
    }

    private suspend fun seedIncoming(owner: UserId, capsule: CapsuleId, blob: BlobId) {
        val ownerString = owner.toRestString()
        val capsuleString = capsule.toRestString()
        val blobString = blob.toRestString()
        database.incomingCapsuleDao().upsertAllForOwner(ownerString, listOf(capsuleEntity(owner, capsule)))
        database.incomingEnvelopeDao().upsertForOwner(
            ownerString,
            IncomingEnvelopeEntity(
                capsuleId = capsuleString,
                ownerUserId = ownerString,
                recipientKeyBundleId = KEY_BUNDLE,
                hpkeCiphertext = byteArrayOf(2, 3),
                transportSha256 = ByteArray(32),
                receivedAtEpochMs = 1L,
            ),
        )
        val path = roots.incomingCiphertextPath(owner, capsule, blob).toFile()
        path.parentFile!!.mkdirs()
        path.writeBytes(byteArrayOf(4, 5, 6))
        database.blobCacheDao().upsertForOwner(
            ownerString,
            BlobCacheEntity(
                blobId = blobString,
                ownerUserId = ownerString,
                capsuleId = capsuleString,
                kind = "PHOTO",
                ordinal = 0,
                expectedSizeBytes = 3L,
                expectedSha256 = ByteArray(32),
                localPath = path.path,
                cacheState = BlobCacheState.CACHED,
            ),
        )
    }

    private fun capsuleEntity(owner: UserId, capsule: CapsuleId) = IncomingCapsuleEntity(
        capsuleId = capsule.toRestString(),
        ownerUserId = owner.toRestString(),
        senderUserId = SENDER,
        recipientUserId = owner.toRestString(),
        senderSigningKeyBundleId = KEY_BUNDLE,
        recipientEncryptionKeyBundleId = KEY_BUNDLE,
        protocolVersion = 1,
        serverStatus = "READY",
        readyAtEpochMs = 1L,
        signedStatementBytes = byteArrayOf(1),
        materialState = LocalMaterialState.DISCOVERED,
    )

    private fun success(
        tombstones: List<IncomingTombstone>,
        nextCursor: String?,
    ) = IncomingTombstoneResult.Success(
        IncomingTombstonePage(tombstones, hasMore = false, nextCursor = nextCursor),
        httpStatus = 200,
    )

    private class Feed(vararg results: IncomingTombstoneResult) : IncomingTombstoneFeed {
        private val remaining = ArrayDeque(results.toList())
        val requestedCursors = mutableListOf<String?>()

        override suspend fun fetchPage(
            ownerUserId: UserId,
            cursor: String?,
            limit: Int,
            accessToken: String,
        ): IncomingTombstoneResult {
            assertEquals(OWNER, ownerUserId)
            assertEquals(50, limit)
            assertEquals("access-token", accessToken)
            requestedCursors += cursor
            return remaining.removeFirst()
        }
    }

    private companion object {
        val OWNER = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a001")
        val OTHER_OWNER = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a002")
        const val CAPSULE = "0198f0a0-0000-7000-8000-00000000c001"
        const val OTHER_CAPSULE = "0198f0a0-0000-7000-8000-00000000c002"
        const val BLOB = "0198f0a0-0000-7000-8000-00000000d001"
        const val OTHER_BLOB = "0198f0a0-0000-7000-8000-00000000d002"
        const val SENDER = "0198f0a0-0000-7000-8000-00000000a003"
        const val KEY_BUNDLE = "0198f0a0-0000-7000-8000-00000000b001"
    }
}
