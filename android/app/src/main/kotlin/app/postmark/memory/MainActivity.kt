package app.postmark.memory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.postmark.memory.session.AuthenticatedHomeChrome
import app.postmark.memory.session.RootScreen
import app.postmark.memory.session.RootViewModel
import app.postmark.memory.ui.auth.LoginScreen
import app.postmark.memory.ui.auth.LoginSubmitState
import app.postmark.memory.ui.auth.LoginViewModel
import app.postmark.memory.ui.auth.RegistrationFormScreen
import app.postmark.memory.ui.auth.RegistrationSubmitState
import app.postmark.memory.ui.auth.RegistrationViewModel
import app.postmark.memory.ui.home.BackendHealthUiState
import app.postmark.memory.ui.home.HomeScreen
import app.postmark.memory.wiring.PostmarkViewModelFactory
import app.postmark.memory.ui.navigation.AuthUiState
import postmark.core.data.network.HealthCheckResult

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PostmarkApplication).container

        setContent {
            MaterialTheme {
                RootSurface(container = container)
            }
        }
    }
}

/**
 * FIX-M1-007-08: every ViewModel is lifecycle-scoped, every state is
 * collected with [collectAsStateWithLifecycle], and the root surface changes
 * ONLY after an async auth flow reaches its terminal result - submit clicks
 * never poke the root directly.
 */
@Composable
private fun RootSurface(container: AppContainer) {
    val factory = remember { PostmarkViewModelFactory(container) }

    // I02/I03: cold-start session bootstrap decides the first surface.
    val rootViewModel: RootViewModel = viewModel(factory = factory)
    val loginViewModel: LoginViewModel = viewModel(factory = factory)
    val registrationViewModel: RegistrationViewModel = viewModel(factory = factory)

    var healthState by remember { mutableStateOf(BackendHealthUiState.CHECKING) }
    LaunchedEffect(Unit) {
        healthState = when (container.healthRepository.check()) {
            is HealthCheckResult.Available -> BackendHealthUiState.AVAILABLE
            else -> BackendHealthUiState.UNAVAILABLE
        }
    }

    // The root re-resolves ONLY when a submit flow reaches its terminal state.
    val loginSubmit by loginViewModel.submitState.collectAsStateWithLifecycle()
    LaunchedEffect(loginSubmit) {
        if (loginSubmit is LoginSubmitState.LoggedIn) {
            rootViewModel.onSessionEstablished()
        }
    }
    val registrationSubmit by registrationViewModel.submitState.collectAsStateWithLifecycle()
    LaunchedEffect(registrationSubmit) {
        if (registrationSubmit is RegistrationSubmitState.Completed) {
            rootViewModel.onSessionEstablished()
        }
    }

    val authState by rootViewModel.authState.collectAsStateWithLifecycle()

    RootScreen(
        authState = authState,
        authenticationContent = {
            Column(modifier = Modifier.padding(16.dp)) {
                val form by loginViewModel.form.collectAsStateWithLifecycle()
                val submitState by loginViewModel.submitState.collectAsStateWithLifecycle()
                LoginScreen(
                    form = form,
                    submitState = submitState,
                    onEmailChange = loginViewModel::onEmailChange,
                    onPasswordChange = loginViewModel::onPasswordChange,
                    onSubmit = loginViewModel::submit,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
                val registrationForm by registrationViewModel.form.collectAsStateWithLifecycle()
                RegistrationFormScreen(
                    form = registrationForm,
                    onFieldChange = registrationViewModel::onFieldChange,
                    onSubmit = registrationViewModel::submit,
                    modifier = Modifier,
                )
            }
        },
        homeContent = {
            val authenticated = authState as? AuthUiState.Authenticated
            AuthenticatedHomeChrome(
                handle = authenticated?.handle ?: "",
                onLogout = rootViewModel::logout,
                homeContent = { HomeScreen(healthState) },
            )
        },
    )
}
