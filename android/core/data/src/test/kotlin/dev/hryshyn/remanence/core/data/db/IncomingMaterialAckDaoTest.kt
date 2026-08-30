package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.UserId
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingMaterialAckDaoTest {

    private val ownerA = user("00000000-0000-4000-8000-000000000001")
    private val ownerB = user("00000000-0000-4000-8000-000000000002")
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
    fun selectorFiltersOwnerStatusMaterialAndAckStateAndOrdersWithHardBound() = runBlocking {
        insertAdvanced(ownerA, capsule(1), 20, LocalMaterialState.MATERIAL_CACHED)
        insertAdvanced(ownerA, capsule(2), 20, LocalMaterialState.FINGERPRINT_ACCEPTED)
        insertAdvanced(ownerA, capsule(3), 10, LocalMaterialState.MATERIAL_CACHED)
        markRaw(capsule(3), ownerA, MaterialAckState.ACKED)
        insertAdvanced(ownerA, capsule(4), 1, LocalMaterialState.MATERIAL_CACHED, serverStatus = "PENDING")
        insert(ownerA, capsule(5), 2)
        insertAdvanced(ownerB, capsule(6), 0, LocalMaterialState.MATERIAL_CACHED)

        val page = dao.selectMaterialAckCandidatesForOwner(
            ownerUserId = ownerA.toRestString(),
            limit = 2,
        )

        assertEquals(
            listOf(capsule(1), capsule(2)),
            (page as IncomingMaterialAckCandidateSelection.Page).candidates.map { it.capsuleId },
        )
        assertEquals(listOf(20L, 20L), page.candidates.map { it.readyAtEpochMs })

        val empty = dao.selectMaterialAckCandidatesForOwner(ownerA.toRestString(), limit = 3)
        assertTrue(empty is IncomingMaterialAckCandidateSelection.Page)
    }

    @Test
    fun selectorRejectsInvalidLimitBeforeRoomAndMapsDatabaseFailure() = runBlocking {
        database.close()
        for (invalid in listOf(-1, 0, IncomingCapsuleDao.MATERIAL_ACK_HARD_MAX_PAGE_SIZE + 1, Int.MAX_VALUE)) {
            assertEquals(
                IncomingMaterialAckCandidateSelection.InvalidRequest,
                dao.selectMaterialAckCandidatesForOwner(ownerA.toRestString(), invalid),
            )
        }

        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RemanenceLocalDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.incomingCapsuleDao()
        database.openHelper.writableDatabase.execSQL("DROP TABLE incoming_capsule")
        assertEquals(
            IncomingMaterialAckCandidateSelection.Unavailable,
            dao.selectMaterialAckCandidatesForOwner(ownerA.toRestString(), 1),
        )
    }

    @Test
    fun successfulAndTerminalCasAreIdempotentAndRedacted() = runBlocking {
        val acked = capsule(7)
        insertAdvanced(ownerA, acked, 7, LocalMaterialState.MATERIAL_CACHED)
        assertEquals(
            IncomingMaterialAckResult.Marked,
            dao.markMaterialAckedForOwner(ownerA.toRestString(), acked.toRestString()),
        )
        assertEquals(
            IncomingMaterialAckResult.AlreadyRecorded,
            dao.markMaterialAckedForOwner(ownerA.toRestString(), acked.toRestString()),
        )
        assertEquals(MaterialAckState.ACKED, dao.getByCapsuleIdAndOwner(acked.toRestString(), ownerA.toRestString())!!.materialAckState)

        val terminal = capsule(8)
        insertAdvanced(ownerA, terminal, 8, LocalMaterialState.FINGERPRINT_ACCEPTED)
        assertEquals(
            IncomingMaterialAckResult.Marked,
            dao.markMaterialTerminalForOwner(ownerA.toRestString(), terminal.toRestString()),
        )
        assertEquals(
            IncomingMaterialAckResult.AlreadyRecorded,
            dao.markMaterialTerminalForOwner(ownerA.toRestString(), terminal.toRestString()),
        )
        assertFalse(IncomingMaterialAckResult.Marked.toString().contains(acked.toRestString()))
        assertFalse(IncomingMaterialAckResult.AlreadyRecorded.toString().contains(acked.toRestString()))
    }

    @Test
    fun casRereadDoesNotDiscloseForeignAndDistinguishesStateChanges() = runBlocking {
        val eligible = capsule(9)
        insertAdvanced(ownerA, eligible, 9, LocalMaterialState.MATERIAL_CACHED)
        assertEquals(
            IncomingMaterialAckResult.MissingOrForeign,
            dao.markMaterialAckedForOwner(ownerB.toRestString(), eligible.toRestString()),
        )
        assertEquals(
            IncomingMaterialAckResult.MissingOrForeign,
            dao.markMaterialAckedForOwner(ownerA.toRestString(), capsule(99).toRestString()),
        )
        assertEquals(MaterialAckState.PENDING, dao.getByCapsuleIdAndOwner(eligible.toRestString(), ownerA.toRestString())!!.materialAckState)

        val wrongStatus = capsule(10)
        insertAdvanced(ownerA, wrongStatus, 10, LocalMaterialState.MATERIAL_CACHED, serverStatus = "PENDING")
        assertEquals(
            IncomingMaterialAckResult.StateChanged,
            dao.markMaterialAckedForOwner(ownerA.toRestString(), wrongStatus.toRestString()),
        )

        val wrongState = capsule(11)
        insert(ownerA, wrongState, 11)
        assertEquals(
            IncomingMaterialAckResult.StateChanged,
            dao.markMaterialAckedForOwner(ownerA.toRestString(), wrongState.toRestString()),
        )

        val opposite = capsule(12)
        insertAdvanced(ownerA, opposite, 12, LocalMaterialState.MATERIAL_CACHED)
        assertEquals(IncomingMaterialAckResult.Marked, dao.markMaterialAckedForOwner(ownerA.toRestString(), opposite.toRestString()))
        assertEquals(
            IncomingMaterialAckResult.StateChanged,
            dao.markMaterialTerminalForOwner(ownerA.toRestString(), opposite.toRestString()),
        )
    }

    @Test
    fun casDatabaseFailureIsUnavailableWithoutAnUnscopedProbe() = runBlocking {
        database.openHelper.writableDatabase.execSQL("DROP TABLE incoming_capsule")

        assertEquals(
            IncomingMaterialAckResult.Unavailable,
            dao.markMaterialAckedForOwner(ownerA.toRestString(), capsule(16).toRestString()),
        )
    }

    @Test
    fun concurrentCasHasOneWriterAndOneIdempotentReplay() = runBlocking {
        val record = capsule(13)
        insertAdvanced(ownerA, record, 13, LocalMaterialState.MATERIAL_CACHED)

        val results = coroutineScope {
            listOf(
                async { dao.markMaterialAckedForOwner(ownerA.toRestString(), record.toRestString()) },
                async { dao.markMaterialAckedForOwner(ownerA.toRestString(), record.toRestString()) },
            ).awaitAll()
        }

        assertEquals(
            setOf(IncomingMaterialAckResult.Marked, IncomingMaterialAckResult.AlreadyRecorded),
            results.toSet(),
        )
        assertEquals(MaterialAckState.ACKED, dao.getByCapsuleIdAndOwner(record.toRestString(), ownerA.toRestString())!!.materialAckState)
    }

    @Test
    fun incomingPageReplayPreservesAckedAndTerminalLocalProgress() = runBlocking {
        val acked = pageCapsule(14)
        val terminal = pageCapsule(15)
        database.incomingPageDao().commitPage(
            ownerUserId = ownerA.toRestString(),
            expectedCursor = null,
            capsules = listOf(acked, terminal),
            envelopes = listOf(pageEnvelope(acked), pageEnvelope(terminal)),
            blobs = emptyList(),
            nextCursor = "page-cursor",
            committedAtEpochMs = 1_755_000_000_000,
        )
        assertEquals(
            MaterialAckState.PENDING,
            dao.getByCapsuleIdAndOwner(acked.capsuleId, ownerA.toRestString())!!.materialAckState,
        )
        advance(acked, LocalMaterialState.MATERIAL_CACHED)
        advance(terminal, LocalMaterialState.FINGERPRINT_ACCEPTED)
        assertEquals(IncomingMaterialAckResult.Marked, dao.markMaterialAckedForOwner(ownerA.toRestString(), acked.capsuleId))
        assertEquals(IncomingMaterialAckResult.Marked, dao.markMaterialTerminalForOwner(ownerA.toRestString(), terminal.capsuleId))

        database.incomingPageDao().commitPage(
            ownerUserId = ownerA.toRestString(),
            expectedCursor = "page-cursor",
            capsules = listOf(acked.copy(materialState = LocalMaterialState.DISCOVERED, materialAckState = MaterialAckState.PENDING), terminal.copy(materialState = LocalMaterialState.DISCOVERED, materialAckState = MaterialAckState.PENDING)),
            envelopes = listOf(pageEnvelope(acked), pageEnvelope(terminal)),
            blobs = emptyList(),
            nextCursor = "page-cursor",
            committedAtEpochMs = 1_755_000_000_001,
        )

        val storedAcked = dao.getByCapsuleIdAndOwner(acked.capsuleId, ownerA.toRestString())!!
        val storedTerminal = dao.getByCapsuleIdAndOwner(terminal.capsuleId, ownerA.toRestString())!!
        assertEquals(MaterialAckState.ACKED, storedAcked.materialAckState)
        assertEquals(MaterialAckState.TERMINAL, storedTerminal.materialAckState)
        assertEquals(LocalMaterialState.MATERIAL_CACHED, storedAcked.materialState)
        assertEquals(LocalMaterialState.FINGERPRINT_ACCEPTED, storedTerminal.materialState)
    }

    private suspend fun insertAdvanced(
        owner: UserId,
        capsuleId: CapsuleId,
        readyAt: Long,
        targetState: LocalMaterialState,
        serverStatus: String = "READY",
    ) {
        insert(owner, capsuleId, readyAt, serverStatus)
        advance(capsuleId, targetState, owner)
    }

    private suspend fun insert(
        owner: UserId,
        capsuleId: CapsuleId,
        readyAt: Long,
        serverStatus: String = "READY",
    ) {
        dao.upsertAllForOwner(
            owner.toRestString(),
            listOf(
                entity(
                    owner = owner.toRestString(),
                    capsuleId = capsuleId,
                    readyAt = readyAt,
                    serverStatus = serverStatus,
                    state = LocalMaterialState.DISCOVERED,
                ),
            ),
        )
    }

    private suspend fun advance(
        capsule: IncomingCapsuleEntity,
        targetState: LocalMaterialState,
    ) = advance(CapsuleId.parseRest(capsule.capsuleId), targetState, ownerA)

    private suspend fun advance(
        capsuleId: CapsuleId,
        targetState: LocalMaterialState,
        owner: UserId = ownerA,
    ) {
        val chain = listOf(
            LocalMaterialState.INDEX_CACHED,
            LocalMaterialState.MATERIAL_CACHED,
            LocalMaterialState.FINGERPRINT_ACCEPTED,
        )
        for (state in chain.takeWhile { it != targetState } + if (targetState in chain) listOf(targetState) else emptyList()) {
            dao.transitionMaterialStateForOwner(owner.toRestString(), capsuleId.toRestString(), state)
        }
    }

    private fun markRaw(capsuleId: CapsuleId, owner: UserId, state: MaterialAckState) {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE incoming_capsule SET material_ack_state = ? WHERE capsule_id = ? AND owner_user_id = ?",
            arrayOf(state.name, capsuleId.toRestString(), owner.toRestString()),
        )
    }

    private fun pageCapsule(number: Int) = entity(
        owner = ownerA.toRestString(),
        capsuleId = capsule(number),
        readyAt = number.toLong(),
        serverStatus = "READY",
        state = LocalMaterialState.DISCOVERED,
    ).copy(
        signedStatementSha256 = ByteArray(32) { 1 },
        publishSignatureBytes = ByteArray(64) { 2 },
    )

    private fun pageEnvelope(capsule: IncomingCapsuleEntity) = IncomingEnvelopeEntity(
        capsuleId = capsule.capsuleId,
        ownerUserId = ownerA.toRestString(),
        recipientKeyBundleId = capsule.recipientEncryptionKeyBundleId,
        hpkeCiphertext = byteArrayOf(3),
        transportSha256 = ByteArray(32) { 4 },
        receivedAtEpochMs = 1_755_000_000_000,
    )

    private fun entity(
        owner: String,
        capsuleId: CapsuleId,
        readyAt: Long,
        serverStatus: String,
        state: LocalMaterialState,
    ) = IncomingCapsuleEntity(
        capsuleId = capsuleId.toRestString(),
        ownerUserId = owner,
        senderUserId = "00000000-0000-4000-8000-000000000010",
        recipientUserId = "00000000-0000-4000-8000-000000000011",
        senderSigningKeyBundleId = "00000000-0000-4000-8000-000000000012",
        recipientEncryptionKeyBundleId = "00000000-0000-4000-8000-000000000013",
        protocolVersion = 1,
        serverStatus = serverStatus,
        readyAtEpochMs = readyAt,
        signedStatementBytes = byteArrayOf(7, 8, 9),
        materialState = state,
    )

    private fun capsule(number: Int) = CapsuleId(
        UUID.fromString("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"),
    )

    private fun user(raw: String) = UserId(UUID.fromString(raw))
}
