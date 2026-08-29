package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.UserId
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingAcceptanceCandidateSelectorTest {
    private val ownerA = user("00000000-0000-4000-8000-000000000001")
    private val ownerB = user("00000000-0000-4000-8000-000000000002")
    private lateinit var database: RemanenceLocalDatabase
    private lateinit var selector: IncomingAcceptanceCandidateSelector

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        selector = IncomingAcceptanceCandidateSelector(database.incomingCapsuleDao(), maxPageSize = 3)
    }

    @After
    fun tearDown() {
        if (database.isOpen) database.close()
    }

    @Test
    fun exactOwnerIsolationIncludesCollisionRefusalAndLegacyInvisibility() = runBlocking {
        insert(ownerA, capsule(1), 10)
        insert(ownerB, capsule(2), 10)
        insertLegacy(capsule(3), 5)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { insert(ownerB, capsule(1), 10) }
        }

        assertEquals(listOf(capsule(1)), page(ownerA).map { it.capsuleId })
        assertEquals(listOf(capsule(2)), page(ownerB).map { it.capsuleId })
    }

    @Test
    fun filtersCanonicalStatesAndOrdersTimestampThenCapsuleId() = runBlocking {
        insert(ownerA, capsule(4), 20)
        insert(ownerA, capsule(2), 10)
        insert(ownerA, capsule(1), 10)
        insert(ownerA, capsule(3), 5, serverStatus = "PENDING")
        insertRaw(
            database.openHelper.writableDatabase,
            entity(ownerA.toRestString(), capsule(5), 1, "READY", LocalMaterialState.INDEX_CACHED),
        )

        assertEquals(
            listOf(capsule(1), capsule(2), capsule(4)),
            page(ownerA).map { it.capsuleId },
        )
        assertEquals(listOf(10L, 10L, 20L), page(ownerA).map { it.readyAtEpochMs })
    }

    @Test
    fun keysetSecondPageNeitherDuplicatesNorSkipsEqualTimestampTies() = runBlocking {
        (1..5).forEach { insert(ownerA, capsule(it), if (it < 4) 10 else 20) }

        val first = page(ownerA, limit = 2)
        val last = first.last()
        val second = page(
            ownerA,
            after = IncomingAcceptanceCandidateKey(last.readyAtEpochMs, last.capsuleId),
            limit = 3,
        )

        assertEquals((1..5).map(::capsule), (first + second).map { it.capsuleId })
        assertEquals(5, (first + second).map { it.capsuleId }.distinct().size)
    }

    @Test
    fun strictLimitsFailBeforeClosedDao() = runBlocking {
        database.close()

        for (invalid in listOf(-1, 0, 4)) {
            assertEquals(
                IncomingAcceptanceCandidateSelection.InvalidRequest,
                selector.select(ownerA, limit = invalid),
            )
        }
    }

    @Test
    fun emptyPageIsSuccessful() = runBlocking {
        assertEquals(emptyList<IncomingAcceptanceCandidate>(), page(ownerA))
    }

    @Test
    fun databaseFailureIsRetryableUnavailable() = runBlocking {
        database.openHelper.writableDatabase.execSQL("DROP TABLE incoming_capsule")
        assertEquals(
            IncomingAcceptanceCandidateSelection.Unavailable,
            selector.select(ownerA, limit = 1),
        )
    }

    @Test
    fun malformedDurableIdentityIsUnavailable() {
        insertRaw(
            database.openHelper.writableDatabase,
            entity(ownerA.toRestString(), capsule(1), 10, "READY", LocalMaterialState.DISCOVERED)
                .copy(capsuleId = "not-a-capsule-id"),
        )
        assertEquals(
            IncomingAcceptanceCandidateSelection.Unavailable,
            runBlocking { selector.select(ownerA, limit = 1) },
        )

    }

    @Test
    fun exactQueryCancellationInstancePropagates() {
        val expected = CancellationException("specific cancellation")
        val cancellingSelector = IncomingAcceptanceCandidateSelector(
            query = IncomingAcceptanceCandidateQuery { _, _, _, _ -> throw expected },
            maxPageSize = 3,
        )

        val actual = assertThrows(CancellationException::class.java) {
            runBlocking { cancellingSelector.select(ownerA, limit = 1) }
        }
        assertSame(expected, actual)
    }

    @Test
    fun negativeDurableOrderingKeyMakesWholePageUnavailable() = runBlocking {
        insert(ownerA, capsule(2), 10)
        insertRaw(
            database.openHelper.writableDatabase,
            entity(ownerA.toRestString(), capsule(1), -1, "READY", LocalMaterialState.DISCOVERED),
        )

        assertEquals(
            IncomingAcceptanceCandidateSelection.Unavailable,
            selector.select(ownerA, limit = 3),
        )
    }

    @Test
    fun contractIsMinimalTypedAndRedacted() {
        assertEquals(
            setOf("capsuleId", "readyAtEpochMs"),
            IncomingAcceptanceCandidate::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .map { it.name }
                .toSet(),
        )
        val candidate = IncomingAcceptanceCandidate(capsule(1), 10)
        assertEquals(capsule(1), candidate.capsuleId)
        assertFalse(candidate.toString().contains("signed", ignoreCase = true))
        assertFalse(candidate.toString().contains(capsule(1).toRestString()))
        assertFalse(IncomingAcceptanceCandidateKey(10, capsule(1)).toString().contains(capsule(1).toRestString()))
        assertFalse(IncomingAcceptanceCandidateSelection.Page(listOf(candidate)).toString().contains(capsule(1).toRestString()))
        val row = IncomingAcceptanceCandidateRow(capsule(1).toRestString(), 10)
        assertEquals("IncomingAcceptanceCandidateRow(<redacted>)", row.toString())
        assertFalse(row.toString().contains(capsule(1).toRestString()))
        assertFalse(row.toString().contains("10"))
        assertEquals("IncomingAcceptanceCandidateSelector(<redacted>)", selector.toString())
    }

    @Test
    fun cursorAndConfiguredMaximumValidationEnforceAbsoluteHardBound() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            IncomingAcceptanceCandidateKey(-1, capsule(1))
        }
        for (invalidMaximum in listOf(0, -1, IncomingAcceptanceCandidateSelector.HARD_MAX_PAGE_SIZE + 1, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                IncomingAcceptanceCandidateSelector(database.incomingCapsuleDao(), invalidMaximum)
            }
        }

        val lowerMaximum = IncomingAcceptanceCandidateSelector(database.incomingCapsuleDao(), 2)
        assertTrue(lowerMaximum.select(ownerA, limit = 2) is IncomingAcceptanceCandidateSelection.Page)
        assertEquals(
            IncomingAcceptanceCandidateSelection.InvalidRequest,
            lowerMaximum.select(ownerA, limit = 3),
        )
    }

    private suspend fun page(
        owner: UserId,
        after: IncomingAcceptanceCandidateKey? = null,
        limit: Int = 3,
    ): List<IncomingAcceptanceCandidate> =
        (selector.select(owner, after, limit) as IncomingAcceptanceCandidateSelection.Page).candidates

    private suspend fun insert(
        owner: UserId,
        capsuleId: CapsuleId,
        readyAt: Long,
        serverStatus: String = "READY",
        materialState: LocalMaterialState = LocalMaterialState.DISCOVERED,
    ) {
        database.incomingCapsuleDao().upsertAllForOwner(
            owner.toRestString(),
            listOf(entity(owner.toRestString(), capsuleId, readyAt, serverStatus, materialState)),
        )
    }

    private fun insertLegacy(capsuleId: CapsuleId, readyAt: Long) {
        insertRaw(database.openHelper.writableDatabase, entity("", capsuleId, readyAt, "READY", LocalMaterialState.DISCOVERED))
    }

    private fun insertRaw(db: SupportSQLiteDatabase, row: IncomingCapsuleEntity) {
        db.execSQL(
            "INSERT INTO incoming_capsule " +
                "(capsule_id, owner_user_id, sender_user_id, recipient_user_id, " +
                "sender_signing_key_bundle_id, recipient_encryption_key_bundle_id, protocol_version, " +
                "server_status, ready_at_epoch_ms, signed_statement_bytes, signed_statement_sha256, " +
                "publish_signature_bytes, material_state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                row.capsuleId, row.ownerUserId, row.senderUserId, row.recipientUserId,
                row.senderSigningKeyBundleId, row.recipientEncryptionKeyBundleId, row.protocolVersion,
                row.serverStatus, row.readyAtEpochMs, row.signedStatementBytes,
                row.signedStatementSha256, row.publishSignatureBytes, row.materialState.name,
            ),
        )
    }

    private fun entity(
        owner: String,
        capsuleId: CapsuleId,
        readyAt: Long,
        status: String,
        state: LocalMaterialState,
    ) = IncomingCapsuleEntity(
        capsuleId = capsuleId.toRestString(), ownerUserId = owner,
        senderUserId = "00000000-0000-4000-8000-000000000010",
        recipientUserId = "00000000-0000-4000-8000-000000000011",
        senderSigningKeyBundleId = "00000000-0000-4000-8000-000000000012",
        recipientEncryptionKeyBundleId = "00000000-0000-4000-8000-000000000013",
        protocolVersion = 1, serverStatus = status, readyAtEpochMs = readyAt,
        signedStatementBytes = byteArrayOf(7, 8, 9), materialState = state,
    )

    private fun capsule(number: Int) = CapsuleId(UUID.fromString("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"))
    private fun user(raw: String) = UserId(UUID.fromString(raw))
}
