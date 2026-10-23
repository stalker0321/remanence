package dev.hryshyn.remanence.ui.auth

import androidx.annotation.StringRes
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import dev.hryshyn.remanence.R

/** Redacted presentation tokens: neither server text nor exception messages reach account UI. */
enum class AuthMessage(@StringRes val resourceId: Int) {
    UNEXPECTED(R.string.auth_unexpected),
    NETWORK(R.string.auth_network),
    INVALID_RESPONSE(R.string.auth_invalid_response),
    WRONG_CREDENTIALS(R.string.auth_wrong_credentials),
    SIGN_IN_FAILED(R.string.auth_sign_in_failed),
    ACCOUNT_UNAVAILABLE(R.string.auth_account_unavailable),
    IDENTITY_REJECTED(R.string.auth_identity_rejected),
    REGISTRATION_FAILED(R.string.auth_registration_failed),
    RECOVERY_REQUIRED(R.string.auth_recovery_required),
    GOOGLE_FAILED(R.string.mvp_google_retry),
    GOOGLE_CONFLICT(R.string.mvp_google_conflict),
}

/** Typed provider cancellation, not proof of a user's action. Never coroutine/lifecycle cancellation. */
fun isCredentialUserCancellation(failure: Exception): Boolean =
    failure is GetCredentialCancellationException || failure is CreateCredentialCancellationException

/** Presentation only: preserve the original exception subtype and never parse private messages.
 * Get cancellation can also report a technical authorization failure. Keep its
 * cancellation semantics but do not tell the user they necessarily cancelled. */
internal enum class CredentialFailure(val reason: String, @StringRes val message: Int) {
    CANCELLED("PROVIDER_CANCELLED", R.string.auth_passkey_cancelled),
    MISSING("PROVIDER_NO_CREDENTIAL", R.string.auth_credential_missing),
    NOT_CONFIGURED("PROVIDER_NOT_CONFIGURED", R.string.auth_credential_not_configured),
    UNAVAILABLE("PROVIDER_UNAVAILABLE", R.string.auth_credential_unavailable),
    FAILED("PROVIDER_FAILED", R.string.auth_credential_failed),
}

internal fun credentialFailure(failure: Exception): CredentialFailure? = when {
    isCredentialUserCancellation(failure) -> CredentialFailure.CANCELLED
    failure is NoCredentialException -> CredentialFailure.MISSING
    failure is CreateCredentialNoCreateOptionException || failure is CreateCredentialProviderConfigurationException ||
        failure is GetCredentialProviderConfigurationException -> CredentialFailure.NOT_CONFIGURED
    failure is CreateCredentialUnsupportedException || failure is GetCredentialUnsupportedException -> CredentialFailure.UNAVAILABLE
    failure is CreateCredentialException || failure is GetCredentialException -> CredentialFailure.FAILED
    else -> null // HTTP, parsing and coroutine failures are not relabelled as provider errors.
}
