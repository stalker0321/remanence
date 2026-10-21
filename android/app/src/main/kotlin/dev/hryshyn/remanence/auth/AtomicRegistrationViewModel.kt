package dev.hryshyn.remanence.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.AppContainer
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.core.data.network.AtomicAction
import dev.hryshyn.remanence.recovery.RecoveryCredentialPort
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class AtomicRegistrationViewModel(container: AppContainer) : ViewModel() {
    private val environment = atomicEnvironment(container)
    private val controller = AtomicRegistrationController(environment)
    val state = controller.state
    private fun enabled() = BuildConfig.DEBUG && BuildConfig.REMANENCE_ATOMIC_REGISTRATION &&
        BuildConfig.REMANENCE_GOOGLE_SIGN_IN_ENABLED && BuildConfig.REMANENCE_RECOVERY_ENABLED

    fun begin(credentials: GoogleCredentialPort) {
        if (enabled()) viewModelScope.launch { controller.begin(credentials) }
    }
    fun save(handle: String, credentials: RecoveryCredentialPort) {
        if (enabled()) viewModelScope.launch { controller.save(handle, credentials) }
    }
    fun retry() { if (enabled()) viewModelScope.launch { controller.retry() } }
    fun resolve() { if (enabled()) viewModelScope.launch { controller.resolve() } }
    fun restart() { if (enabled()) viewModelScope.launch { controller.restart() } }
    fun cancelReservation() { if (enabled()) viewModelScope.launch { controller.cancelReservation() } }
    fun leave() {
        val setup = controller.leave() ?: return
        // Local authority is already gone. No account rollback or guaranteed remote revoke.
        viewModelScope.launch {
            withTimeoutOrNull(5000) {
                runCatching { environment.api.request(AtomicAction.LEAVE, setup.reservation.id, setup.token,
                    buildJsonObject { put("epoch", setup.reservation.epoch) }) }
            }
        }
    }
    override fun onCleared() { controller.leave(); super.onCleared() }
}
