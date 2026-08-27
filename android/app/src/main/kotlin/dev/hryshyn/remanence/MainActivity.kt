package dev.hryshyn.remanence

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
import dev.hryshyn.remanence.session.AuthenticatedHomeChrome
import dev.hryshyn.remanence.session.RootScreen
import dev.hryshyn.remanence.session.RootViewModel
import dev.hryshyn.remanence.ui.auth.LoginScreen
import dev.hryshyn.remanence.ui.auth.LoginSubmitState
import dev.hryshyn.remanence.ui.auth.LoginViewModel
import dev.hryshyn.remanence.ui.create.CreateScreen
import dev.hryshyn.remanence.ui.create.CreateViewModel
import dev.hryshyn.remanence.ui.scan.ScanScreen
import dev.hryshyn.remanence.ui.scan.ScanTerminalState
import dev.hryshyn.remanence.ui.scan.ScanViewModel
import dev.hryshyn.remanence.ui.auth.RegistrationFormScreen
import dev.hryshyn.remanence.ui.auth.RegistrationSubmitState
import dev.hryshyn.remanence.ui.auth.RegistrationViewModel
import dev.hryshyn.remanence.ui.home.BackendHealthUiState
import dev.hryshyn.remanence.ui.home.HomeCapabilityViewModel
import dev.hryshyn.remanence.ui.home.HomeScreen
import dev.hryshyn.remanence.wiring.RemanenceViewModelFactory
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.core.data.network.HealthCheckResult

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as RemanenceApplication).container

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
    val factory = remember { RemanenceViewModelFactory(container) }

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
                val registrationSubmit by registrationViewModel.submitState.collectAsStateWithLifecycle()
                RegistrationFormScreen(
                    form = registrationForm,
                    submitState = registrationSubmit,
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
            // FIX-REVIEW-02: every entry starts a fresh create session; the
            // same epoch across rotation is a deliberate no-op.
            val createEpoch by rootViewModel.createSessionEpoch.collectAsStateWithLifecycle()
            val authenticatedOwner = (authState as? AuthUiState.Authenticated)?.userId
            LaunchedEffect(createEpoch, authenticatedOwner) {
                // Capture the authenticated owner before Create can launch any
                // suspending publish work; the ViewModel keeps this snapshot
                // for all staging and cleanup paths.
                createViewModel.beginSession(createEpoch, authenticatedOwner)
            }
            androidx.compose.runtime.key(createEpoch) {
                CreateScreen(viewModel = createViewModel)
            }
        },
        capsuleContent = { grantId, capsuleId ->
            dev.hryshyn.remanence.ui.capsule.CapsuleRoute(
                grantId = grantId,
                capsuleId = capsuleId,
                identityLoader = dev.hryshyn.remanence.ui.capsule.CapsuleIdentityLoader {
                    when (val loaded = container.identityRepository.load()) {
                        is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available ->
                            loaded.encryptionHandle
                        dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.RecoveryRequired -> null
                    }
                },
                sourceFactory = { handle ->
                    dev.hryshyn.remanence.ui.capsule.CapsuleContentSource(
                        database = container.database,
                        encryptionPrivateHandle = handle,
                        // M2-P03: presentation material resolves only under the
                        // owning authenticated account; no active account fails closed.
                        ownerUserIdProvider = {
                            container.currentAccountStore.loadEntity()?.userId
                                ?: error("no authenticated local account")
                        },
                    )
                },
                validateLiveGrant = { rootViewModel.requireLivePresentationGrant(grantId) },
                revocations = rootViewModel.capsuleRevocations,
                onClose = rootViewModel::closeCapsule,
            )
        },
        capsuleIdResolver = rootViewModel::capsuleIdFor,
        scanContent = {
            val scanViewModel: ScanViewModel = viewModel(factory = factory)
            // FIX-REVIEW-02/ANDROID-HOTFIX-A: every entry is a fresh FRONT-
            // first scan; initialize the epoch before allowing any camera UI
            // to compose. LaunchedEffect runs after composition, so the gate
            // is required for a retained VM whose old controller is still
            // Granted/Binding while a new epoch is being entered.
            val scanEpoch by rootViewModel.scanSessionEpoch.collectAsStateWithLifecycle()
            val initializedEpoch by scanViewModel.initializedEpoch.collectAsStateWithLifecycle()
            LaunchedEffect(scanEpoch) { scanViewModel.beginSession(scanEpoch) }
            if (initializedEpoch == scanEpoch) {
                androidx.compose.runtime.key(scanEpoch) {
                    ScanFlowSurface(rootViewModel = rootViewModel, scanViewModel = scanViewModel)
                }
            } else {
                Text("Preparing scan…", modifier = Modifier.testTag("scan_session_initializing"))
            }
        },
        onExitFlow = rootViewModel::returnToHome,
    )
}

/**
 * FIX-M1-007-12: the live scan surface. A verified grant navigates through
 * the guarded capsule route - only while this surface is composed.
 */
@Composable
private fun ScanFlowSurface(rootViewModel: RootViewModel, scanViewModel: ScanViewModel) {
    val terminal by scanViewModel.terminal.collectAsStateWithLifecycle()
    LaunchedEffect(terminal) {
        val granted = terminal as? ScanTerminalState.Granted ?: return@LaunchedEffect
        // FIX-REVIEW-03: only the random grant ID travels; the root resolves
        // the capsule ID through THE authoritative grant manager itself.
        rootViewModel.openCapsuleWithGrant(grantId = granted.grantId)
    }
    ScanScreen(
        viewModel = scanViewModel,
        // Activity recreation disposes/recreates Compose but keeps the
        // ViewModel and root destination. Only an actual route exit should
        // tear down the scan; this preserves same-epoch rotation state.
        onScreenDispose = {
            if (rootViewModel.destination.value != AppDestination.Scan) {
                scanViewModel.resetSession()
            }
        },
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
