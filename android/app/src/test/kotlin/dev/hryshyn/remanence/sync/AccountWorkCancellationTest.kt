package dev.hryshyn.remanence.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M2-P05 (architecture.md section 11) Robolectric proof that
 * [AccountWorkCancellation] cancels exactly the work whose tag set
 * contains one canonical account tag, never a global tag, and cannot
 * cancel another account's chains.
 *
 * The test seeds the WorkManager test database with chains for two
 * distinct accounts plus chains tagged only with the global
 * `remanence` tag, runs the cancellation, and inspects the resulting
 * [WorkInfo] state. The "official work-testing artifact" path
 * documented for the M2-P05 unit suite is the only available driver;
 * there is no M2-P05 worker implementation yet, so the test enqueues
 * neutral `NoOpWorker` requests that carry the same tag set the real
 * chains will eventually carry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountWorkCancellationTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var cancellation: AccountWorkCancellation

    private val userA: UserId = UserId(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
    private val userB: UserId = UserId(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
    private val capsuleA: CapsuleId = CapsuleId(UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"))
    private val capsuleB: CapsuleId = CapsuleId(UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"))

    private val accountTagA: String = AccountWorkIdentity.accountTag(userA)
    private val accountTagB: String = AccountWorkIdentity.accountTag(userB)
    private val globalTag: String = "remanence"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.ERROR)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        cancellation = AccountWorkCancellation(workManager)
    }

    @After
    fun tearDown() {
        // The official work-testing helper closes the test database so the
        // next @Before re-initialises a fresh WorkManager instance.
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun cancellingAccountARemovesEveryChainTaggedWithAccountA() {
        val aIncoming = enqueue(AccountWorkIdentity.incomingSync(userA))
        val aOutbox = enqueue(AccountWorkIdentity.outbox(userA, capsuleA))
        val aOutboxOther = enqueue(AccountWorkIdentity.outbox(userA, capsuleB))

        val usedTag = cancellation.cancelForAccount(userA)

        assertEquals(accountTagA, usedTag)
        assertState(listOf(aIncoming, aOutbox, aOutboxOther), WorkInfo.State.CANCELLED)
    }

    @Test
    fun cancellingAccountACannotCancelAccountBChains() {
        val bIncoming = enqueue(AccountWorkIdentity.incomingSync(userB))
        val bOutbox = enqueue(AccountWorkIdentity.outbox(userB, capsuleA))
        val bOutboxOther = enqueue(AccountWorkIdentity.outbox(userB, capsuleB))
        val aIncoming = enqueue(AccountWorkIdentity.incomingSync(userA))

        cancellation.cancelForAccount(userA)

        // A is gone.
        assertState(listOf(aIncoming), WorkInfo.State.CANCELLED)
        // B is untouched, even when A and B share the same capsule UUID.
        assertState(
            listOf(bIncoming, bOutbox, bOutboxOther),
            WorkInfo.State.ENQUEUED,
        )
    }

    @Test
    fun cancellingAccountADoesNotTouchChainsTaggedWithOnlyTheGlobalTag() {
        val globalOnly = enqueue(AccountWorkIdentity(uniqueName = "remanence.global-only", tags = listOf(globalTag)))
        val accountA = enqueue(AccountWorkIdentity.incomingSync(userA))

        cancellation.cancelForAccount(userA)

        assertState(listOf(accountA), WorkInfo.State.CANCELLED)
        assertState(listOf(globalOnly), WorkInfo.State.ENQUEUED)
    }

    @Test
    fun cancellationRequestsNeverUseAGlobalTagAlone() {
        // The boundary MUST take a typed UserId and route through
        // AccountWorkIdentity.accountTag; assert the literal "remanence"
        // global tag is never seen by WorkManager as a cancellation
        // selector. A direct cancelAllWorkByTag("remanence") would, by
        // construction, remove every chain we just queued, including
        // account B's. We instead assert that the account tag is the
        // exact string used and that it is NOT the global tag.
        val direct = cancellation.cancelForAccount(userA)
        assertNotEquals(globalTag, direct)
        assertTrue(
            "the cancel selector must be the canonical account tag, was '$direct'",
            direct.startsWith("remanence.account.") && direct.length > "remanence.account.".length,
        )
    }

    @Test
    fun unknownAccountCancellationIsHarmlessAndDoesNotMutateB() {
        val bIncoming = enqueue(AccountWorkIdentity.incomingSync(userB))
        val ghost: UserId = UserId(UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"))

        // A logout for a never-queued account must not touch B.
        val usedTag = cancellation.cancelForAccount(ghost)
        assertEquals(AccountWorkIdentity.accountTag(ghost), usedTag)
        assertNotEquals(accountTagA, usedTag)
        assertNotEquals(accountTagB, usedTag)
        assertState(listOf(bIncoming), WorkInfo.State.ENQUEUED)
    }

    private fun enqueue(identity: AccountWorkIdentity): UUID {
        // The constraints below are never met in this test (no network is
        // ever marked as available for the test scheduler), so the test
        // scheduler never starts the fixture NoOpWorker; cancellation then
        // visibly transitions each chain from ENQUEUED to CANCELLED inside
        // this test.
        val unsatisfiableConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val builder = OneTimeWorkRequestBuilder<NoOpWorker>()
            .setConstraints(unsatisfiableConstraints)
            .setInitialState(WorkInfo.State.ENQUEUED)
        for (tag in identity.tags) {
            builder.addTag(tag)
        }
        val request = builder.build()
        workManager.enqueue(request)
        return request.id
    }

    private fun assertState(ids: List<UUID>, expected: WorkInfo.State) {
        for (id in ids) {
            val info = workManager.getWorkInfoById(id).get()
            assertEquals(
                "work $id should be in $expected but was ${info?.state}",
                expected,
                info?.state,
            )
        }
    }
}
