package dev.hryshyn.remanence.ui.auth

import androidx.credentials.exceptions.*
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.auth.atomicRegistrationErrorMessage
import dev.hryshyn.remanence.core.data.network.AtomicRegistrationRejected
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.*
import org.junit.Test

class CredentialFailureTest {
    @Test fun typedCancellationIsStillCancellationButNeverInferredFromTextOrCause() {
        for (failure in listOf(GetCredentialCancellationException(), CreateCredentialCancellationException())) {
            assertTrue(isCredentialUserCancellation(failure))
            assertEquals(CredentialFailure.CANCELLED, credentialFailure(failure))
        }
        val unknown = GetCredentialUnknownException("Cancelled")
        unknown.initCause(GetCredentialCancellationException())
        assertFalse(isCredentialUserCancellation(unknown))
        assertEquals(CredentialFailure.FAILED, credentialFailure(unknown))
    }

    @Test fun domProviderFailureIsNotBackendFailureOrProofOfUserDismissal() {
        for (failure in listOf(CreatePublicKeyCredentialDomException(SecurityError(), "synthetic RP detail"),
            GetPublicKeyCredentialDomException(SecurityError(), "synthetic RP detail"))) {
            assertEquals(CredentialFailure.FAILED, credentialFailure(failure))
            assertEquals(R.string.auth_credential_failed, atomicRegistrationErrorMessage(credentialFailure(failure)!!.reason))
        }
    }

    @Test fun lifecycleHttpAndUnrecognizedNonProviderExceptionsAreNotReclassified() {
        for (failure in listOf(CancellationException(), IOException("Cancelled"),
            AtomicRegistrationRejected(500, "SETUP_REJECTED"), IllegalStateException("UNREGISTERED_ON_API_CONSOLE"))) {
            assertNull(credentialFailure(failure)); assertFalse(isCredentialUserCancellation(failure))
        }
    }

    @Test fun presentationDoesNotReuseServerOrSavedAccountCopyForProviderAndMissingPrf() {
        for (failure in CredentialFailure.entries) {
            assertEquals(failure.message, atomicRegistrationErrorMessage(failure.reason))
            assertNotEquals(R.string.auth_invalid_response, failure.message)
            if (failure != CredentialFailure.CANCELLED) assertNotEquals(R.string.auth_passkey_cancelled, failure.message)
        }
        assertEquals(R.string.atomic_registration_prf_unavailable, atomicRegistrationErrorMessage("PRF_UNAVAILABLE"))
        assertNotEquals(R.string.recovery_prf_missing, atomicRegistrationErrorMessage("PRF_UNAVAILABLE"))
        assertEquals(R.string.recovery_verification_pending, atomicRegistrationErrorMessage("CREDENTIAL_PENDING"))
        assertEquals(R.string.atomic_registration_failed, atomicRegistrationErrorMessage("private unknown reason"))
    }
}
