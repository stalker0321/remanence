package app.postmark.memory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import app.postmark.memory.session.AuthenticatedHomeChrome
import app.postmark.memory.session.RootScreen
import app.postmark.memory.session.RootViewModel
import app.postmark.memory.ui.auth.LoginScreen
import app.postmark.memory.ui.auth.LoginViewModel
import app.postmark.memory.ui.auth.RegistrationFormScreen
import app.postmark.memory.ui.auth.RegistrationViewModel
import app.postmark.memory.ui.home.BackendHealthUiState
import app.postmark.memory.ui.home.HomeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import app.postmark.memory.ui.navigation.AuthUiState
import postmark.core.data.network.HealthCheckResult
import kotlinx.coroutines.SupervisorJob

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PostmarkApplication).container
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        setContent {
            MaterialTheme {
                // I02/I03: cold-start session bootstrap decides the first surface.
                val rootViewModel = remember { RootViewModel(container.sessionBootstrap) }
                var healthState by remember { mutableStateOf(BackendHealthUiState.CHECKING) }

                LaunchedEffect(Unit) {
                    healthState = when (container.healthRepository.check()) {
                        is HealthCheckResult.Available -> BackendHealthUiState.AVAILABLE
                        else -> BackendHealthUiState.UNAVAILABLE
                    }
                }

                val loginViewModel = remember { LoginViewModel(container.loginUseCase, scope) }
                val registrationViewModel = remember {
                    RegistrationViewModel(container.registrationUseCase, scope)
                }

                RootScreen(
                    authState = rootViewModel.authState.value,
                    authenticationContent = {
                        Column {
                            LoginScreen(
                                form = loginViewModel.form.value,
                                submitState = loginViewModel.submitState.value,
                                onEmailChange = loginViewModel::onEmailChange,
                                onPasswordChange = loginViewModel::onPasswordChange,
                                onSubmit = {
                                    loginViewModel.submit()
                                    rootViewModel.onSessionEstablished()
                                },
                                modifier = Modifier.padding(16.dp),
                            )
                            RegistrationFormScreen(
                                form = registrationViewModel.form.value,
                                onFieldChange = registrationViewModel::onFieldChange,
                                onSubmit = {
                                    registrationViewModel.submit()
                                    rootViewModel.onSessionEstablished()
                                },
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    },
                    homeContent = {
                        val authenticated = rootViewModel.authState.value as? AuthUiStateHolder
                        AuthenticatedHomeChrome(
                            handle = authenticated?.handle ?: "",
                            onLogout = rootViewModel::logout,
                            homeContent = { HomeScreen(healthState) },
                        )
                    },
                )
            }
        }
    }
}

/** Local alias keeping the handle extraction above readable. */
private typealias AuthUiStateHolder = app.postmark.memory.ui.navigation.AuthUiState.Authenticated
