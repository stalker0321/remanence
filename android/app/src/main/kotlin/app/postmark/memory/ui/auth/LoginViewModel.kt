package app.postmark.memory.ui.auth

import app.postmark.memory.auth.LoginUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LoginSubmitState {
    data object Idle : LoginSubmitState

    data object Submitting : LoginSubmitState

    data class Failed(val message: String) : LoginSubmitState

    /**
     * Password accepted by the server but the matching private identity is
     * absent on this device. Not an error state: the UI explains recovery.
     */
    data object RecoveryRequired : LoginSubmitState

    data class LoggedIn(
        val userId: String,
        val handle: String,
    ) : LoginSubmitState
}

/**
 * Bridges the login form to [LoginUseCase]. Authentication success without
 * local keys surfaces distinctly as [LoginSubmitState.RecoveryRequired]; no
 * replacement bundle is ever generated from here.
 */
class LoginViewModel(
    private val loginUseCase: LoginUseCase,
    private val scope: CoroutineScope,
) {

    private val _form = MutableStateFlow(LoginFormState())
    val form: StateFlow<LoginFormState> = _form.asStateFlow()

    private val _submitState = MutableStateFlow<LoginSubmitState>(LoginSubmitState.Idle)
    val submitState: StateFlow<LoginSubmitState> = _submitState.asStateFlow()

    fun onEmailChange(value: String) {
        _form.value = _form.value.copy(email = value)
    }

    fun onPasswordChange(value: String) {
        _form.value = _form.value.copy(password = value)
    }

    fun submit() {
        if (_submitState.value is LoginSubmitState.Submitting) return
        if (!LoginFormValidator.canSubmit(_form.value)) return
        val snapshot = _form.value
        _submitState.value = LoginSubmitState.Submitting
        scope.launch {
            _submitState.value = try {
                mapOutcome(loginUseCase.login(snapshot.email, snapshot.password))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LoginSubmitState.Failed("Unexpected error. Try again later.")
            }
        }
    }

    private fun mapOutcome(outcome: LoginUseCase.Outcome): LoginSubmitState = when (outcome) {
        is LoginUseCase.Outcome.LoggedIn -> LoginSubmitState.LoggedIn(outcome.userId, outcome.handle)
        is LoginUseCase.Outcome.RecoveryRequired -> LoginSubmitState.RecoveryRequired
        is LoginUseCase.Outcome.Rejected -> LoginSubmitState.Failed(rejectedMessage(outcome.httpStatus))
        LoginUseCase.Outcome.NetworkUnreachable ->
            LoginSubmitState.Failed("Network unreachable. Try again later.")
        LoginUseCase.Outcome.InvalidResponse ->
            LoginSubmitState.Failed("Unexpected server response.")
    }

    private fun rejectedMessage(httpStatus: Int): String = when (httpStatus) {
        401 -> "Incorrect email or password."
        else -> "Sign-in failed. Try again later."
    }
}
