package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.core.model.LocalMaterialState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingCapsuleQuarantineTest {

    private companion object {
        const val OWNER_A = "0198f0a0-0000-7000-8000-00000000ow01"
        const val OWNER_B = "0198f0a0-0000-7000-8000-00000000ow02"
        const val CAPSULE = "0198f0a0-0000-7000-8000-00000000ca01"
    }

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var dao: IncomingCapsuleDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.incomingCapsuleDao()
    }

    @After
    fun tearDown() {
        if (database.isOpen) database.close()
    }

    @Test
    fun readyDiscoveredQuarantineChangesOnlyCapsuleMaterialState() = runBlocking {
        val before = capsule()
        val envelope = envelope()
        val blob = blob()
        dao.upsertAllForOwner(OWNER_A, listOf(before))
        database.incomingEnvelopeDao().upsertForOwner(OWNER_A, envelope)
        database.blobCacheDao().upsertForOwner(OWNER_A, blob)

        assertEquals(
            IncomingCapsuleQuarantineResult.Quarantined,
            dao.quarantineReadyDiscoveredForOwner(OWNER_A, CAPSULE),
        )

        val after = dao.getByCapsuleIdAndOwner(CAPSULE, OWNER_A)!!
        assertEquals(LocalMaterialState.CORRUPT, after.materialState)
        assertEquals(before.capsuleId, after.capsuleId)
        assertEquals(before.ownerUserId, after.ownerUserId)
        assertEquals(before.senderUserId, after.senderUserId)
        assertEquals(before.recipientUserId, after.recipientUserId)
        assertEquals(before.senderSigningKeyBundleId, after.senderSigningKeyBundleId)
        assertEquals(before.recipientEncryptionKeyBundleId, after.recipientEncryptionKeyBundleId)
        assertEquals(before.protocolVersion, after.protocolVersion)
        assertEquals(before.serverStatus, after.serverStatus)
        assertEquals(before.readyAtEpochMs, after.readyAtEpochMs)
        assertTrue(before.signedStatementBytes.contentEquals(after.signedStatementBytes))
        assertTrue(before.signedStatementSha256.contentEquals(after.signedStatementSha256))
        assertTrue(before.publishSignatureBytes.contentEquals(after.publishSignatureBytes))
        assertEquals(envelope, database.incomingEnvelopeDao().getByCapsuleIdAndOwner(CAPSULE, OWNER_A))
        assertEquals(blob, database.blobCacheDao().getByBlobIdAndOwner(blob.blobId, OWNER_A))
    }

    @Test
    fun readyCorruptIsAnIdempotentReplay() = runBlocking {
        dao.upsertAllForOwner(OWNER_A, listOf(capsule()))
        assertTrue(
            dao.transitionMaterialStateForOwner(
                OWNER_A,
                CAPSULE,
                LocalMaterialState.CORRUPT,
            ) is LocalMaterialTransitionResult.Accepted,
        )

        assertEquals(
            IncomingCapsuleQuarantineResult.AlreadyCorrupt,
            dao.quarantineReadyDiscoveredForOwner(OWNER_A, CAPSULE),
        )
        assertEquals(LocalMaterialState.CORRUPT, dao.getByCapsuleIdAndOwner(CAPSULE, OWNER_A)!!.materialState)
    }

    @Test
    fun missingAndForeignOwnerAreIndistinguishableAndUnchanged() = runBlocking {
        val foreign = capsule()
        dao.upsertAllForOwner(OWNER_A, listOf(foreign))

        val foreignResult = dao.quarantineReadyDiscoveredForOwner(OWNER_B, CAPSULE)
        val missingResult = dao.quarantineReadyDiscoveredForOwner(
            OWNER_A,
            "0198f0a0-0000-7000-8000-00000000missing",
        )

        assertEquals(IncomingCapsuleQuarantineResult.MissingOrForeignOwner, foreignResult)
        assertEquals(foreignResult, missingResult)
        assertEquals(foreign, dao.getByCapsuleIdAndOwner(CAPSULE, OWNER_A))
        assertEquals(LocalMaterialState.DISCOVERED, dao.getByCapsuleIdAndOwner(CAPSULE, OWNER_A)!!.materialState)
    }

    @Test
    fun wrongServerStatusAndEveryAdvancedStateRemainUnchanged() = runBlocking {
        val wrongStatusId = "0198f0a0-0000-7000-8000-00000000ca02"
        dao.upsertAllForOwner(
            OWNER_A,
            listOf(capsule(capsuleId = wrongStatusId).copy(serverStatus = "PENDING")),
        )
        assertEquals(
            IncomingCapsuleQuarantineResult.ConcurrentOrStateChanged,
            dao.quarantineReadyDiscoveredForOwner(OWNER_A, wrongStatusId),
        )
        assertEquals(
            LocalMaterialState.DISCOVERED,
            dao.getByCapsuleIdAndOwner(wrongStatusId, OWNER_A)!!.materialState,
        )

        listOf(
            LocalMaterialState.INDEX_CACHED,
            LocalMaterialState.MATERIAL_CACHED,
            LocalMaterialState.FINGERPRINT_ACCEPTED,
        ).forEachIndexed { index, state ->
            val id = "0198f0a0-0000-7000-8000-00000000ca0${index + 3}"
            dao.upsertAllForOwner(OWNER_A, listOf(capsule(capsuleId = id)))
            for (target in listOf(
                LocalMaterialState.INDEX_CACHED,
                LocalMaterialState.MATERIAL_CACHED,
                LocalMaterialState.FINGERPRINT_ACCEPTED,
            )) {
                dao.transitionMaterialStateForOwner(OWNER_A, id, target)
                if (target == state) break
            }

            assertEquals(
                IncomingCapsuleQuarantineResult.ConcurrentOrStateChanged,
                dao.quarantineReadyDiscoveredForOwner(OWNER_A, id),
            )
            assertEquals(state, dao.getByCapsuleIdAndOwner(id, OWNER_A)!!.materialState)
        }
    }

    @Test
    fun twoConcurrentCallersHaveOneWinnerAndOneIdempotentReplay() = runBlocking {
        dao.upsertAllForOwner(OWNER_A, listOf(capsule()))

        val results = coroutineScope {
            listOf(
                async { dao.quarantineReadyDiscoveredForOwner(OWNER_A, CAPSULE) },
                async { dao.quarantineReadyDiscoveredForOwner(OWNER_A, CAPSULE) },
            ).awaitAll()
        }

        assertEquals(1, results.count { it == IncomingCapsuleQuarantineResult.Quarantined })
        assertEquals(1, results.count { it == IncomingCapsuleQuarantineResult.AlreadyCorrupt })
        assertEquals(LocalMaterialState.CORRUPT, dao.getByCapsuleIdAndOwner(CAPSULE, OWNER_A)!!.materialState)
    }

    @Test
    fun databaseFailureMapsToRedactedDatabaseUnavailable() = runBlocking {
        dao.upsertAllForOwner(OWNER_A, listOf(capsule()))
        database.openHelper.writableDatabase.execSQL("DROP TABLE incoming_capsule")

        assertEquals(
            IncomingCapsuleQuarantineResult.DatabaseUnavailable,
            dao.quarantineReadyDiscoveredForOwner(OWNER_A, CAPSULE),
        )
    }

    @Test
    fun cancellationFromCasIsRethrownExactly() {
        val expected = CancellationException("quarantine cancellation")

        val actual = org.junit.Assert.assertThrows(CancellationException::class.java) {
            runBlocking {
                resolveIncomingCapsuleQuarantine(
                    compareAndSet = { throw expected },
                    rereadOwnedCapsule = { null },
                )
            }
        }

        assertSame(expected, actual)
    }

    @Test
    fun everyOutcomeIsRedacted() {
        val outcomes = listOf(
            IncomingCapsuleQuarantineResult.Quarantined,
            IncomingCapsuleQuarantineResult.AlreadyCorrupt,
            IncomingCapsuleQuarantineResult.MissingOrForeignOwner,
            IncomingCapsuleQuarantineResult.ConcurrentOrStateChanged,
            IncomingCapsuleQuarantineResult.DatabaseUnavailable,
        )

        outcomes.forEach { outcome ->
            assertFalse(outcome.toString().contains(OWNER_A))
            assertFalse(outcome.toString().contains(CAPSULE))
        }
    }

    private fun capsule(
        capsuleId: String = CAPSULE,
        state: LocalMaterialState = LocalMaterialState.DISCOVERED,
    ) = IncomingCapsuleEntity(
        capsuleId = capsuleId,
        ownerUserId = OWNER_A,
        senderUserId = "0198f0a0-0000-7000-8000-00000000se01",
        recipientUserId = OWNER_A,
        senderSigningKeyBundleId = "0198f0a0-0000-7000-8000-00000000sk01",
        recipientEncryptionKeyBundleId = "0198f0a0-0000-7000-8000-00000000rk01",
        protocolVersion = 1,
        serverStatus = "READY",
        readyAtEpochMs = 1_755_000_000_000,
        signedStatementBytes = byteArrayOf(1, 2, 3),
        signedStatementSha256 = byteArrayOf(4, 5, 6),
        publishSignatureBytes = byteArrayOf(7, 8, 9),
        materialState = state,
    )

    private fun envelope() = IncomingEnvelopeEntity(
        capsuleId = CAPSULE,
        ownerUserId = OWNER_A,
        recipientKeyBundleId = "0198f0a0-0000-7000-8000-00000000rk01",
        hpkeCiphertext = byteArrayOf(9, 8, 7),
        transportSha256 = ByteArray(32) { 4 },
        receivedAtEpochMs = 1_755_000_100_000,
    )

    private fun blob() = BlobCacheEntity(
        blobId = "0198f0a0-0000-7000-8000-00000000bl01",
        ownerUserId = OWNER_A,
        capsuleId = CAPSULE,
        kind = "RECOGNITION_MANIFEST",
        ordinal = 0,
        expectedSizeBytes = 8_192,
        expectedSha256 = ByteArray(32) { (it + 7).toByte() },
        localPath = "files/capsules/ca01/blob-bl01.bin",
        cacheState = BlobCacheState.DOWNLOADING,
    )
}
