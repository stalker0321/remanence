package dev.hryshyn.remanence.sync

import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleUploadWorkerTest {

    @Test
    fun durableUploadUsesConnectedNetworkAndBoundedExponentialBackoff() {
        val owner = dev.hryshyn.remanence.core.model.UserId.parseRest("9c111111-2222-4333-8444-555555555555")
        val capsule = dev.hryshyn.remanence.core.model.CapsuleId.parseRest("9c333333-4444-4555-8666-777777777777")
        val request = CapsuleUploadWorker.request(owner, capsule)
        assertEquals(androidx.work.NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(androidx.work.BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(30_000L, request.workSpec.backoffDelayDuration)
        assertEquals(owner.toRestString(), request.workSpec.input.getString(CapsuleUploadWorker.INPUT_OWNER_USER_ID))
        assertEquals(capsule.toRestString(), request.workSpec.input.getString(CapsuleUploadWorker.INPUT_CAPSULE_ID))
    }

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
    fun parkedStaleRecipientKeyRecoveryFailureParksWorkAsFailure() {
        assertEquals(
            ListenableWorker.Result.failure(),
            CapsuleUploadWorker.mapOutcome(CapsuleUploadOutcome.RecipientKeyStale),
        )
    }

    @Test
    fun initialPersistedStaleRecipientKeySchedulesRecoveryRetry() {
        assertEquals(
            ListenableWorker.Result.retry(),
            CapsuleUploadWorker.mapOutcome(
                CapsuleUploadOutcome.Retryable("RECIPIENT_KEY_STALE_DRAFT"),
            ),
        )
        assertEquals(
            ListenableWorker.Result.retry(),
            CapsuleUploadWorker.mapOutcome(
                CapsuleUploadOutcome.Retryable("RECIPIENT_KEY_STALE_FINALIZE"),
            ),
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
