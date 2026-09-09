package dev.hryshyn.remanence.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class IncomingAcceptanceDiagnosticsTest {

    @Before
    fun resetBefore() {
        IncomingAcceptanceDiagnostics.reset()
    }

    @After
    fun resetAfter() {
        IncomingAcceptanceDiagnostics.reset()
    }

    @Test
    fun lateOwnerAEventCannotOverwriteOwnerBWorkerProgress() {
        var ownerAIsCurrent = true
        val ownerA = IncomingAcceptanceDiagnostics.workerReporter { ownerAIsCurrent }
        val ownerB = IncomingAcceptanceDiagnostics.workerReporter { true }

        ownerA.report("owner-a progress")
        ownerAIsCurrent = false
        ownerB.report("owner-b progress")
        ownerA.report("late owner-a progress")

        assertEquals("owner-b progress", IncomingAcceptanceDiagnostics.workerProgress.value)
        ownerA.close()
        ownerB.close()
    }

    @Test
    fun schedulingAndWorkerChannelsDoNotOverwriteEachOther() {
        val scheduling = IncomingAcceptanceDiagnostics.schedulingReporter { true }
        val worker = IncomingAcceptanceDiagnostics.workerReporter { true }

        scheduling.report(IncomingSyncSchedulingOutcome.Queued)
        worker.report(IncomingSyncWorkerStage.ACCEPTANCE)

        assertEquals("queued", IncomingAcceptanceDiagnostics.schedulingState.value)
        assertEquals("sync stage=acceptance", IncomingAcceptanceDiagnostics.workerProgress.value)
        assertNotEquals(
            IncomingAcceptanceDiagnostics.schedulingState.value,
            IncomingAcceptanceDiagnostics.workerProgress.value,
        )
        scheduling.close()
        worker.close()
    }

    @Test
    fun workerPreOwnerAdmissionCannotPublishProgress() {
        var admitted = false
        val reporter = IncomingAcceptanceDiagnostics.workerReporter { admitted }

        reporter.report(IncomingSyncWorkerStage.SESSION_RESTORE)
        assertEquals("not run", IncomingAcceptanceDiagnostics.workerProgress.value)

        admitted = true
        reporter.report(IncomingSyncWorkerStage.SESSION_RESTORE)
        assertEquals("sync stage=session restore", IncomingAcceptanceDiagnostics.workerProgress.value)
        reporter.close()
    }

    @Test
    fun resetCannotInterleaveBetweenEligibilityAndPublication() = runTest {
        val eligible = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val reporter = IncomingAcceptanceDiagnostics.workerReporter(
            isCurrent = { true },
            beforePublishForTests = {
                eligible.countDown()
                releasePublication.await()
            },
        )

        val publish = async(Dispatchers.Default) {
            reporter.report("owner-a progress")
        }
        assertTrue(eligible.await(5, TimeUnit.SECONDS))

        // reset is intentionally launched while the reporter is paused at the
        // exact eligibility -> publish boundary. The common monitor forces it
        // to run only after the whole report transaction has completed.
        val reset = async(Dispatchers.Default) {
            IncomingAcceptanceDiagnostics.reset()
        }
        releasePublication.countDown()

        publish.await()
        reset.await()
        assertEquals("not run", IncomingAcceptanceDiagnostics.workerProgress.value)
        reporter.close()
    }
}
