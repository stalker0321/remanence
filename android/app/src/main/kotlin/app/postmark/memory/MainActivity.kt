package app.postmark.memory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import app.postmark.memory.ui.create.CreateScreen
import app.postmark.memory.ui.create.CreateViewModel
import app.postmark.memory.ui.scan.ScanScreen
import app.postmark.memory.ui.scan.ScanTerminalState
import app.postmark.memory.ui.scan.ScanViewModel
import app.postmark.memory.ui.auth.RegistrationFormScreen
import app.postmark.memory.ui.auth.RegistrationSubmitState
import app.postmark.memory.ui.auth.RegistrationViewModel
import app.postmark.memory.ui.home.BackendHealthUiState
import app.postmark.memory.ui.home.HomeCapabilityViewModel
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
    val capabilityViewModel: HomeCapabilityViewModel = viewModel(factory = factory)

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
    val destination by rootViewModel.destination.collectAsStateWithLifecycle()

    // Real capability derivation: authenticated AND both keysets on device.
    LaunchedEffect(authState) {
        capabilityViewModel.onAuthStateChanged(authState)
    }
    val accountCapability by capabilityViewModel.capability.collectAsStateWithLifecycle()

    RootScreen(
        authState = authState,
        destination = destination,
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
                homeContent = {
                    HomeScreen(
                        state = healthState,
                        accountCapability = accountCapability,
                        onCreate = rootViewModel::openCreate,
                        onScan = rootViewModel::openScan,
                    )
                },
            )
        },
        createContent = {
            val createViewModel: CreateViewModel = viewModel(factory = factory)
            CreateScreen(viewModel = createViewModel)
        },
        capsuleContent = { grantId, capsuleId ->
            CapsuleRoute(
                container = container,
                grantId = grantId,
                capsuleId = capsuleId,
                onClose = rootViewModel::closeCapsule,
            )
        },
        capsuleIdResolver = rootViewModel::capsuleIdFor,
        scanContent = {
            val scanViewModel: ScanViewModel = viewModel(factory = factory)
            // A verified grant navigates through the guarded capsule route.
            val terminal by scanViewModel.terminal.collectAsStateWithLifecycle()
            LaunchedEffect(terminal) {
                val granted = terminal as? ScanTerminalState.Granted ?: return@LaunchedEffect
                rootViewModel.openCapsuleWithGrant(
                    grantId = granted.grantId,
                    capsuleId = granted.capsuleId,
                )
            }
            ScanScreen(viewModel = scanViewModel)
        },
        onExitFlow = rootViewModel::returnToHome,
    )
}

/**
 * Intro chrome for flows whose full production wiring lands in
 * FIX-M1-007-11/12: an honest description of what the surface does plus
 * the working exit path. It performs no recognition, crypto, or network work.
 */
@Composable
private fun FlowIntroSurface(title: String, detail: String) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(text = detail)
    }
}


/**
 * FIX-M1-007-13: the live capsule presentation bound to the memory-only
 * grant lifecycle. Photos decrypt on demand from ciphertext; leaving the
 * screen consumes the grant and releases every decrypted byte.
 */
@Composable
private fun CapsuleRoute(
    container: AppContainer,
    grantId: String,
    capsuleId: String,
    onClose: () -> Unit,
) {
    var state by remember(grantId) {
        mutableStateOf<app.postmark.memory.ui.capsule.CapsulePresentationState?>(null)
    }

    LaunchedEffect(grantId, capsuleId) {
        if (state != null || capsuleId.isEmpty()) return@LaunchedEffect
        val loaded = runCatching { container.identityRepository.load() }.getOrNull()
            as? postmark.core.crypto.IdentityBundleRepository.LoadResult.Available
            ?: return@LaunchedEffect
        val row = container.currentAccountStore.loadEntity() ?: return@LaunchedEffect
        val source = app.postmark.memory.ui.capsule.CapsuleContentSource(
            database = container.database,
            encryptionPrivateHandle = loaded.encryptionHandle,
            ownUserId = java.util.UUID.fromString(row.userId),
            recipientKeyBundleIdOf = { id ->
                container.database.outboxCapsuleDao().getByCapsuleId(id)
                    ?.recipientKeyBundleId?.let(java.util.UUID::fromString)
            },
        )
        val photoCount = runCatching { source.photoCount(capsuleId) }.getOrNull() ?: return@LaunchedEffect
        // The note decrypts once at open; photos decrypt per page on demand.
        val note = runCatching { source.noteText(capsuleId) }.getOrNull()
        val presentation = app.postmark.memory.ui.capsule.CapsulePresentationState(
            photoLoader = object : app.postmark.memory.ui.capsule.CapsulePhotoLoader {
                override suspend fun load(ordinal: Int): app.postmark.memory.ui.capsule.DecryptedPhoto =
                    app.postmark.memory.ui.capsule.DecryptedPhoto(
                        ordinal,
                        source.loadPhoto(capsuleId, ordinal).jpegBytes,
                    )
            },
            noteText = { note },
        )
        presentation.open(photoCount.coerceIn(3, 5))
        state = presentation
    }

    val presentation = state
    if (presentation == null || !presentation.isOpen) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.testTag("capsule_route_loading"),
        )
        return
    }
    app.postmark.memory.ui.capsule.CapsuleScreen(state = presentation, onClose = onClose)
}
