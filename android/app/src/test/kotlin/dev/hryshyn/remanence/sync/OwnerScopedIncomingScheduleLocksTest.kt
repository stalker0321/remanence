package dev.hryshyn.remanence.sync

import dev.hryshyn.remanence.core.model.UserId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@kotlinx.coroutines.ExperimentalCoroutinesApi
class OwnerScopedIncomingScheduleLocksTest {

    @Test
    fun sameOwnerSerializesWorkInfoObservationAndEnqueueDecision() = runTest {
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000c601")
        val locks = OwnerScopedIncomingScheduleLocks()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0

        suspend fun criticalSection() {
            locks.withOwner(owner) {
                active++
                maxActive = maxOf(maxActive, active)
                firstEntered.complete(Unit)
                releaseFirst.await()
                active--
            }
        }

        val first = async { criticalSection() }
        firstEntered.await()
        val second = async { criticalSection() }
        runCurrent()
        assertFalse(second.isCompleted)

        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, maxActive)
    }

    @Test
    fun workFinishingAfterSnapshotStillGetsTheKeepRetryRequest() = runTest {
        var activeWork = true
        var enqueueCalls = 0
        val operation = IncomingKeepScheduleOperation(
            observe = {
                assertEquals(true, activeWork)
                // Simulate the active WorkInfo finishing before KEEP enqueue.
                activeWork = false
                ExistingIncomingWorkState.ENQUEUED
            },
            enqueue = {
                enqueueCalls += 1
                activeWork = true
            },
        )

        val outcome = operation.run()

        assertEquals(1, enqueueCalls)
        assertEquals(true, activeWork)
        assertEquals(
            "KEEP accepted (observed enqueued; retry timing unknown)",
            outcome.safeStatus,
        )
    }

    @Test
    fun differentOwnersCanRunConcurrentlyAndEntriesAreReleased() = runTest {
        val ownerA = UserId.parseRest("0198f0a0-0000-7000-8000-00000000c601")
        val ownerB = UserId.parseRest("0198f0a0-0000-7000-8000-00000000c602")
        val locks = OwnerScopedIncomingScheduleLocks()
        val enteredA = CompletableDeferred<Unit>()
        val enteredB = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0

        suspend fun critical(owner: dev.hryshyn.remanence.core.model.UserId) {
            locks.withOwner(owner) {
                active += 1
                maxActive = maxOf(maxActive, active)
                if (owner == ownerA) enteredA.complete(Unit) else enteredB.complete(Unit)
                release.await()
                active -= 1
            }
        }

        val first = async { critical(ownerA) }
        val second = async { critical(ownerB) }
        enteredA.await()
        enteredB.await()
        assertEquals(2, maxActive)
        assertEquals(2, locks.activeOwnerCountForTests())

        release.complete(Unit)
        first.await()
        second.await()
        assertTrue("owner lock entries must be released", locks.activeOwnerCountForTests() == 0)
    }

    @Test
    fun queryFailureOrTimeoutDoesNotSuppressTheKeepEnqueue() = runTest {
        var enqueueCalls = 0
        val outcomes = listOf<Throwable>(
            java.io.IOException("query failed"),
            java.util.concurrent.TimeoutException("query timed out"),
        ).map { failure ->
            IncomingKeepScheduleOperation(
                observe = { throw failure },
                enqueue = { enqueueCalls += 1 },
            ).run()
        }

        assertEquals(2, enqueueCalls)
        assertEquals(
            listOf(IncomingSyncSchedulingOutcome.Queued, IncomingSyncSchedulingOutcome.Queued),
            outcomes,
        )
    }

    @Test
    fun hungOperationAwaitDoesNotHoldTheOwnerLockForTheNextLifecycleOperation() = runTest {
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000c603")
        val locks = OwnerScopedIncomingScheduleLocks()
        val awaitEntered = CompletableDeferred<Unit>()
        val releaseAwait = CompletableDeferred<Unit>()
        val nextEntered = CompletableDeferred<Unit>()
        val firstOperation = IncomingKeepScheduleOperation(
            observe = { null },
            enqueue = {},
            awaitEnqueue = {
                awaitEntered.complete(Unit)
                releaseAwait.await()
            },
        )

        val first = async {
            val prepared = locks.withOwner(owner) { firstOperation.prepare() }
            firstOperation.complete(prepared)
        }
        awaitEntered.await()

        val secondOperation = IncomingKeepScheduleOperation(
            observe = { null },
            enqueue = { nextEntered.complete(Unit) },
        )
        val second = async {
            val prepared = locks.withOwner(owner) { secondOperation.prepare() }
            secondOperation.complete(prepared)
        }
        yield()
        assertTrue("the next owner lifecycle operation must enter", nextEntered.isCompleted)
        assertFalse(first.isCompleted)

        releaseAwait.complete(Unit)
        first.await()
        second.await()
        assertEquals(0, locks.activeOwnerCountForTests())
    }
}
