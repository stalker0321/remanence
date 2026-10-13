package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.model.UserId
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRetirementOutboxTest {

    companion object {
        const val OWNER_1 = "11111111-2222-3333-4444-555555555555"
        const val OWNER_2 = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    }

    private fun tempRoot(): java.io.File = Files.createTempDirectory("pending-outbox").toFile()

    private fun leaseFor(owner: String, live: Boolean = true): PendingRetirementSessionGate =
        object : PendingRetirementSessionGate {
            private val lease = SessionRequestLease(
                ownerUserId = UserId.parseRest(owner),
                incarnation = 1L,
            )
            override fun capture() = lease
            override fun accessTokenFor(lease: SessionRequestLease) = "token"
            override fun isLive(lease: SessionRequestLease) = live
        }

    private fun liveGate(): PendingRetirementSessionGate = leaseFor(OWNER_1)

    @Test
    fun enqueuePersistsAndSurvivesRestartWithoutNetwork() = runTest {
        val root = tempRoot()
        val outbox = PendingRetirementOutbox(root, UnconfiguredPendingRetirementPort)
        val item = outbox.enqueue(OWNER_1, "capsule-1")
        assertEquals(OWNER_1, item.ownerUserId)
        assertEquals("capsule-1", item.capsuleId)
        assertTrue(item.operationId.isNotBlank())

        // New instance over the same dir = process restart (shared lock).
        val reopened = PendingRetirementOutbox(root, UnconfiguredPendingRetirementPort)
        assertEquals(1, reopened.pendingCount(OWNER_1))
        // Unconfigured port never succeeds: drain keeps the item.
        assertEquals(0, reopened.drain(OWNER_1, liveGate()))
        assertEquals(1, reopened.pendingCount(OWNER_1))
    }

    @Test
    fun networkFailureKeepsQueueAndDrainRetries() = runTest {
        val root = tempRoot()
        var calls = 0
        val seenOpIds = mutableListOf<String>()
        val flaky = PendingRetirementPort { _, _, op, _, token ->
            calls += 1
            seenOpIds += op
            assertTrue(token.isNotBlank())
            PendingRetireOutcome.RetryableFailure("network")
        }
        val outbox = PendingRetirementOutbox(root, flaky)
        outbox.enqueue(OWNER_1, "capsule-1")
        assertEquals(0, outbox.drain(OWNER_1, liveGate()))
        assertEquals(0, outbox.drain(OWNER_1, liveGate()))
        assertEquals(2, calls)
        assertEquals(1, outbox.pendingCount(OWNER_1))
        assertTrue(seenOpIds.all { it.isNotBlank() })
    }

    @Test
    fun successRemovesOnlyCurrentOwnerItems() = runTest {
        val root = tempRoot()
        val ok = PendingRetirementPort { _, _, _, _, _ -> PendingRetireOutcome.Success }
        val outbox = PendingRetirementOutbox(root, ok)
        outbox.enqueue(OWNER_1, "capsule-1")
        outbox.enqueue(OWNER_2, "capsule-2")
        // Switched owner: owner-2 items never execute under owner-1 drain.
        assertEquals(1, outbox.drain(OWNER_1, liveGate()))
        assertEquals(0, outbox.pendingCount(OWNER_1))
        assertEquals(1, outbox.pendingCount(OWNER_2))
    }

    @Test
    fun deadLeaseAfterAwaitKeepsItemAndAborts() = runTest {
        val root = tempRoot()
        var calls = 0
        val port = PendingRetirementPort { _, _, _, _, _ ->
            calls += 1
            PendingRetireOutcome.Success
        }
        val outbox = PendingRetirementOutbox(root, port)
        outbox.enqueue(OWNER_1, "capsule-1")
        // Even a success response is discarded when the lease died mid-send.
        assertEquals(0, outbox.drain(OWNER_1, leaseFor(OWNER_1, live = false)))
        assertEquals(1, outbox.pendingCount(OWNER_1))
        assertEquals(1, calls)
    }

    @Test
    fun missingLeaseOrTokenKeepsQueue() = runTest {
        val root = tempRoot()
        var calls = 0
        val port = PendingRetirementPort { _, _, _, _, _ ->
            calls += 1
            PendingRetireOutcome.Success
        }
        val noLease = object : PendingRetirementSessionGate {
            override fun capture(): SessionRequestLease? = null
            override fun accessTokenFor(lease: SessionRequestLease) = "token"
            override fun isLive(lease: SessionRequestLease) = true
        }
        val outbox = PendingRetirementOutbox(root, port)
        outbox.enqueue(OWNER_1, "capsule-1")
        assertEquals(0, outbox.drain(OWNER_1, noLease))
        assertEquals(0, calls)
        assertEquals(1, outbox.pendingCount(OWNER_1))
    }

    @Test
    fun leaseBoundToOtherAccountNeverDispatches() = runTest {
        val root = tempRoot()
        var calls = 0
        val port = PendingRetirementPort { _, _, _, _, _ ->
            calls += 1
            PendingRetireOutcome.Success
        }
        val outbox = PendingRetirementOutbox(root, port)
        outbox.enqueue(OWNER_1, "capsule-1")
        // Live lease, but for the wrong account: reject, no dispatch.
        assertEquals(0, outbox.drain(OWNER_1, leaseFor(OWNER_2)))
        assertEquals(0, calls)
        assertEquals(1, outbox.pendingCount(OWNER_1))
    }

    @Test
    fun firstItemInvalidationPreservesFullTailOfThree() = runTest {
        val root = tempRoot()
        var calls = 0
        val port = PendingRetirementPort { _, _, _, _, _ ->
            calls += 1
            PendingRetireOutcome.Success
        }
        // Gate dies on the FIRST item: all three stay queued, zero sends.
        val deadFirst = object : PendingRetirementSessionGate {
            override fun capture() = SessionRequestLease(UserId.parseRest(OWNER_1), 1L)
            override fun accessTokenFor(lease: SessionRequestLease) = "token"
            override fun isLive(lease: SessionRequestLease) = false
        }
        val outbox = PendingRetirementOutbox(root, port)
        outbox.enqueue(OWNER_1, "capsule-1")
        outbox.enqueue(OWNER_1, "capsule-2")
        outbox.enqueue(OWNER_1, "capsule-3")
        assertEquals(0, outbox.drain(OWNER_1, deadFirst))
        assertEquals(3, outbox.pendingCount(OWNER_1))
        assertEquals(1, calls)
        val ids = outbox.readSnapshot(OWNER_1).map { it.capsuleId }.toSet()
        assertEquals(setOf("capsule-1", "capsule-2", "capsule-3"), ids)
    }

    @Test
    fun middleItemInvalidationPreservesHeadOutcomeAndTail() = runTest {
        val root = tempRoot()
        val liveLease = SessionRequestLease(UserId.parseRest(OWNER_1), 1L)
        var sends = 0
        val port = PendingRetirementPort { _, _, _, _, _ ->
            sends += 1
            PendingRetireOutcome.Success
        }
        // First send sees a live lease, second sees a dead one.
        var liveCalls = 0
        val flapping = object : PendingRetirementSessionGate {
            override fun capture() = liveLease
            override fun accessTokenFor(lease: SessionRequestLease) = "token"
            override fun isLive(lease: SessionRequestLease): Boolean {
                liveCalls += 1
                // isLive is checked once per item after its await: first
                // item live, second dead.
                return liveCalls < 2
            }
        }
        val outbox = PendingRetirementOutbox(root, port)
        outbox.enqueue(OWNER_1, "capsule-1")
        outbox.enqueue(OWNER_1, "capsule-2")
        outbox.enqueue(OWNER_1, "capsule-3")
        // Head removed, middle kept (bumped), tail preserved untouched.
        assertEquals(1, outbox.drain(OWNER_1, flapping))
        assertEquals(2, sends)
        val snapshot = outbox.readSnapshot(OWNER_1)
        assertEquals(2, snapshot.size)
        assertEquals(listOf("capsule-2", "capsule-3"), snapshot.map { it.capsuleId })
    }

    @Test
    fun queueCarriesOnlyOpaqueIds() = runTest {
        val root = tempRoot()
        val outbox = PendingRetirementOutbox(root, UnconfiguredPendingRetirementPort)
        outbox.enqueue(OWNER_1, "capsule-1")
        val file = java.io.File(root, "accounts/$OWNER_1/pending-retirement/outbox.json")
        val text = file.readText()
        assertTrue("capsule-1" in text)
        for (forbidden in listOf("plaintext", "secret", "otp", "destination", "private")) {
            assertTrue(forbidden !in text.lowercase())
        }
    }

    private fun corruptQueueFile(root: java.io.File, owner: String) {
        val dir = java.io.File(root, "accounts/$owner/pending-retirement")
        dir.mkdirs()
        java.io.File(dir, "outbox.json").writeText("not-json{{{[corrupt")
    }

    @Test
    fun corruptQueueReportsFaultAndQuarantinesNeverSuccess() = runTest {
        val root = tempRoot()
        val outbox = PendingRetirementOutbox(root, UnconfiguredPendingRetirementPort)
        corruptQueueFile(root, OWNER_1)
        // drainDetailed reports fault (worker rearms via retry)...
        val outcome = outbox.drainDetailed(OWNER_1, liveGate())
        assertEquals(0, outcome.removed)
        assertTrue(outcome.fault)
        // ...the unreadable bytes are preserved aside, never silently dropped.
        val dir = java.io.File(root, "accounts/$OWNER_1/pending-retirement")
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("outbox.corrupt.") })
        assertTrue(dir.listFiles()!!.first { it.name.startsWith("outbox.corrupt.") }.readText().contains("corrupt"))
    }

    @Test
    fun enqueueAfterCorruptionKeepsNewIntent() = runTest {
        val root = tempRoot()
        val outbox = PendingRetirementOutbox(root, UnconfiguredPendingRetirementPort)
        corruptQueueFile(root, OWNER_1)
        // The failed intent is NOT lost: quarantine-then-write keeps it queued.
        val item = outbox.enqueue(OWNER_1, "capsule-1")
        assertEquals("capsule-1", item.capsuleId)
        assertEquals(1, outbox.pendingCount(OWNER_1))
        // The recent quarantine still faults (no silent empty-success) while
        // the healthy new intent stays queued for later sends.
        val outcome = outbox.drainDetailed(OWNER_1, liveGate())
        assertTrue(outcome.fault)
        assertEquals(1, outcome.remaining)
        assertEquals(1, outbox.pendingCount(OWNER_1))
    }

    @Test
    fun agedQuarantineStopsFaulting() = runTest {
        val root = tempRoot()
        val outbox = PendingRetirementOutbox(root, UnconfiguredPendingRetirementPort)
        outbox.enqueue(OWNER_1, "capsule-1")
        corruptQueueFile(root, OWNER_1)
        // Quarantine the corrupt file via a faulting drain first.
        assertTrue(outbox.drainDetailed(OWNER_1, liveGate()).fault)
        // Terminal budget: long after the quarantine, drains stop faulting
        // (forensics remain on disk); network retries were never budgeted.
        val aged = outbox.drainDetailed(
            OWNER_1,
            liveGate(),
            nowMillis = System.currentTimeMillis() + PendingRetirementOutbox.FAULT_TTL_MILLIS + 1,
        )
        assertTrue(!aged.fault)
    }
}
