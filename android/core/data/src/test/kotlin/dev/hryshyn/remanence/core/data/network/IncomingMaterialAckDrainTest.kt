package dev.hryshyn.remanence.core.data.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.core.data.db.IncomingCapsuleDao
import dev.hryshyn.remanence.core.data.db.IncomingCapsuleEntity
import dev.hryshyn.remanence.core.data.db.IncomingMaterialAckResult
import dev.hryshyn.remanence.core.data.db.IncomingSyncSession
import dev.hryshyn.remanence.core.data.db.MaterialAckState
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.UserId
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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
class IncomingMaterialAckDrainTest {

    private val owner = user("00000000-0000-4000-8000-000000000001")
    private val otherOwner = user("00000000-0000-4000-8000-000000000002")
    private val accessToken = "drain-access-token"
    private lateinit var database: RemanenceLocalDatabase
    private lateinit var dao: IncomingCapsuleDao
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.incomingCapsuleDao()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        if (database.isOpen) database.close()
        server.close()
    }

    @Test
    fun deterministicBoundedPageAdvancesInOrderAndReportsFullPage() = runTest {
        val first = capsule(1)
        val tied = capsule(2)
        val later = capsule(3)
        val alreadyAcked = capsule(4)
        val alreadyTerminal = capsule(5)
        seed(first, 20, LocalMaterialState.MATERIAL_CACHED)
        seed(tied, 20, LocalMaterialState.FINGERPRINT_ACCEPTED)
        seed(later, 30, LocalMaterialState.MATERIAL_CACHED)
        seed(alreadyAcked, 0, LocalMaterialState.MATERIAL_CACHED)
        seed(alreadyTerminal, 1, LocalMaterialState.FINGERPRINT_ACCEPTED)
        assertEquals(IncomingMaterialAckResult.Marked, dao.markMaterialAckedForOwner(owner, alreadyAcked))
        assertEquals(IncomingMaterialAckResult.Marked, dao.markMaterialTerminalForOwner(owner, alreadyTerminal))
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(MockResponse.Builder().code(204).build())

        val firstResult = drain().run(limit = 2)

        assertEquals(
            IncomingMaterialAckDrainResult.Completed(2, 2, pageMayHaveMore = true),
            firstResult,
        )
        assertEquals(
            "/v1/capsules/${first.toRestString()}/material-synced",
            server.takeRequest().url.encodedPath,
        )
        assertEquals(
            "/v1/capsules/${tied.toRestString()}/material-synced",
            server.takeRequest().url.encodedPath,
        )
        assertEquals(MaterialAckState.ACKED, row(first).materialAckState)
        assertEquals(MaterialAckState.ACKED, row(tied).materialAckState)
        assertEquals(MaterialAckState.PENDING, row(later).materialAckState)

        val secondResult = drain().run(limit = 2)

        assertEquals(
            IncomingMaterialAckDrainResult.Completed(1, 1, pageMayHaveMore = false),
            secondResult,
        )
        assertEquals(
            "/v1/capsules/${later.toRestString()}/material-synced",
            server.takeRequest().url.encodedPath,
        )
        assertEquals(MaterialAckState.ACKED, row(later).materialAckState)
        assertEquals(MaterialAckState.ACKED, row(alreadyAcked).materialAckState)
        assertEquals(MaterialAckState.TERMINAL, row(alreadyTerminal).materialAckState)
    }

    @Test
    fun invalidLimitIsRejectedBeforeSessionSelectorOrNetwork() = runTest {
        var sessionCalls = 0
        val session: suspend () -> IncomingSyncSession? = {
            sessionCalls++
            IncomingSyncSession(owner, accessToken)
        }
        val invalid = listOf(0, -1, IncomingCapsuleDao.MATERIAL_ACK_HARD_MAX_PAGE_SIZE + 1)

        invalid.forEach { limit ->
            assertEquals(IncomingMaterialAckDrainResult.InvalidRequest, drain(session).run(limit))
        }

        assertEquals(0, sessionCalls)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun terminalNonAuthResponseMarksTerminalAndContinuesLaterCandidates() = runTest {
        val terminal = capsule(10)
        val later = capsule(11)
        seed(terminal, 10, LocalMaterialState.MATERIAL_CACHED)
        seed(later, 11, LocalMaterialState.FINGERPRINT_ACCEPTED)
        server.enqueue(problem(422, "VALIDATION_FAILED", retryable = false))
        server.enqueue(MockResponse.Builder().code(204).build())

        val result = drain().run(limit = 2)

        assertEquals(IncomingMaterialAckDrainResult.Completed(2, 2, true), result)
        assertEquals(MaterialAckState.TERMINAL, row(terminal).materialAckState)
        assertEquals(MaterialAckState.ACKED, row(later).materialAckState)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun capsuleNotFoundIsProvenTerminalAndContinuesLaterCandidates() = runTest {
        val terminal = capsule(12)
        val later = capsule(13)
        seed(terminal, 12, LocalMaterialState.MATERIAL_CACHED)
        seed(later, 13, LocalMaterialState.MATERIAL_CACHED)
        server.enqueue(problem(404, "CAPSULE_NOT_FOUND", retryable = false))
        server.enqueue(MockResponse.Builder().code(204).build())

        val result = drain().run(limit = 2)

        assertEquals(IncomingMaterialAckDrainResult.Completed(2, 2, true), result)
        assertEquals(MaterialAckState.TERMINAL, row(terminal).materialAckState)
        assertEquals(MaterialAckState.ACKED, row(later).materialAckState)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun malformedFourxxInvalidResponseRemainsPendingAndStopsBeforeLaterPost() = runTest {
        val first = capsule(14)
        val later = capsule(15)
        seed(first, 14, LocalMaterialState.MATERIAL_CACHED)
        seed(later, 15, LocalMaterialState.MATERIAL_CACHED)
        server.enqueue(MockResponse.Builder().code(404).body("not a problem response").build())

        val result = drain().run(limit = 2)

        assertEquals(
            IncomingMaterialAckDrainResult.Retryable(
                IncomingMaterialAckDrainRetryReason.MATERIAL_SYNC_RETRYABLE,
            ),
            result,
        )
        assertEquals(MaterialAckState.PENDING, row(first).materialAckState)
        assertEquals(MaterialAckState.PENDING, row(later).materialAckState)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun unknownHttpFailureRemainsPendingAndStopsBeforeLaterPost() = runTest {
        val first = capsule(16)
        val later = capsule(17)
        seed(first, 16, LocalMaterialState.MATERIAL_CACHED)
        seed(later, 17, LocalMaterialState.MATERIAL_CACHED)
        server.enqueue(problem(418, "UNKNOWN", retryable = false))

        val result = drain().run(limit = 2)

        assertEquals(
            IncomingMaterialAckDrainResult.Retryable(
                IncomingMaterialAckDrainRetryReason.MATERIAL_SYNC_RETRYABLE,
            ),
            result,
        )
        assertEquals(MaterialAckState.PENDING, row(first).materialAckState)
        assertEquals(MaterialAckState.PENDING, row(later).materialAckState)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun nonretryableInternalErrorRemainsPendingAndStopsBeforeLaterPost() = runTest {
        val first = capsule(18)
        val later = capsule(19)
        seed(first, 18, LocalMaterialState.MATERIAL_CACHED)
        seed(later, 19, LocalMaterialState.MATERIAL_CACHED)
        server.enqueue(problem(500, "INTERNAL_ERROR", retryable = false))

        val result = drain().run(limit = 2)

        assertEquals(
            IncomingMaterialAckDrainResult.Retryable(
                IncomingMaterialAckDrainRetryReason.MATERIAL_SYNC_RETRYABLE,
            ),
            result,
        )
        assertEquals(MaterialAckState.PENDING, row(first).materialAckState)
        assertEquals(MaterialAckState.PENDING, row(later).materialAckState)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun retryableRemoteFailureStopsWithoutLocalMutation() = runTest {
        val first = capsule(20)
        val later = capsule(21)
        seed(first, 20, LocalMaterialState.MATERIAL_CACHED)
        seed(later, 21, LocalMaterialState.MATERIAL_CACHED)
        server.enqueue(problem(503, "INTERNAL_ERROR", retryable = true))

        val result = drain().run(limit = 2)

        assertEquals(
            IncomingMaterialAckDrainResult.Retryable(
                IncomingMaterialAckDrainRetryReason.MATERIAL_SYNC_RETRYABLE,
            ),
            result,
        )
        assertEquals(MaterialAckState.PENDING, row(first).materialAckState)
        assertEquals(MaterialAckState.PENDING, row(later).materialAckState)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun authInvalidStopsWithoutCasOrLaterPost() = runTest {
        val first = capsule(30)
        val later = capsule(31)
        seed(first, 30, LocalMaterialState.MATERIAL_CACHED)
        seed(later, 31, LocalMaterialState.MATERIAL_CACHED)
        server.enqueue(problem(401, "AUTH_INVALID", retryable = false))

        val result = drain().run(limit = 2)

        assertEquals(IncomingMaterialAckDrainResult.AccountStopped, result)
        assertEquals(MaterialAckState.PENDING, row(first).materialAckState)
        assertEquals(MaterialAckState.PENDING, row(later).materialAckState)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun accountSwitchBeforePostStopsWithoutNetworkOrMutation() = runTest {
        val first = capsule(40)
        seed(first, 40, LocalMaterialState.MATERIAL_CACHED)
        var checks = 0
        val session: suspend () -> IncomingSyncSession? = {
            checks++
            if (checks == 2) IncomingSyncSession(otherOwner, "other-token")
            else IncomingSyncSession(owner, accessToken)
        }

        val result = drain(session).run(limit = 1)

        assertEquals(IncomingMaterialAckDrainResult.AccountStopped, result)
        assertEquals(MaterialAckState.PENDING, row(first).materialAckState)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun accountSwitchBeforeCasLeavesSuccessfulPostReplayable() = runTest {
        val first = capsule(50)
        seed(first, 50, LocalMaterialState.MATERIAL_CACHED)
        server.enqueue(MockResponse.Builder().code(204).build())
        var checks = 0
        val session: suspend () -> IncomingSyncSession? = {
            checks++
            if (checks == 3) IncomingSyncSession(otherOwner, "other-token")
            else IncomingSyncSession(owner, accessToken)
        }

        val result = drain(session).run(limit = 1)

        assertEquals(IncomingMaterialAckDrainResult.AccountStopped, result)
        assertEquals(MaterialAckState.PENDING, row(first).materialAckState)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cancellationBeforeCasIsExactAndRestartRepostsAndConverges() = runTest {
        val first = capsule(60)
        seed(first, 60, LocalMaterialState.MATERIAL_CACHED)
        val expected = CancellationException("stop after remote success")
        server.enqueue(MockResponse.Builder().code(204).build())
        var checks = 0
        val cancellingSession: suspend () -> IncomingSyncSession? = {
            checks++
            if (checks == 3) throw expected
            IncomingSyncSession(owner, accessToken)
        }

        try {
            drain(cancellingSession).run(limit = 1)
            assertTrue("cancellation must be rethrown", false)
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
        assertEquals(MaterialAckState.PENDING, row(first).materialAckState)
        assertEquals(1, server.requestCount)

        server.enqueue(MockResponse.Builder().code(204).build())
        val replay = drain().run(limit = 1)

        assertEquals(IncomingMaterialAckDrainResult.Completed(1, 1, true), replay)
        assertEquals(MaterialAckState.ACKED, row(first).materialAckState)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun foreignOwnerReplacementAfterPostIsNotMutatedAndLaterCandidateRuns() = runTest {
        val replaced = capsule(70)
        val later = capsule(71)
        seed(replaced, 70, LocalMaterialState.MATERIAL_CACHED)
        seed(later, 71, LocalMaterialState.MATERIAL_CACHED)
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(MockResponse.Builder().code(204).build())
        var checks = 0
        val session: suspend () -> IncomingSyncSession? = {
            checks++
            if (checks == 3) replaceOwner(replaced, otherOwner)
            IncomingSyncSession(owner, accessToken)
        }

        val result = drain(session).run(limit = 2)

        assertEquals(IncomingMaterialAckDrainResult.Completed(1, 2, true), result)
        assertEquals(MaterialAckState.PENDING, rowAsOwner(replaced, otherOwner).materialAckState)
        assertEquals(MaterialAckState.ACKED, row(later).materialAckState)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun casStateLossIsReconciledWithoutBlockingLaterCandidate() = runTest {
        val changed = capsule(80)
        val later = capsule(81)
        seed(changed, 80, LocalMaterialState.MATERIAL_CACHED)
        seed(later, 81, LocalMaterialState.MATERIAL_CACHED)
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(MockResponse.Builder().code(204).build())
        var checks = 0
        val session: suspend () -> IncomingSyncSession? = {
            checks++
            if (checks == 3) setAckState(changed, MaterialAckState.TERMINAL)
            IncomingSyncSession(owner, accessToken)
        }

        val result = drain(session).run(limit = 2)

        assertEquals(IncomingMaterialAckDrainResult.Completed(1, 2, true), result)
        assertEquals(MaterialAckState.TERMINAL, row(changed).materialAckState)
        assertEquals(MaterialAckState.ACKED, row(later).materialAckState)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun localCasFailureStopsAsRetryableAndResultsAreRedacted() = runTest {
        val first = capsule(100)
        seed(first, 100, LocalMaterialState.MATERIAL_CACHED)
        server.enqueue(MockResponse.Builder().code(204).build())
        var checks = 0
        val session: suspend () -> IncomingSyncSession? = {
            checks++
            if (checks == 3) database.openHelper.writableDatabase.execSQL("DROP TABLE incoming_capsule")
            IncomingSyncSession(owner, accessToken)
        }

        val result = drain(session).run(limit = 1)

        assertEquals(
            IncomingMaterialAckDrainResult.Retryable(
                IncomingMaterialAckDrainRetryReason.LOCAL_PROGRESS_UNAVAILABLE,
            ),
            result,
        )
        assertFalse(result.toString().contains(accessToken))
        assertFalse(result.toString().contains(first.toRestString()))
        assertFalse(result.toString().contains("private detail"))
    }

    private fun drain(
        session: suspend () -> IncomingSyncSession? = { IncomingSyncSession(owner, accessToken) },
        repository: RecipientMaterialSyncedRepository = repository(),
    ) = IncomingMaterialAckDrain(
        incomingCapsuleDao = dao,
        currentSession = session,
        recipientMaterialSyncedRepository = repository,
    )

    private fun repository(): RecipientMaterialSyncedRepository =
        RecipientMaterialSyncedRepository(
            HttpClientFactory.create(),
            ApiBaseUrl.parse(server.url("/").toString()),
        )

    private suspend fun seed(
        capsuleId: CapsuleId,
        readyAt: Long,
        state: LocalMaterialState,
    ) {
        dao.upsertAllForOwner(
            owner.toRestString(),
            listOf(entity(capsuleId, readyAt, state = LocalMaterialState.DISCOVERED)),
        )
        val chain = listOf(
            LocalMaterialState.INDEX_CACHED,
            LocalMaterialState.MATERIAL_CACHED,
            LocalMaterialState.FINGERPRINT_ACCEPTED,
        )
        for (next in chain.takeWhile { it != state } + if (state in chain) listOf(state) else emptyList()) {
            dao.transitionMaterialStateForOwner(
                ownerUserId = owner.toRestString(),
                capsuleId = capsuleId.toRestString(),
                requestedTarget = next,
            )
        }
    }

    private fun entity(
        capsuleId: CapsuleId,
        readyAt: Long,
        state: LocalMaterialState,
    ) = IncomingCapsuleEntity(
        capsuleId = capsuleId.toRestString(),
        ownerUserId = owner.toRestString(),
        senderUserId = "00000000-0000-4000-8000-000000000010",
        recipientUserId = owner.toRestString(),
        senderSigningKeyBundleId = "00000000-0000-4000-8000-000000000012",
        recipientEncryptionKeyBundleId = "00000000-0000-4000-8000-000000000013",
        protocolVersion = 1,
        serverStatus = "READY",
        readyAtEpochMs = readyAt,
        signedStatementBytes = byteArrayOf(1, 2, 3),
        materialState = state,
    )

    private suspend fun row(capsuleId: CapsuleId) =
        database.incomingCapsuleDao().getByCapsuleIdAndOwner(
            capsuleId.toRestString(),
            owner.toRestString(),
        )!!

    private suspend fun rowAsOwner(capsuleId: CapsuleId, rowOwner: UserId) =
        database.incomingCapsuleDao().getByCapsuleIdAndOwner(
            capsuleId.toRestString(),
            rowOwner.toRestString(),
        )!!

    private fun replaceOwner(capsuleId: CapsuleId, newOwner: UserId) {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE incoming_capsule SET owner_user_id = ? WHERE capsule_id = ?",
            arrayOf(newOwner.toRestString(), capsuleId.toRestString()),
        )
    }

    private fun setAckState(capsuleId: CapsuleId, state: MaterialAckState) {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE incoming_capsule SET material_ack_state = ? WHERE capsule_id = ? AND owner_user_id = ?",
            arrayOf(state.name, capsuleId.toRestString(), owner.toRestString()),
        )
    }

    private fun problem(
        status: Int,
        code: String,
        retryable: Boolean,
    ) = MockResponse.Builder()
        .code(status)
        .setHeader("Content-Type", "application/problem+json")
        .body(
            """
            {"type":"https://remanence.invalid/problems/$code","title":"safe","status":$status,"code":"$code","detail":"private detail","request_id":"00000000-0000-4000-8000-000000000099","retryable":$retryable}
            """.trimIndent(),
        )
        .build()

    private fun capsule(number: Int) = CapsuleId(
        UUID.fromString("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"),
    )

    private fun user(raw: String) = UserId(UUID.fromString(raw))
}
