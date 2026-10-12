package dev.hryshyn.remanence.ui.auth

import androidx.annotation.StringRes
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
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

/** Provider-sheet cancellation is distinct from coroutine/lifecycle cancellation and genuine failure. */
fun isCredentialUserCancellation(failure: Exception): Boolean =
    failure is GetCredentialCancellationException || failure is CreateCredentialCancellationException
