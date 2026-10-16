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
    @Test fun savedButPendingContinuationHasDedicatedPresentation() {
        // The exact reducer the save/another-manager catch arm consumes:
        // pending is NOT verified (no Continue, no code), carries the
        // pending copy, and never offers another manager merely
        // because the continuation occurred.
        val presentation = presentRecoveryPending()
        assertEquals(false, presentation.verified)
        assertEquals(R.string.recovery_verification_pending, presentation.message)
        assertEquals(false, presentation.offerAnotherManager)
        // Pending copy is neither verified success nor neutral cancellation.
        assertNotEquals(R.string.recovery_verified, presentation.message)
        assertNotEquals(R.string.auth_passkey_cancelled, presentation.message)
        assertNotEquals(R.string.auth_passkey_setup_failed, presentation.message)
    }
}
