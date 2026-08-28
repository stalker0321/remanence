package dev.hryshyn.remanence.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleEntity
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleState
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapsuleUploadResumerTest {

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

    @Test
    fun resumeEnqueuesEligibleIdsInDaoOrderOnEveryInvocation() = runBlocking {
        val expected = listOf(CAPSULE_A, CAPSULE_B, CAPSULE_C, CAPSULE_D, CAPSULE_E, CAPSULE_F, CAPSULE_G)
        seed(CAPSULE_G, OutboxCapsuleState.TERMINAL_FAILURE, retryPath = "retry/g")
        seed(CAPSULE_C, OutboxCapsuleState.FINALIZING)
        seed(CAPSULE_F, OutboxCapsuleState.PUBLISHED, retryPath = "retry/f")
        seed(CAPSULE_A, OutboxCapsuleState.ENCRYPTED)
        seed(CAPSULE_E, OutboxCapsuleState.RETRYABLE_FAILURE, lastErrorCode = "NETWORK")
        seed(CAPSULE_D, OutboxCapsuleState.RETRYABLE_FAILURE)
        seed(CAPSULE_B, OutboxCapsuleState.UPLOADING)
        val enqueued = mutableListOf<String>()
        val resumer = CapsuleUploadResumer(
            capsuleDao = database.outboxCapsuleDao(),
            currentAccountUserId = { OWNER },
            enqueue = { owner, capsule -> enqueued += "${owner.toRestString()}:${capsule.toRestString()}" },
        )

        val first = resumer.resume(OWNER_TYPED)
        val second = resumer.resume(OWNER_TYPED)

        assertEquals(CapsuleUploadResumeStatus.COMPLETED, first.status)
        assertEquals(expected.size, first.discoveredCount)
        assertEquals(expected.size, first.enqueuedCount)
        assertEquals(first, second)
        assertEquals(
            expected.map { "$OWNER:$it" } + expected.map { "$OWNER:$it" },
            enqueued,
        )
    }

    @Test
    fun accountSwitchStopsBeforeTheNextEnqueue() = runBlocking {
        seed(CAPSULE_A, OutboxCapsuleState.ENCRYPTED)
        seed(CAPSULE_B, OutboxCapsuleState.UPLOADING)
        seed(CAPSULE_C, OutboxCapsuleState.FINALIZING)
        var accountChecks = 0
        val enqueued = mutableListOf<CapsuleId>()
        val resumer = CapsuleUploadResumer(
            capsuleDao = database.outboxCapsuleDao(),
            currentAccountUserId = {
                accountChecks += 1
                if (accountChecks < 3) OWNER else OTHER_OWNER
            },
            enqueue = { _, capsule -> enqueued += capsule },
        )

        val result = resumer.resume(OWNER_TYPED)

        assertEquals(CapsuleUploadResumeStatus.ACCOUNT_MISMATCH, result.status)
        assertEquals(3, result.discoveredCount)
        assertEquals(1, result.enqueuedCount)
        assertEquals(listOf(CAPSULE_TYPED_A), enqueued)
    }

    @Test
    fun malformedCapsuleIdIsSkippedFailClosedWithoutCrossingEnqueueBoundary() = runBlocking {
        seed("00000000-0000-0000-0000-not-a-capsule", OutboxCapsuleState.ENCRYPTED)
        seed(CAPSULE_A, OutboxCapsuleState.ENCRYPTED)
        val enqueued = mutableListOf<CapsuleId>()
        val resumer = CapsuleUploadResumer(
            capsuleDao = database.outboxCapsuleDao(),
            currentAccountUserId = { OWNER },
            enqueue = { _, capsule -> enqueued += capsule },
        )

        val result = resumer.resume(OWNER_TYPED)

        assertEquals(CapsuleUploadResumeStatus.INVALID_CANDIDATE, result.status)
        assertEquals(2, result.discoveredCount)
        assertEquals(1, result.invalidCount)
        assertEquals(1, result.enqueuedCount)
        assertEquals(listOf(CAPSULE_TYPED_A), enqueued)
    }

    @Test
    fun queryFailureReturnsNonSensitiveOperationalFailure() = runBlocking {
        val resumer = CapsuleUploadResumer(
            capsuleDao = FailingCapsuleDao(),
            currentAccountUserId = { OWNER },
            enqueue = { _, _ -> error("enqueue must not be reached") },
        )

        val result = resumer.resume(OWNER_TYPED)

        assertEquals(CapsuleUploadResumeStatus.OPERATIONAL_FAILURE, result.status)
        assertEquals(0, result.discoveredCount)
        assertEquals(0, result.enqueuedCount)
    }

    @Test
    fun enqueueFailureReturnsNonSensitiveOperationalFailure() = runBlocking {
        seed(CAPSULE_A, OutboxCapsuleState.ENCRYPTED)
        val resumer = CapsuleUploadResumer(
            capsuleDao = database.outboxCapsuleDao(),
            currentAccountUserId = { OWNER },
            enqueue = { _, _ -> error("enqueue unavailable") },
        )

        val result = resumer.resume(OWNER_TYPED)

        assertEquals(CapsuleUploadResumeStatus.OPERATIONAL_FAILURE, result.status)
        assertEquals(1, result.discoveredCount)
        assertEquals(0, result.enqueuedCount)
    }

    @Test
    fun cancellationFromEnqueuePropagates() = runBlocking {
        seed(CAPSULE_A, OutboxCapsuleState.ENCRYPTED)
        val resumer = CapsuleUploadResumer(
            capsuleDao = database.outboxCapsuleDao(),
            currentAccountUserId = { OWNER },
            enqueue = { _, _ -> throw CancellationException("cancelled") },
        )

        var propagated = false
        try {
            resumer.resume(OWNER_TYPED)
        } catch (_: CancellationException) {
            propagated = true
        }

        assertTrue(propagated)
    }

    private suspend fun seed(
        capsuleId: String,
        state: OutboxCapsuleState,
        lastErrorCode: String? = null,
        retryPath: String? = null,
        owner: String = OWNER,
    ) {
        database.outboxCapsuleDao().insertOrAbort(
            owner,
            OutboxCapsuleEntity(
                capsuleId = capsuleId,
                idempotencyKey = "idem-$owner-$capsuleId",
                ownerUserId = owner,
                senderUserId = owner,
                recipientUserId = OTHER_OWNER,
                senderKeyBundleId = null,
                recipientKeyBundleId = "0198f0a0-0000-7000-8000-00000000a532",
                senderSigningPublicKeysetB64 = null,
                state = state,
                recognitionManifestPath = null,
                contentManifestPath = null,
                envelopePath = null,
                publishStatementPath = null,
                publishStatementSignaturePath = null,
                senderRetryKeysetPath = retryPath,
                lastErrorCode = lastErrorCode,
            ),
        )
    }

    private class FailingCapsuleDao : dev.hryshyn.remanence.core.data.db.OutboxCapsuleDao() {
        override suspend fun clearForOwner(ownerUserId: String) = Unit

        override suspend fun getByCapsuleIdAndOwner(
            capsuleId: String,
            ownerUserId: String,
        ): OutboxCapsuleEntity? = null

        override suspend fun getCapsuleIdsNeedingUploadForOwner(ownerUserId: String): List<String> {
            throw IllegalStateException("query unavailable")
        }

        override suspend fun markEncryptedForOwner(capsuleId: String, ownerUserId: String) = 0

        override suspend fun beginUploadForOwner(capsuleId: String, ownerUserId: String) = 0

        override suspend fun applyDraftRecipientKeyRewrapForOwner(
            capsuleId: String,
            ownerUserId: String,
            recipientUserId: String,
            expectedRecipientKeyBundleId: String,
            newRecipientKeyBundleId: String,
            newEnvelopePath: String,
            newPublishStatementPath: String,
            newPublishStatementSignaturePath: String,
        ) = 0

        override suspend fun applyFinalizeRecipientKeyRewrapForOwner(
            capsuleId: String,
            ownerUserId: String,
            recipientUserId: String,
            expectedRecipientKeyBundleId: String,
            newRecipientKeyBundleId: String,
            newEnvelopePath: String,
            newPublishStatementPath: String,
            newPublishStatementSignaturePath: String,
        ) = 0

        override suspend fun beginFinalizeForOwner(capsuleId: String, ownerUserId: String) = 0

        override suspend fun markPublishedForOwner(capsuleId: String, ownerUserId: String) = 0

        override suspend fun markFinalizeRetryableForOwner(
            capsuleId: String,
            ownerUserId: String,
            errorCode: String?,
        ) = 0

        override suspend fun markRetryableFailureForOwner(
            capsuleId: String,
            ownerUserId: String,
            errorCode: String?,
        ) = 0

        override suspend fun markTerminalFailureForOwner(
            capsuleId: String,
            ownerUserId: String,
            errorCode: String?,
        ) = 0

        override suspend fun clearSenderRetryKeysetPath(
            capsuleId: String,
            ownerUserId: String,
            expectedPath: String?,
        ) = 0

        override suspend fun insertStrict(capsule: OutboxCapsuleEntity) = Unit

        override suspend fun findOwnersOfImmutableIds(
            capsuleId: String,
            idempotencyKey: String,
        ): List<String> = emptyList()
    }

    private companion object {
        const val OWNER = "0198f0a0-0000-7000-8000-00000000a501"
        const val OTHER_OWNER = "0198f0a0-0000-7000-8000-00000000a502"
        const val CAPSULE_A = "0198f0a0-0000-7000-8000-00000000a511"
        const val CAPSULE_B = "0198f0a0-0000-7000-8000-00000000a512"
        const val CAPSULE_C = "0198f0a0-0000-7000-8000-00000000a513"
        const val CAPSULE_D = "0198f0a0-0000-7000-8000-00000000a514"
        const val CAPSULE_E = "0198f0a0-0000-7000-8000-00000000a515"
        const val CAPSULE_F = "0198f0a0-0000-7000-8000-00000000a516"
        const val CAPSULE_G = "0198f0a0-0000-7000-8000-00000000a517"

        val OWNER_TYPED = UserId.parseRest(OWNER)
        val CAPSULE_TYPED_A = CapsuleId.parseRest(CAPSULE_A)
    }
}
