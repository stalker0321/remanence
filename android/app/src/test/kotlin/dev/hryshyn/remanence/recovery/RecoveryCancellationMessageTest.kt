package dev.hryshyn.remanence.recovery

import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import dev.hryshyn.remanence.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RecoveryCancellationMessageTest {
    @Test fun createAndGetDismissalsUseNeutralCopyForSetupAndRestore() {
        for (failure in listOf(CreateCredentialCancellationException(), GetCredentialCancellationException())) {
            for (protecting in listOf(true, false)) {
                assertEquals(R.string.auth_passkey_cancelled, recoveryFailureMessage(failure, protecting))
            }
        }
    }
    @Test fun unknownProviderFailuresAreNotClassifiedAsUserCancellation() {
        val failure = GetCredentialUnknownException("private provider message")
        assertEquals(R.string.auth_passkey_setup_failed, recoveryFailureMessage(failure, true))
        assertEquals(R.string.recovery_failed, recoveryFailureMessage(failure, false))
    }
    @Test fun savedButPendingContinuationHasDedicatedCopy() {
        for (protecting in listOf(true, false)) {
            assertEquals(
                R.string.recovery_verification_pending,
                recoveryFailureMessage(RecoveryVerificationPending("cred-1"), protecting),
            )
        }
        // Pending is neither verified success nor neutral cancellation.
        assertNotEquals(R.string.recovery_verified, R.string.recovery_verification_pending)
        assertNotEquals(R.string.auth_passkey_cancelled, R.string.recovery_verification_pending)
        assertNotEquals(R.string.auth_passkey_setup_failed, R.string.recovery_verification_pending)
    }
}
