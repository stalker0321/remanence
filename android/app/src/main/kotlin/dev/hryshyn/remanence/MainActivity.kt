package dev.hryshyn.remanence

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.ui.hold.HoldTextButton
import dev.hryshyn.remanence.ui.hold.HoldInformation
import dev.hryshyn.remanence.session.HomeIntent
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        val container = (application as RemanenceApplication).container

        setContent {
            HoldTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                        RootSurface(container = container)
                    }
                }
            }
        }
    }
}

/**
 * The single Compose lifecycle bridge for authenticated restart/resume work.
 * The root owns the resolution; WorkManager KEEP coalesces duplicate attempts.
 */
@Composable
internal fun ForegroundResumeEffect(onResume: () -> Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        onResume()
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
    ForegroundResumeEffect(rootViewModel::onAppForegrounded)
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

    // The root re-resolves after a terminal submit flow and on foreground.
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

    val homeIntent by rootViewModel.homeIntent.collectAsStateWithLifecycle()
    var registering by rememberSaveable { mutableStateOf(false) }
    BackHandler(authState == AuthUiState.SignedOut && homeIntent != null) {
        registering = false
        rootViewModel.cancelHomeIntent()
    }

    RootScreen(
        showPublicHome = homeIntent == null,
        authState = authState,
        destination = destination,
        authenticationContent = {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (authState == AuthUiState.SignedOut) {
                    HoldTextButton(onClick = { registering = false; rootViewModel.cancelHomeIntent() }) { Text("back") }
                }
                Text(if (registering) "make yourself at home" else "a place for your memories", style = MaterialTheme.typography.headlineLarge)
                HoldInformation(when (homeIntent) {
                    HomeIntent.OPEN -> "Sign in to open the remanence meant for you."
                    HomeIntent.MAKE -> "Sign in to leave a memory for someone."
                    else -> "Your account keeps each remanence with the person it was meant for."
                })
                if (authState == AuthUiState.RequiresConnectivity) {
                    HoldInformation("Connect to the internet so we can check your session.")
                    HoldTextButton(onClick = rootViewModel::onAppForegrounded) { Text("try connection again") }
                }
                if (registering) {
                    val form by registrationViewModel.form.collectAsStateWithLifecycle()
                    val submit by registrationViewModel.submitState.collectAsStateWithLifecycle()
                    RegistrationFormScreen(form, submit, registrationViewModel::onFieldChange, registrationViewModel::submit)
                    HoldTextButton(onClick = { registering = false }, enabled = submit !is RegistrationSubmitState.Submitting) { Text("I already have an account") }
                } else {
                    val form by loginViewModel.form.collectAsStateWithLifecycle()
                    val submit by loginViewModel.submitState.collectAsStateWithLifecycle()
                    LoginScreen(form, submit, loginViewModel::onEmailChange, loginViewModel::onPasswordChange, loginViewModel::submit)
                    HoldTextButton(onClick = { registering = true }, enabled = submit !is LoginSubmitState.Submitting) { Text("create an account") }
                }
            }
        },
        homeContent = {
            val authenticated = authState as? AuthUiState.Authenticated
            val home: @Composable () -> Unit = {
                HomeScreen(
                    state = healthState, accountCapability = accountCapability,
                    publicEntry = authState == AuthUiState.SignedOut,
                    onCreate = { rootViewModel.requestHomeIntent(HomeIntent.MAKE) },
                    onScan = { rootViewModel.requestHomeIntent(HomeIntent.OPEN) },
                )
            }
            if (authenticated != null) {
                AuthenticatedHomeChrome(authenticated.handle, rootViewModel::logout, home)
            } else {
                home()
            }
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
                CreateScreen(
                    viewModel = createViewModel,
                    // Activity recreation disposes Compose while the root
                    // remains on Create. Only a stable route exit owns the
                    // Create session teardown; this preserves same-epoch
                    // rotation and any in-progress publish.
                    onScreenDispose = {
                        if (rootViewModel.destination.value != AppDestination.Create) {
                            createViewModel.endSession()
                        }
                    },
                )
            }
        },
        capsuleContent = { grantId ->
            dev.hryshyn.remanence.ui.capsule.CapsuleRoute(
                grantId = grantId,
                contentFactory = {
                    val binding = rootViewModel.presentationGrantFor(grantId)
                        ?: error("presentation grant is no longer live")
                    val capsuleId = binding.capsuleId.toString()
                    val reader = when (binding.source) {
                        dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource.INCOMING ->
                            requireNotNull(binding.incomingPresentation).also { prepared ->
                                check(prepared.admitForOpen()) {
                                    "incoming capsule is no longer available"
                                }
                            }.let { prepared ->
                                dev.hryshyn.remanence.ui.capsule.IncomingPresentationContentSource(prepared)
                            }
                        dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource.OUTBOX -> {
                            val handle = when (val loaded = container.identityRepository.load()) {
                                is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available ->
                                    loaded.encryptionHandle
                                dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.RecoveryRequired ->
                                    error("local keys are unavailable")
                            }
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
                        }
                    }
                    dev.hryshyn.remanence.ui.capsule.CapsuleContentBinding(
                        capsuleId = capsuleId,
                        reader = reader,
                    )
                },
                validateLiveGrant = { rootViewModel.requireLivePresentationGrant(grantId) },
                revocations = rootViewModel.capsuleRevocations,
                onClose = rootViewModel::closeCapsule,
                onFailure = { rootViewModel.revokePresentationForRouteFailure(grantId) },
            )
        },
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
        rootViewModel.openCapsuleWithGrant(
            grantId = granted.grantId,
            onPresentationClosed = scanViewModel::resetSession,
        )
    }
    ScanScreen(
        viewModel = scanViewModel,
        // Activity recreation disposes/recreates Compose but keeps the
        // ViewModel and root destination. Only an actual route exit should
        // tear down the scan; this preserves same-epoch rotation state.
        onScreenDispose = {
            // Disposal also happens for rotation and the Scan -> Capsule
            // handoff. Only a stable non-scan destination means the scan flow
            // was actually left; preserve its generation across rotation and
            // while the capsule route takes ownership of the grant.
            when (rootViewModel.destination.value) {
                AppDestination.Home,
                AppDestination.Authentication,
                AppDestination.Create,
                -> scanViewModel.resetSession()
                AppDestination.Scan,
                is AppDestination.Capsule,
                -> Unit
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
