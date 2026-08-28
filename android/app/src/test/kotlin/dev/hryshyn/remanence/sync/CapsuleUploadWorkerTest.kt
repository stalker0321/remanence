package dev.hryshyn.remanence.sync

import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleUploadWorkerTest {

    @Test
    fun succeededWorkCompletesSuccessfully() {
        assertEquals(
            ListenableWorker.Result.success(),
            CapsuleUploadWorker.mapOutcome(CapsuleUploadOutcome.Succeeded),
        )
    }

    @Test
    fun missingWorkFailsWithoutAcknowledgingUpload() {
        assertEquals(
            ListenableWorker.Result.failure(),
            CapsuleUploadWorker.mapOutcome(CapsuleUploadOutcome.Missing),
        )
    }

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

    @Test
    fun accountMismatchFails() {
        assertEquals(
            ListenableWorker.Result.failure(),
            CapsuleUploadWorker.mapOutcome(CapsuleUploadOutcome.AccountMismatch),
        )
    }

    @Test
    fun terminalFailureFails() {
        assertEquals(
            ListenableWorker.Result.failure(),
            CapsuleUploadWorker.mapOutcome(CapsuleUploadOutcome.TerminalFailure("VALIDATION_FAILED")),
        )
    }
}
