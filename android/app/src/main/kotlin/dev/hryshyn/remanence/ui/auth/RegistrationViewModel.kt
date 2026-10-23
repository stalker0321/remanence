package dev.hryshyn.remanence.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.auth.RegistrationUseCase
import dev.hryshyn.remanence.core.data.network.RegistrationProblemCode
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

    data class Failed(val message: AuthMessage) : RegistrationSubmitState

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

    init {
        // Opening the registration screen starts a new logical flow, so
        // keys staged by an earlier signup/logout cycle are never reused
        // for an unrelated registration of the same target. Retries
        // within this screen keep the epoch and reuse the candidate.
        // Staged material is never deleted — it stays available for
        // lost-ACK login binding by exact bundle.
        useCase.startNewFlow()
    }

    /** Injected scope (tests); production runs on the lifecycle-owned scope. */
    private val launchScope: CoroutineScope = scope ?: viewModelScope

    private val _form = MutableStateFlow(RegistrationFormState())
    val form: StateFlow<RegistrationFormState> = _form.asStateFlow()

    private val _submitState = MutableStateFlow<RegistrationSubmitState>(RegistrationSubmitState.Idle)
    val submitState: StateFlow<RegistrationSubmitState> = _submitState.asStateFlow()

    /**
     * Activity retention is longer than a completed auth flow. A subsequent
     * SignedOut boundary retires its form (including credentials), but ordinary
     * login/register navigation and rotation must preserve unfinished input.
     * The existing use case already rotates its key epoch on success/logout.
     */
    fun onSignedOut() {
        if (_submitState.value == RegistrationSubmitState.Completed) {
            _form.value = RegistrationFormState()
            _submitState.value = RegistrationSubmitState.Idle
        }
    }

    fun onFieldChange(field: RegistrationField, value: String) {
        val current = _form.value
        _form.value = when (field) {
            RegistrationField.EMAIL -> current.copy(email = value)
            RegistrationField.PASSWORD -> current.copy(password = value)
            RegistrationField.HANDLE -> current.copy(handle = value)
        }
    }

    fun submit() {
        if (_submitState.value is RegistrationSubmitState.Submitting ||
            _submitState.value is RegistrationSubmitState.Completed) return
        if (!RegistrationFormValidator.canSubmit(_form.value)) return
        val snapshot = _form.value
        _submitState.value = RegistrationSubmitState.Submitting
        launchScope.launch {
            _submitState.value = try {
                mapOutcome(useCase.register(snapshot.email, snapshot.password, snapshot.handle))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                RegistrationSubmitState.Failed(AuthMessage.UNEXPECTED)
            }
        }
    }

    private fun mapOutcome(outcome: RegistrationUseCase.Outcome): RegistrationSubmitState = when (outcome) {
        is RegistrationUseCase.Outcome.Registered -> RegistrationSubmitState.Completed
        is RegistrationUseCase.Outcome.Rejected -> RegistrationSubmitState.Failed(rejectedMessage(outcome.problemCode))
        RegistrationUseCase.Outcome.NetworkUnreachable ->
            RegistrationSubmitState.Failed(AuthMessage.NETWORK)
        RegistrationUseCase.Outcome.InvalidResponse ->
            RegistrationSubmitState.Failed(AuthMessage.INVALID_RESPONSE)
        RegistrationUseCase.Outcome.RecoveryRequired ->
            RegistrationSubmitState.Failed(AuthMessage.RECOVERY_REQUIRED)
    }

    private fun rejectedMessage(problemCode: RegistrationProblemCode?): AuthMessage = when (problemCode) {
        RegistrationProblemCode.EMAIL_UNAVAILABLE,
        RegistrationProblemCode.HANDLE_UNAVAILABLE,
        -> AuthMessage.ACCOUNT_UNAVAILABLE
        RegistrationProblemCode.KEY_BUNDLE_INVALID -> AuthMessage.IDENTITY_REJECTED
        null -> AuthMessage.REGISTRATION_FAILED
    }
}
