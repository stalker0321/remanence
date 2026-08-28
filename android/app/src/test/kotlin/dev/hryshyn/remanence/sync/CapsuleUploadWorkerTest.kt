package dev.hryshyn.remanence.sync

import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleUploadWorkerTest {

    @Test
    fun staleRecipientKeyParksWorkAsFailure() {
        assertEquals(
            ListenableWorker.Result.failure(),
            CapsuleUploadWorker.mapOutcome(CapsuleUploadOutcome.RecipientKeyStale),
        )
    }

    @Test
    fun transientRetryableWorkStillRetries() {
        assertEquals(
            ListenableWorker.Result.retry(),
            CapsuleUploadWorker.mapOutcome(CapsuleUploadOutcome.Retryable("NETWORK")),
        )
    }
}
