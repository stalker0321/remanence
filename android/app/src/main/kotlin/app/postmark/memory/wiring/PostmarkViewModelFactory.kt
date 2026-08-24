package app.postmark.memory.wiring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.postmark.memory.AppContainer
import app.postmark.memory.session.RootViewModel
import app.postmark.memory.ui.home.HomeCapabilityViewModel
import app.postmark.memory.ui.auth.LoginViewModel
import app.postmark.memory.ui.auth.RegistrationViewModel

/**
 * FIX-M1-007-08: single Compose-facing factory so every screen ViewModel is
 * lifecycle-owned (viewModelScope) and wired from the one AppContainer.
 */
class PostmarkViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        RootViewModel::class.java -> RootViewModel(
            sessionBootstrap = container.sessionBootstrap,
            logoutAction = { container.logoutUseCase.logout() },
        ) as T
        LoginViewModel::class.java -> LoginViewModel(container.loginUseCase) as T
        RegistrationViewModel::class.java -> RegistrationViewModel(container.registrationUseCase) as T
        HomeCapabilityViewModel::class.java ->
            HomeCapabilityViewModel(container.identityAvailability) as T
        else -> throw IllegalArgumentException("unknown ViewModel: ${modelClass.name}")
    }
}
