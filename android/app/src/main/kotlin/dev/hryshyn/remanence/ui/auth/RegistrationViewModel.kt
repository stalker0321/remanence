package dev.hryshyn.remanence.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.auth.RegistrationUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Terminal states of one registration attempt, with redacted user-facing messages. */
sealed interface RegistrationSubmitState {
    data object Idle : RegistrationSubmitState

    data object Submitting : RegistrationSubmitState

    data class Failed(val message: String) : RegistrationSubmitState

    data object Completed : RegistrationSubmitState
}

/**
 * Bridges the registration form to [RegistrationUseCase]. Validation stays
 * client-side UX; server failures surface as redacted, actionable messages
 * that never echo which email or handle already exists.
 */
class RegistrationViewModel(
    private val useCase: RegistrationUseCase,
    scope: CoroutineScope? = null,
) : ViewModel() {

    /** Injected scope (tests); production runs on the lifecycle-owned scope. */
    private val launchScope: CoroutineScope = scope ?: viewModelScope

    private val _form = MutableStateFlow(RegistrationFormState())
    val form: StateFlow<RegistrationFormState> = _form.asStateFlow()

    private val _submitState = MutableStateFlow<RegistrationSubmitState>(RegistrationSubmitState.Idle)
    val submitState: StateFlow<RegistrationSubmitState> = _submitState.asStateFlow()

    fun onFieldChange(field: RegistrationField, value: String) {
        val current = _form.value
        _form.value = when (field) {
            RegistrationField.EMAIL -> current.copy(email = value)
            RegistrationField.PASSWORD -> current.copy(password = value)
            RegistrationField.HANDLE -> current.copy(handle = value)
        }
    }

    fun submit() {
        if (_submitState.value is RegistrationSubmitState.Submitting) return
        if (!RegistrationFormValidator.canSubmit(_form.value)) return
        val snapshot = _form.value
        _submitState.value = RegistrationSubmitState.Submitting
        launchScope.launch {
            _submitState.value = try {
                mapOutcome(useCase.register(snapshot.email, snapshot.password, snapshot.handle))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                RegistrationSubmitState.Failed("Unexpected error. Try again later.")
            }
        }
    }

    private fun mapOutcome(outcome: RegistrationUseCase.Outcome): RegistrationSubmitState = when (outcome) {
        is RegistrationUseCase.Outcome.Registered -> RegistrationSubmitState.Completed
        is RegistrationUseCase.Outcome.Rejected -> RegistrationSubmitState.Failed(rejectedMessage(outcome.httpStatus))
        RegistrationUseCase.Outcome.NetworkUnreachable ->
            RegistrationSubmitState.Failed("Network unreachable. Try again later.")
        RegistrationUseCase.Outcome.InvalidResponse ->
            RegistrationSubmitState.Failed("Unexpected server response.")
        RegistrationUseCase.Outcome.RecoveryRequired ->
            RegistrationSubmitState.Failed("Existing keys cannot be opened on this device; recovery required.")
    }

    private fun rejectedMessage(httpStatus: Int): String = when (httpStatus) {
        409 -> "Email or handle is unavailable."
        422 -> "Please check the entered fields."
        else -> "Registration failed. Try again later."
    }
}
