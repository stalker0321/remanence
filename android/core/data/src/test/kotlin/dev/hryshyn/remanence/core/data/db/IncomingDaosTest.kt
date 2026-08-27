package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.core.model.LocalMaterialState
import kotlinx.coroutines.runBlocking
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
class IncomingDaosTest {

    private companion object {
        const val OWNER = "0198f0a0-0000-7000-8000-00000000ow01"
    }

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var capsuleDao: IncomingCapsuleDao
    private lateinit var envelopeDao: IncomingEnvelopeDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        capsuleDao = database.incomingCapsuleDao()
        envelopeDao = database.incomingEnvelopeDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun capsule(
        capsuleId: String = "0198f0a0-0000-7000-8000-00000000ca01",
        state: LocalMaterialState = LocalMaterialState.DISCOVERED,
        readyAt: Long = 1_755_000_000_000,
    ) = IncomingCapsuleEntity(
        capsuleId = capsuleId,
        ownerUserId = "0198f0a0-0000-7000-8000-00000000ow01",
        senderUserId = "0198f0a0-0000-7000-8000-00000000se01",
        recipientUserId = "0198f0a0-0000-7000-8000-00000000re01",
        senderSigningKeyBundleId = "0198f0a0-0000-7000-8000-00000000sk01",
        recipientEncryptionKeyBundleId = "0198f0a0-0000-7000-8000-00000000rk01",
        protocolVersion = 1,
        serverStatus = "READY",
        readyAtEpochMs = readyAt,
        signedStatementBytes = byteArrayOf(1, 2, 3),
        materialState = state,
    )

    private fun envelope(capsuleId: String = "0198f0a0-0000-7000-8000-00000000ca01") =
        IncomingEnvelopeEntity(
            capsuleId = capsuleId,
            ownerUserId = "0198f0a0-0000-7000-8000-00000000ow01",
            recipientKeyBundleId = "0198f0a0-0000-7000-8000-00000000rk01",
            hpkeCiphertext = byteArrayOf(9, 8, 7),
            transportSha256 = ByteArray(32) { 4 },
            receivedAtEpochMs = 1_755_000_100_000,
        )

    @Test
    fun upsertThenReadReturnsRoutedMetadataOnly() = runBlocking {
        val record = capsule()
        capsuleDao.upsertAllForOwner(listOf(record))
        assertEquals(record.signedStatementBytes.toList(), capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!.signedStatementBytes.toList())
    }

    @Test
    fun replayedUpsertIsIdempotentByCapsuleId() = runBlocking {
        val record = capsule()
        capsuleDao.upsertAllForOwner(listOf(record))
        val updated = record.copy(serverStatus = "READY", readyAtEpochMs = 1_755_000_999_999)
        capsuleDao.upsertAllForOwner(listOf(updated))

        val loaded = capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!
        assertEquals(updated.readyAtEpochMs, loaded.readyAtEpochMs)
        // exactly one row for the capsule id after replay
        assertEquals(1, countRows("incoming_capsule"))
    }

    @Test
    fun freshAdvancedOrCorruptCandidateIsRejected() = runBlocking {
        for (state in listOf(
            LocalMaterialState.INDEX_CACHED,
            LocalMaterialState.MATERIAL_CACHED,
            LocalMaterialState.FINGERPRINT_ACCEPTED,
            LocalMaterialState.CORRUPT,
        )) {
            val capsuleId = "0198f0a0-0000-7000-8000-00000000s${state.name.take(2)}"
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    capsuleDao.upsertAllForOwner(listOf(capsule(capsuleId = capsuleId, state = state)))
                }
            }
            assertNull(capsuleDao.getByCapsuleIdAndOwner(capsuleId, OWNER))
        }
        assertEquals(0, countRows("incoming_capsule"))
    }

    @Test
    fun invalidFreshCandidateDoesNotPartiallyWriteBatch() = runBlocking {
        val valid = capsule(capsuleId = "0198f0a0-0000-7000-8000-00000000valid")
        val invalid = capsule(
            capsuleId = "0198f0a0-0000-7000-8000-00000000invalid",
            state = LocalMaterialState.MATERIAL_CACHED,
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { capsuleDao.upsertAllForOwner(listOf(valid, invalid)) }
        }

        assertNull(capsuleDao.getByCapsuleIdAndOwner(valid.capsuleId, OWNER))
        assertNull(capsuleDao.getByCapsuleIdAndOwner(invalid.capsuleId, OWNER))
        assertEquals(0, countRows("incoming_capsule"))
    }

    @Test
    fun sameOwnerReplayCannotOverwriteAdvancedStoredState() = runBlocking {
        val record = capsule()
        capsuleDao.upsertAllForOwner(listOf(record))
        assertTrue(
            capsuleDao.transitionMaterialStateForOwner(
                ownerUserId = OWNER,
                capsuleId = record.capsuleId,
                requestedTarget = LocalMaterialState.INDEX_CACHED,
            ) is LocalMaterialTransitionResult.Accepted,
        )

        capsuleDao.upsertAllForOwner(
            listOf(
                record.copy(
                    serverStatus = "READY-REPLAY",
                    materialState = LocalMaterialState.FINGERPRINT_ACCEPTED,
                ),
            ),
        )

        val replayed = capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!
        assertEquals("READY-REPLAY", replayed.serverStatus)
        assertEquals(LocalMaterialState.INDEX_CACHED, replayed.materialState)
    }

    @Test
    fun materialStateTransitionsFollowCanonicalForwardChainThroughRoom() = runBlocking {
        val record = capsule(state = LocalMaterialState.DISCOVERED)
        capsuleDao.upsertAllForOwner(listOf(record))

        val forwardStates = listOf(
            LocalMaterialState.INDEX_CACHED,
            LocalMaterialState.MATERIAL_CACHED,
            LocalMaterialState.FINGERPRINT_ACCEPTED,
        )
        var observedState = LocalMaterialState.DISCOVERED
        for (requestedTarget in forwardStates) {
            val result = capsuleDao.transitionMaterialStateForOwner(
                ownerUserId = OWNER,
                capsuleId = record.capsuleId,
                requestedTarget = requestedTarget,
            )
            assertTrue(result is LocalMaterialTransitionResult.Accepted)
            assertEquals(observedState, (result as LocalMaterialTransitionResult.Accepted).transition.from)
            assertEquals(requestedTarget, result.transition.to)
            observedState = requestedTarget
        }
        assertEquals(
            LocalMaterialState.FINGERPRINT_ACCEPTED,
            capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!.materialState,
        )

        val regression = capsuleDao.transitionMaterialStateForOwner(
            ownerUserId = OWNER,
            capsuleId = record.capsuleId,
            requestedTarget = LocalMaterialState.INDEX_CACHED,
        )
        assertTrue(regression is LocalMaterialTransitionResult.Rejected)
        assertEquals(
            LocalMaterialState.FINGERPRINT_ACCEPTED,
            capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!.materialState,
        )
    }

    @Test
    fun idempotentReplayIsPreciseNoWriteResult() = runBlocking {
        val record = capsule()
        capsuleDao.upsertAllForOwner(listOf(record))
        val changesBefore = totalChanges()

        val result = capsuleDao.transitionMaterialStateForOwner(
            ownerUserId = OWNER,
            capsuleId = record.capsuleId,
            requestedTarget = LocalMaterialState.DISCOVERED,
        )

        assertTrue(result is LocalMaterialTransitionResult.IdempotentReplay)
        assertEquals(
            LocalMaterialState.DISCOVERED,
            (result as LocalMaterialTransitionResult.IdempotentReplay).transition.from,
        )
        assertEquals(changesBefore, totalChanges())
    }

    @Test
    fun rejectedAndMissingTransitionsAreDistinctNoWriteResults() = runBlocking {
        val record = capsule()
        capsuleDao.upsertAllForOwner(listOf(record))
        val changesBeforeRejected = totalChanges()

        val rejected = capsuleDao.transitionMaterialStateForOwner(
            ownerUserId = OWNER,
            capsuleId = record.capsuleId,
            requestedTarget = LocalMaterialState.MATERIAL_CACHED,
        )
        assertTrue(rejected is LocalMaterialTransitionResult.Rejected)
        assertEquals(changesBeforeRejected, totalChanges())
        assertEquals(LocalMaterialState.DISCOVERED, capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!.materialState)

        val changesBeforeMissing = totalChanges()
        val missing = capsuleDao.transitionMaterialStateForOwner(
            ownerUserId = OWNER,
            capsuleId = "0198f0a0-0000-7000-8000-00000000missing",
            requestedTarget = LocalMaterialState.INDEX_CACHED,
        )
        assertTrue(missing is LocalMaterialTransitionResult.MissingRow)
        assertEquals(changesBeforeMissing, totalChanges())
    }

    @Test
    fun corruptEntryOnlyRecoversToDiscovered() = runBlocking {
        val record = capsule()
        capsuleDao.upsertAllForOwner(listOf(record))

        val enteredCorrupt = capsuleDao.transitionMaterialStateForOwner(
            ownerUserId = OWNER,
            capsuleId = record.capsuleId,
            requestedTarget = LocalMaterialState.CORRUPT,
        )
        assertTrue(enteredCorrupt is LocalMaterialTransitionResult.Accepted)
        assertEquals(LocalMaterialState.CORRUPT, capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!.materialState)

        val invalidRecovery = capsuleDao.transitionMaterialStateForOwner(
            ownerUserId = OWNER,
            capsuleId = record.capsuleId,
            requestedTarget = LocalMaterialState.MATERIAL_CACHED,
        )
        assertTrue(invalidRecovery is LocalMaterialTransitionResult.Rejected)
        assertEquals(LocalMaterialState.CORRUPT, capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!.materialState)

        val recovery = capsuleDao.transitionMaterialStateForOwner(
            ownerUserId = OWNER,
            capsuleId = record.capsuleId,
            requestedTarget = LocalMaterialState.DISCOVERED,
        )
        assertTrue(recovery is LocalMaterialTransitionResult.Accepted)
        assertEquals(LocalMaterialState.DISCOVERED, capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!.materialState)
    }

    @Test
    fun casLossIsConcurrentOrStaleAndNeverAccepted() = runBlocking {
        val record = capsule()
        capsuleDao.upsertAllForOwner(listOf(record))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER force_material_cas_loss " +
                "BEFORE UPDATE OF material_state ON incoming_capsule " +
                "WHEN OLD.material_state = 'DISCOVERED' AND NEW.material_state = 'INDEX_CACHED' " +
                "BEGIN " +
                "UPDATE incoming_capsule SET material_state = 'CORRUPT' WHERE capsule_id = OLD.capsule_id; " +
                "SELECT RAISE(IGNORE); " +
                "END",
        )

        val result = capsuleDao.transitionMaterialStateForOwner(
            ownerUserId = OWNER,
            capsuleId = record.capsuleId,
            requestedTarget = LocalMaterialState.INDEX_CACHED,
        )

        assertTrue(result is LocalMaterialTransitionResult.ConcurrentOrStale)
        assertTrue(result !is LocalMaterialTransitionResult.Accepted)
        assertEquals(LocalMaterialState.CORRUPT, capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!.materialState)
    }

    @Test
    fun envelopeUpsertIsIdempotentAndReplaySafe() = runBlocking {
        val record = envelope()
        envelopeDao.upsertForOwner(record)
        envelopeDao.upsertForOwner(record.copy(receivedAtEpochMs = 1_755_000_200_000))

        val loaded = envelopeDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!
        assertEquals(1_755_000_200_000, loaded.receivedAtEpochMs)
        assertEquals(record.hpkeCiphertext.toList(), loaded.hpkeCiphertext.toList())
        assertEquals(1, countRows("incoming_envelope"))
    }

    @Test
    fun clearRemovesIncomingRecords() = runBlocking {
        val id = "0198f0a0-0000-7000-8000-00000000ca02"
        capsuleDao.upsertAllForOwner(listOf(capsule(capsuleId = id)))
        envelopeDao.upsertForOwner(envelope(capsuleId = id))
        capsuleDao.clear()
        envelopeDao.clear()
        assertNull(capsuleDao.getByCapsuleIdAndOwner(id, OWNER))
        assertNull(envelopeDao.getByCapsuleIdAndOwner(id, OWNER))
    }

    private fun countRows(table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun totalChanges(): Int =
        database.openHelper.readableDatabase.query("SELECT total_changes()").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
