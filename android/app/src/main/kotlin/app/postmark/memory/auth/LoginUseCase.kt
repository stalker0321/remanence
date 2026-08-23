package app.postmark.memory.auth

import postmark.core.data.network.AuthFailure
import postmark.core.data.network.AuthResult
import postmark.core.data.network.LoginRequestDto
import postmark.core.data.network.LoginResponseDto
import postmark.core.data.network.RegistrationUserDto

/** Port answering whether the local wrapped identity covers [bundleId][postmark.core.model.KeyBundleId]. */
fun interface LoginIdentityPort {

    /**
     * True only when this device holds a locally wrapped private identity
     * whose derived bundle ID equals [activeKeyBundleId]. Must never generate
     * replacement material.
     */
    fun hasIdentityFor(activeKeyBundleId: String): Boolean
}

/** Port over the server login endpoint. */
fun interface LoginAuthApiPort {
    suspend fun login(request: LoginRequestDto): AuthResult<LoginResponseDto>
}

/**
 * Orchestrates login. Authentication and E2EE identity stay separate
 * (docs/security.md section 4): when the server accepts the password but this
 * device lacks the matching private identity, the outcome is
 * [LoginUseCase.Outcome.RecoveryRequired] and no replacement bundle may be
 * generated silently.
 */
class LoginUseCase(
    private val identity: LoginIdentityPort,
    private val authApi: LoginAuthApiPort,
    private val accounts: CurrentAccountPort,
) {

    sealed interface Outcome {
        data class LoggedIn(
            val userId: String,
            val handle: String,
            val activeKeyBundleId: String,
        ) : Outcome

        /** Password accepted by the server, but local private keys are absent. */
        data class RecoveryRequired(
            val userId: String,
            val handle: String,
            val activeKeyBundleId: String,
        ) : Outcome

        data class Rejected(val httpStatus: Int) : Outcome

        data object NetworkUnreachable : Outcome

        data object InvalidResponse : Outcome
    }

    suspend fun login(email: String, password: String): Outcome = when (
        val result = authApi.login(LoginRequestDto(email = email, password = password))
    ) {
        is AuthResult.Success -> {
            val response = result.value
            accounts.recordCurrentAccount(response.user, response.activeKeyBundle.keyBundleId)
            val loggedInLocally = identity.hasIdentityFor(response.activeKeyBundle.keyBundleId)
            if (loggedInLocally) {
                Outcome.LoggedIn(
                    userId = response.user.userId,
                    handle = response.user.handle,
                    activeKeyBundleId = response.activeKeyBundle.keyBundleId,
                )
            } else {
                Outcome.RecoveryRequired(
                    userId = response.user.userId,
                    handle = response.user.handle,
                    activeKeyBundleId = response.activeKeyBundle.keyBundleId,
                )
            }
        }
        is AuthResult.Failure -> when (result.reason) {
            AuthFailure.HTTP -> Outcome.Rejected(result.httpStatus ?: 0)
            AuthFailure.NETWORK -> Outcome.NetworkUnreachable
            AuthFailure.INVALID_RESPONSE -> Outcome.InvalidResponse
        }
    }
}
