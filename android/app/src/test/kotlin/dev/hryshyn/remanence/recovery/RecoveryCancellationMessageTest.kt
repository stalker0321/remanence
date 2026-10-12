package dev.hryshyn.remanence.recovery

import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import dev.hryshyn.remanence.R
import org.junit.Assert.assertEquals
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
}
