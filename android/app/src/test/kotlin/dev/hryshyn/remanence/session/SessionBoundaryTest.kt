package dev.hryshyn.remanence.session

import dev.hryshyn.remanence.sync.ExistingIncomingWorkState
import dev.hryshyn.remanence.sync.IncomingKeepScheduleOperation
import dev.hryshyn.remanence.sync.IncomingScheduleLeaseRejected
import dev.hryshyn.remanence.sync.IncomingSyncSchedulingOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SessionBoundaryTest {

    @Test
    fun invalidationBetweenQueryAndEnqueueRejectsTheStaleLease() = runTest {
        val boundary = SessionBoundary()
        val epoch = boundary.currentEpoch()
        val queryReturned = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        var enqueueCalls = 0

        val scheduling = async(Dispatchers.Default) {
            IncomingKeepScheduleOperation(
                observe = {
                    // Simulate WorkManager observation returning before the
                    // caller reaches the synchronous KEEP enqueue section.
                    queryReturned.complete(Unit)
                    releaseQuery.await()
                    ExistingIncomingWorkState.ENQUEUED
                },
                enqueue = {
                    val accepted = boundary.withCurrentLease(epoch) {
                        enqueueCalls += 1
                    }
                    if (accepted == null) throw IncomingScheduleLeaseRejected()
                },
            ).run()
        }

        queryReturned.await()
        boundary.invalidate()
        releaseQuery.complete(Unit)

        assertEquals(
            IncomingSyncSchedulingOutcome.SessionOwnerRejected,
            scheduling.await(),
        )
        assertEquals(0, enqueueCalls)
    }

    @Test
    fun oldAttemptCannotBorrowReloggedSameOwnerEpoch() = runTest {
        val boundary = SessionBoundary()
        val oldEpoch = boundary.currentEpoch()
        val oldReadStarted = CompletableDeferred<Unit>()
        val releaseOldRead = CompletableDeferred<Unit>()
        var enqueueCalls = 0

        suspend fun attempt(
            epoch: Long,
            accountRead: suspend () -> String,
        ): IncomingSyncSchedulingOutcome {
            val returnedOwner = accountRead()
            return IncomingKeepScheduleOperation(
                observe = { null },
                enqueue = {
                    val accepted = boundary.withCurrentLease(epoch) {
                        if (returnedOwner != "owner-a") {
                            throw IncomingScheduleLeaseRejected()
                        }
                        enqueueCalls += 1
                        true
                    }
                    if (accepted == null) throw IncomingScheduleLeaseRejected()
                },
            ).run()
        }

        val oldAttempt = async(Dispatchers.Default) {
            attempt(oldEpoch) {
                oldReadStarted.complete(Unit)
                releaseOldRead.await()
                // The old suspended read returns A after logout and a fresh A
                // login; only its original epoch may authorize the enqueue.
                "owner-a"
            }
        }
        oldReadStarted.await()

        boundary.invalidate()
        val freshEpoch = boundary.currentEpoch()
        assertNotEquals(oldEpoch, freshEpoch)

        val freshOutcome = attempt(freshEpoch) { "owner-a" }
        assertEquals(IncomingSyncSchedulingOutcome.Queued, freshOutcome)
        assertEquals(1, enqueueCalls)

        releaseOldRead.complete(Unit)
        assertEquals(
            IncomingSyncSchedulingOutcome.SessionOwnerRejected,
            oldAttempt.await(),
        )
        assertEquals(1, enqueueCalls)
    }
}
