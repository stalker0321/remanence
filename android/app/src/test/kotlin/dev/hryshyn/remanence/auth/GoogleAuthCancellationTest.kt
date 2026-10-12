package dev.hryshyn.remanence.auth

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import dev.hryshyn.remanence.core.data.network.GoogleAuthRejected
import dev.hryshyn.remanence.ui.auth.AuthMessage
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleAuthCancellationTest {
    @Test fun userDismissalReturnsNeutralIdleNotFailure() {
        assertEquals(GoogleAuthState.Idle, googleFailureState(GetCredentialCancellationException()))
    }
    @Test fun unknownProviderFailureAndHttpErrorsRemainFailures() {
        assertEquals(GoogleAuthState.Failed(false), googleFailureState(GetCredentialUnknownException("private provider text")))
        assertEquals(GoogleAuthState.Failed(false), googleFailureState(GoogleAuthRejected(500)))
        assertEquals(GoogleAuthState.Failed(true), googleFailureState(GoogleAuthRejected(409)))
    }
    @Test fun networkFailureUsesTypedSafeCopy() {
        assertEquals(GoogleAuthState.Failed(false, AuthMessage.NETWORK), googleFailureState(IOException("private host name")))
    }
}
