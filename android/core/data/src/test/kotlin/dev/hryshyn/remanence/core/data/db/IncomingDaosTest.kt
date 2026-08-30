package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.UserId
import java.lang.reflect.Modifier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        capsuleDao.upsertAllForOwner(OWNER, listOf(record))
        assertEquals(record.signedStatementBytes.toList(), capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!.signedStatementBytes.toList())
    }

    @Test
    fun migratedEmptyCryptoRejectsEmptyCandidateWithoutAdvancingCursor() = runBlocking {
        val legacy = capsule()
        capsuleDao.upsertAllForOwner(OWNER, listOf(legacy))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                database.incomingPageDao().commitPage(
                    ownerUserId = OWNER,
                    expectedCursor = null,
                    capsules = listOf(legacy),
                    envelopes = listOf(envelope()),
                    blobs = emptyList(),
                    nextCursor = "must-not-advance",
                    committedAtEpochMs = 1_755_000_200_000,
                )
            }
        }

        assertEquals(legacy, capsuleDao.getByCapsuleIdAndOwner(legacy.capsuleId, OWNER))
        assertNull(database.syncCursorDao().get(OWNER, "incoming"))
        assertNull(envelopeDao.getByCapsuleIdAndOwner(legacy.capsuleId, OWNER))
    }

    @Test
    fun pageCommitPersistsTerminalCursorAndEmptyContinuation() = runBlocking {
        val record = capsule()
        database.incomingPageDao().commitPage(
            ownerUserId = OWNER,
            expectedCursor = null,
            capsules = listOf(record),
            envelopes = listOf(envelope()),
            blobs = emptyList(),
            nextCursor = "terminal-cursor",
            committedAtEpochMs = 1_755_000_200_000,
        )

        database.incomingPageDao().commitPage(
            ownerUserId = OWNER,
            expectedCursor = "terminal-cursor",
            capsules = emptyList(),
            envelopes = emptyList(),
            blobs = emptyList(),
            nextCursor = "terminal-cursor",
            committedAtEpochMs = 1_755_000_300_000,
        )

        assertEquals(
            "terminal-cursor",
            database.syncCursorDao().get(OWNER, "incoming")!!.serverCursor,
        )
        val storedCapsule = capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!
        assertEquals(record.capsuleId, storedCapsule.capsuleId)
        assertEquals(record.ownerUserId, storedCapsule.ownerUserId)
        assertEquals(record.signedStatementBytes.toList(), storedCapsule.signedStatementBytes.toList())
        val storedEnvelope = envelopeDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!
        assertEquals(envelope().hpkeCiphertext.toList(), storedEnvelope.hpkeCiphertext.toList())
        assertEquals(envelope().transportSha256.toList(), storedEnvelope.transportSha256.toList())
    }

    @Test
    fun replayedUpsertIsIdempotentByCapsuleId() = runBlocking {
        val record = capsule()
        capsuleDao.upsertAllForOwner(OWNER, listOf(record))
        val updated = record.copy(serverStatus = "READY", readyAtEpochMs = 1_755_000_999_999)
        capsuleDao.upsertAllForOwner(OWNER, listOf(updated))

        val loaded = capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!
        assertEquals(updated.readyAtEpochMs, loaded.readyAtEpochMs)
        // exactly one row for the capsule id after replay
        assertEquals(1, countRows("incoming_capsule"))
    }

    @Test
    fun senderIndexCandidatesAreTypedOwnerScopedAndAcceptOnlyDurableStates() = runBlocking {
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a001")
        val otherOwner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a002")
        val indexed = capsule(
            capsuleId = "0198f0a0-0000-7000-8000-00000000a011",
            readyAt = 20,
        ).copy(ownerUserId = owner.toRestString())
        val material = capsule(
            capsuleId = "0198f0a0-0000-7000-8000-00000000a012",
            readyAt = 10,
        ).copy(ownerUserId = owner.toRestString())
        val accepted = capsule(
            capsuleId = "0198f0a0-0000-7000-8000-00000000a015",
            readyAt = 30,
        ).copy(ownerUserId = owner.toRestString())
        val wrongStatus = capsule(
            capsuleId = "0198f0a0-0000-7000-8000-00000000a016",
            readyAt = 0,
            state = LocalMaterialState.DISCOVERED,
        ).copy(ownerUserId = owner.toRestString(), serverStatus = "PENDING")
        val foreign = capsule(
            capsuleId = "0198f0a0-0000-7000-8000-00000000a013",
            readyAt = 1,
        ).copy(ownerUserId = otherOwner.toRestString())

        capsuleDao.upsertAllForOwner(owner.toRestString(), listOf(indexed, material, accepted, wrongStatus))
        capsuleDao.transitionMaterialStateForOwner(
            owner.toRestString(), indexed.capsuleId, LocalMaterialState.INDEX_CACHED,
        )
        capsuleDao.transitionMaterialStateForOwner(
            owner.toRestString(), material.capsuleId, LocalMaterialState.INDEX_CACHED,
        )
        capsuleDao.transitionMaterialStateForOwner(
            owner.toRestString(), material.capsuleId, LocalMaterialState.MATERIAL_CACHED,
        )
        capsuleDao.transitionMaterialStateForOwner(
            owner.toRestString(), accepted.capsuleId, LocalMaterialState.INDEX_CACHED,
        )
        capsuleDao.transitionMaterialStateForOwner(
            owner.toRestString(), accepted.capsuleId, LocalMaterialState.MATERIAL_CACHED,
        )
        capsuleDao.transitionMaterialStateForOwner(
            owner.toRestString(), accepted.capsuleId, LocalMaterialState.FINGERPRINT_ACCEPTED,
        )
        capsuleDao.upsertAllForOwner(otherOwner.toRestString(), listOf(foreign))

        val corrupt = capsule(
            capsuleId = "0198f0a0-0000-7000-8000-00000000a014",
            readyAt = 0,
        ).copy(ownerUserId = owner.toRestString())
        capsuleDao.upsertAllForOwner(owner.toRestString(), listOf(corrupt))
        capsuleDao.transitionMaterialStateForOwner(
            owner.toRestString(), corrupt.capsuleId, LocalMaterialState.CORRUPT,
        )

        val result = capsuleDao.selectSenderIndexCandidatesForOwner(owner)

        assertEquals(
            listOf(material.capsuleId, indexed.capsuleId, accepted.capsuleId),
            result.map { it.capsuleId.toRestString() },
        )
        assertTrue(result.all { it.ownerUserId == owner })
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
                    capsuleDao.upsertAllForOwner(OWNER, listOf(capsule(capsuleId = capsuleId, state = state)))
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
            runBlocking { capsuleDao.upsertAllForOwner(OWNER, listOf(valid, invalid)) }
        }

        assertNull(capsuleDao.getByCapsuleIdAndOwner(valid.capsuleId, OWNER))
        assertNull(capsuleDao.getByCapsuleIdAndOwner(invalid.capsuleId, OWNER))
        assertEquals(0, countRows("incoming_capsule"))
    }

    @Test
    fun sameOwnerReplayCannotOverwriteAdvancedStoredState() = runBlocking {
        val record = capsule()
        capsuleDao.upsertAllForOwner(OWNER, listOf(record))
        assertTrue(
            capsuleDao.transitionMaterialStateForOwner(
                ownerUserId = OWNER,
                capsuleId = record.capsuleId,
                requestedTarget = LocalMaterialState.INDEX_CACHED,
            ) is LocalMaterialTransitionResult.Accepted,
        )

        capsuleDao.upsertAllForOwner(
            OWNER,
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
        capsuleDao.upsertAllForOwner(OWNER, listOf(record))

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
        capsuleDao.upsertAllForOwner(OWNER, listOf(record))
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
        capsuleDao.upsertAllForOwner(OWNER, listOf(record))
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
        capsuleDao.upsertAllForOwner(OWNER, listOf(record))

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
        capsuleDao.upsertAllForOwner(OWNER, listOf(record))
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
        envelopeDao.upsertForOwner(OWNER, record)
        envelopeDao.upsertForOwner(OWNER, record.copy(receivedAtEpochMs = 1_755_000_200_000))

        val loaded = envelopeDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!
        assertEquals(1_755_000_200_000, loaded.receivedAtEpochMs)
        assertEquals(record.hpkeCiphertext.toList(), loaded.hpkeCiphertext.toList())
        assertEquals(1, countRows("incoming_envelope"))
    }

    @Test
    fun clearForOwnerRemovesOnlyOwnerRecords() = runBlocking {
        val ownerA = "0198f0a0-0000-7000-8000-00000000ow01"
        val ownerB = "0198f0a0-0000-7000-8000-00000000ow02"
        val idA = "0198f0a0-0000-7000-8000-00000000ca02"
        val idB = "0198f0a0-0000-7000-8000-00000000ca03"
        capsuleDao.upsertAllForOwner(ownerA, listOf(capsule(capsuleId = idA)))
        envelopeDao.upsertForOwner(ownerA, envelope(capsuleId = idA))
        capsuleDao.upsertAllForOwner(ownerB, listOf(capsule(capsuleId = idB).copy(ownerUserId = ownerB)))
        envelopeDao.upsertForOwner(ownerB, envelope(capsuleId = idB).copy(ownerUserId = ownerB))

        capsuleDao.clearForOwner(ownerA)
        envelopeDao.clearForOwner(ownerA)

        assertNull(capsuleDao.getByCapsuleIdAndOwner(idA, ownerA))
        assertNull(envelopeDao.getByCapsuleIdAndOwner(idA, ownerA))
        assertNotNull(capsuleDao.getByCapsuleIdAndOwner(idB, ownerB))
        assertNotNull(envelopeDao.getByCapsuleIdAndOwner(idB, ownerB))
    }

    @Test
    fun capsuleForeignOwnerUpsertIsRefusedAndOriginalRowUnchanged() = runBlocking {
        val ownerB = "0198f0a0-0000-7000-8000-00000000ow02"
        val original = capsule()
        capsuleDao.upsertAllForOwner(OWNER, listOf(original))
        val before = capsuleDao.getByCapsuleIdAndOwner(original.capsuleId, OWNER)!!

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                capsuleDao.upsertAllForOwner(
                    ownerB,
                    listOf(original.copy(ownerUserId = ownerB, serverStatus = "HACKED")),
                )
            }
        }

        assertEquals(1, countRows("incoming_capsule"))
        val after = capsuleDao.getByCapsuleIdAndOwner(original.capsuleId, OWNER)!!
        assertEquals(before, after)
        assertEquals(OWNER, after.ownerUserId)
    }

    @Test
    fun ownerArgumentMismatchIsRejectedBeforeAnyIncomingWrite() = runBlocking {
        val ownerB = "0198f0a0-0000-7000-8000-00000000ow02"
        val originalCapsule = capsule()
        val originalEnvelope = envelope()
        capsuleDao.upsertAllForOwner(OWNER, listOf(originalCapsule))
        envelopeDao.upsertForOwner(OWNER, originalEnvelope)
        val capsuleCandidate = originalCapsule.copy(ownerUserId = ownerB, serverStatus = "HACKED")
        val envelopeCandidate = originalEnvelope.copy(ownerUserId = ownerB, hpkeCiphertext = byteArrayOf(1))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { capsuleDao.upsertAllForOwner(OWNER, listOf(capsuleCandidate)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { envelopeDao.upsertForOwner(OWNER, envelopeCandidate) }
        }

        val capsuleAfter = capsuleDao.getByCapsuleIdAndOwner(originalCapsule.capsuleId, OWNER)!!
        assertEquals(originalCapsule, capsuleAfter)
        assertEquals(OWNER, capsuleAfter.ownerUserId)
        val envelopeAfter = envelopeDao.getByCapsuleIdAndOwner(originalEnvelope.capsuleId, OWNER)!!
        assertEquals(originalEnvelope, envelopeAfter)
        assertEquals(OWNER, envelopeAfter.ownerUserId)
    }

    @Test
    fun envelopeForeignOwnerUpsertIsRefusedAndOriginalRowUnchanged() = runBlocking {
        val ownerB = "0198f0a0-0000-7000-8000-00000000ow02"
        val original = envelope()
        envelopeDao.upsertForOwner(OWNER, original)
        val before = envelopeDao.getByCapsuleIdAndOwner(original.capsuleId, OWNER)!!

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                envelopeDao.upsertForOwner(
                    ownerB,
                    original.copy(ownerUserId = ownerB, hpkeCiphertext = byteArrayOf(1)),
                )
            }
        }

        assertEquals(1, countRows("incoming_envelope"))
        val after = envelopeDao.getByCapsuleIdAndOwner(original.capsuleId, OWNER)!!
        assertTrue(before.hpkeCiphertext.contentEquals(after.hpkeCiphertext))
        assertTrue(before.transportSha256.contentEquals(after.transportSha256))
        assertEquals(before.receivedAtEpochMs, after.receivedAtEpochMs)
        assertEquals(OWNER, after.ownerUserId)
    }

    @Test
    fun batchPreflightRejectsEntireBatchOnAnyOwnerMismatch() = runBlocking {
        val ownerB = "0198f0a0-0000-7000-8000-00000000ow02"
        val idA = "0198f0a0-0000-7000-8000-00000000ca03"
        val idB = "0198f0a0-0000-7000-8000-00000000ca04"
        val aOwned = capsule(capsuleId = idA).copy(ownerUserId = OWNER)
        capsuleDao.upsertAllForOwner(OWNER, listOf(aOwned))

        val mixedBatch = listOf(
            capsule(capsuleId = idB),
            capsule(capsuleId = idA).copy(ownerUserId = ownerB),
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { capsuleDao.upsertAllForOwner(OWNER, mixedBatch) }
        }

        assertNull(capsuleDao.getByCapsuleIdAndOwner(idB, OWNER))
        assertEquals(1, countRows("incoming_capsule"))
    }

    @Test
    fun publicIncomingDaoSurfaceHasNoGlobalClearOrRawWritePrimitives() {
        val capsulePublic = IncomingCapsuleDao::class.java.methods
        val envelopePublic = IncomingEnvelopeDao::class.java.methods

        assertPublicSurfaceIsOwnerBound(
            capsulePublic,
            upsertName = "upsertAllForOwner",
            entityParameterType = List::class.java,
        )
        assertPublicSurfaceIsOwnerBound(
            envelopePublic,
            upsertName = "upsertForOwner",
            entityParameterType = IncomingEnvelopeEntity::class.java,
        )
    }

    private fun assertPublicSurfaceIsOwnerBound(
        methods: Array<java.lang.reflect.Method>,
        upsertName: String,
        entityParameterType: Class<*>,
    ) {
        assertTrue(methods.none { it.name == "clear" })
        assertTrue(methods.any { it.name == "clearForOwner" })
        assertTrue(
            methods.none {
                it.name in setOf(
                    "findOwnerOf",
                    "findOwnerOfRaw",
                    "insertIgnoring",
                    "updateReplayFieldsForOwner",
                    "updateReplayFieldsRaw",
                )
            },
        )
        val upserts = methods.filter { it.name == upsertName }
        assertEquals(1, upserts.size)
        assertEquals(String::class.java, upserts.single().parameterTypes.first())
        assertTrue(entityParameterType in upserts.single().parameterTypes)
        assertTrue(Modifier.isPublic(upserts.single().modifiers))
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
