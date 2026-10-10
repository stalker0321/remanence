package dev.hryshyn.remanence

import androidx.compose.ui.res.stringResource
import dev.hryshyn.remanence.R
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.ui.hold.HoldTextButton
import dev.hryshyn.remanence.ui.hold.HoldInformation
import dev.hryshyn.remanence.ui.hold.HoldSpace
import dev.hryshyn.remanence.session.HomeIntent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
import dev.hryshyn.remanence.ui.home.AccountCapabilityState
import dev.hryshyn.remanence.ui.home.HomeCapabilityViewModel
import dev.hryshyn.remanence.ui.home.HomeScreen
import dev.hryshyn.remanence.wiring.RemanenceViewModelFactory
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.core.data.network.HealthCheckResult
import dev.hryshyn.remanence.ui.locale.AppLocale
import dev.hryshyn.remanence.ui.locale.AppLocaleController
import dev.hryshyn.remanence.ui.locale.LanguageSwitchRow

/**
 * AppCompatActivity (not ComponentActivity): the AppCompat per-app locale
 * backport only functions with an attached AppCompat delegate, and Compose
 * + setApplicationLocales officially requires AppCompatActivity. The delegate
 * applies and restores the stored locales; no manual context wrapping.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        val container = (application as RemanenceApplication).container

        setContent {
            // Hoisted here (not in a ViewModel): the locale switch recreates
            // only this Activity, so ViewModels, auth state and capsule grants
            // survive; the persisted tag restores the choice after restart.
            var appLocale by remember {
                mutableStateOf(container.appLocaleRepository.load())
            }
            HoldTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                        RootSurface(
                            container = container,
                            appLocale = appLocale,
                            onLocaleSelected = { locale ->
                                appLocale = AppLocaleController.select(locale)
                            },
                        )
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
 * BUG-014: backend-health ownership for the root surface. The check itself is
 * the existing `HealthRepository.check()`; this holder only owns the current
 * UI state plus re-running it. It is deliberately re-runnable (unlike the old
 * `LaunchedEffect(Unit)` single shot) so a transient outage at cold start no
 * longer latches `UNAVAILABLE` for the process lifetime. Plain state holder:
 * single-flight comes from the restarting effect, not from here.
 */
internal class BackendHealthMonitor(
    private val checkHealth: suspend () -> HealthCheckResult,
) {
    var state: BackendHealthUiState by mutableStateOf(BackendHealthUiState.CHECKING)
        private set

    suspend fun recheck() {
        state = when (checkHealth()) {
            is HealthCheckResult.Available -> BackendHealthUiState.AVAILABLE
            else -> BackendHealthUiState.UNAVAILABLE
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
private fun RootSurface(
    container: AppContainer,
    appLocale: AppLocale,
    onLocaleSelected: (AppLocale) -> Unit,
) {
    val factory = remember { RemanenceViewModelFactory(container) }

    // I02/I03: cold-start session bootstrap decides the first surface.
    val rootViewModel: RootViewModel = viewModel(factory = factory)
    var recoveryBusy by remember { mutableStateOf(false) }
    // BUG-014: the health epoch advances on every real foreground/resume
    // transition alongside the session refresh, so the check below re-runs.
    var healthCheckEpoch by remember { mutableIntStateOf(0) }
    ForegroundResumeEffect {
        // Returning from the Credential Manager dialog must not rotate the
        // session underneath its session-bound registration/setup challenge.
        if (!recoveryBusy) rootViewModel.onAppForegrounded()
        healthCheckEpoch++
    }
    val loginViewModel: LoginViewModel = viewModel(factory = factory)
    val registrationViewModel: RegistrationViewModel = viewModel(factory = factory)
    val googleViewModel: dev.hryshyn.remanence.auth.GoogleAuthViewModel = viewModel(factory = factory)
    val googleState by googleViewModel.state.collectAsStateWithLifecycle()
    val activity = androidx.compose.ui.platform.LocalContext.current as android.app.Activity
    val googleCredentials = remember(activity) { dev.hryshyn.remanence.auth.AndroidGoogleCredentials(activity) }
    LaunchedEffect(googleState) {
        if (googleState == dev.hryshyn.remanence.auth.GoogleAuthState.SignedIn) rootViewModel.onSessionEstablished()
    }
    val capabilityViewModel: HomeCapabilityViewModel = viewModel(factory = factory)

    val healthMonitor = remember { BackendHealthMonitor(container.healthRepository::check) }
    // BUG-014: backend health is re-checked on every real foreground/resume
    // transition, not just once at cold start. Restarting the effect on the
    // epoch cancels any in-flight check, so at most one runs (single-flight);
    // no timer, no connectivity listener.
    LaunchedEffect(healthCheckEpoch) {
        healthMonitor.recheck()
    }

    // The root re-resolves after a terminal submit flow and on foreground.
    val loginSubmit by loginViewModel.submitState.collectAsStateWithLifecycle()
    LaunchedEffect(loginSubmit) {
        if (loginSubmit is LoginSubmitState.LoggedIn ||
            (BuildConfig.REMANENCE_RECOVERY_ENABLED && loginSubmit is LoginSubmitState.RecoveryRequired)) {
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
    var showLegacyAuth by rememberSaveable { mutableStateOf(!BuildConfig.REMANENCE_GOOGLE_SIGN_IN_ENABLED) }
    BackHandler(authState == AuthUiState.SignedOut && homeIntent != null) {
        registering = false
        rootViewModel.cancelHomeIntent()
    }

    RootScreen(
        showPublicHome = homeIntent == null,
        authState = authState,
        destination = destination,
        authenticationContent = {
            val setupRequired = authState as? AuthUiState.RecoverySetupRequired
            if (setupRequired != null) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(HoldSpace.Gutter), verticalArrangement = Arrangement.spacedBy(HoldSpace.Group)) {
                    Text("@${setupRequired.handle}")
                    HoldInformation(stringResource(R.string.mvp_setup_saved))
                    dev.hryshyn.remanence.recovery.RecoveryActions(container, true, false,
                        rootViewModel::onSessionEstablished, onBusyChange = { recoveryBusy = it }, mandatorySetup = true)
                    HoldTextButton(enabled = !recoveryBusy, onClick = rootViewModel::onAppForegrounded) {
                        Text(stringResource(R.string.mvp_retry_connection))
                    }
                    LanguageSwitchRow(current = appLocale, onSelect = onLocaleSelected)
                    HoldTextButton(enabled = !recoveryBusy, onClick = rootViewModel::logout) { Text(stringResource(R.string.hold_logout)) }
                }
            } else {
            Column(
                Modifier.fillMaxSize().padding(horizontal = HoldSpace.pageGutter(), vertical = HoldSpace.Gutter),
                verticalArrangement = Arrangement.spacedBy(HoldSpace.Group),
            ) {
                if (authState == AuthUiState.SignedOut) {
                    HoldTextButton(onClick = { registering = false; rootViewModel.cancelHomeIntent() }) { Text(stringResource(R.string.hold_back)) }
                }
                Text(if (registering) stringResource(R.string.hold_register_title) else stringResource(R.string.hold_auth_title), style = MaterialTheme.typography.headlineLarge)
                HoldInformation(when (homeIntent) {
                    HomeIntent.OPEN -> "Sign in to open the remanence meant for you."
                    HomeIntent.MAKE -> "Sign in to leave a memory for someone."
                    else -> "Your account keeps each remanence with the person it was meant for."
                })
                if (authState == AuthUiState.RequiresConnectivity) {
                    HoldInformation("Connect to the internet so we can check your session.")
                    HoldTextButton(onClick = rootViewModel::onAppForegrounded) { Text("try connection again") }
                }
                if (BuildConfig.REMANENCE_GOOGLE_SIGN_IN_ENABLED && authState == AuthUiState.SignedOut) {
                    HoldTextButton(enabled = googleState != dev.hryshyn.remanence.auth.GoogleAuthState.Working && !recoveryBusy,
                        onClick = { googleViewModel.start(googleCredentials) }) { Text(stringResource(R.string.mvp_google_continue)) }
                    if (googleState == dev.hryshyn.remanence.auth.GoogleAuthState.Working) Text(stringResource(R.string.mvp_working))
                    val failed = googleState as? dev.hryshyn.remanence.auth.GoogleAuthState.Failed
                    if (failed != null) Text(stringResource(if (failed.conflict) R.string.mvp_google_conflict else R.string.mvp_google_retry))
                    if (googleViewModel.hasTicket) {
                        var handle by rememberSaveable { mutableStateOf("") }
                        Text(stringResource(R.string.mvp_handle_title))
                        androidx.compose.material3.OutlinedTextField(value = handle,
                            onValueChange = { if (it.length <= 30) handle = it }, singleLine = true,
                            label = { Text(stringResource(R.string.mvp_handle_label)) })
                        Text(stringResource(R.string.mvp_handle_permanent))
                        HoldTextButton(enabled = googleState != dev.hryshyn.remanence.auth.GoogleAuthState.Working &&
                            handle.isNotEmpty() && dev.hryshyn.remanence.ui.auth.RegistrationFormValidator.handleError(handle) == null,
                            onClick = { googleViewModel.register(handle) }) { Text(stringResource(R.string.mvp_continue)) }
                    }
                }
                if (!googleViewModel.hasTicket && googleState != dev.hryshyn.remanence.auth.GoogleAuthState.Working) dev.hryshyn.remanence.recovery.RecoveryActions(
                    container, hasIdentity = false,
                    recoveryRequired = authState == AuthUiState.RecoveryRequired,
                    onRecovered = rootViewModel::onSessionEstablished,
                    onBusyChange = { recoveryBusy = it },
                )
                if (authState == AuthUiState.RecoveryRequired) HoldTextButton(enabled = !recoveryBusy, onClick = rootViewModel::logout) {
                    Text(stringResource(R.string.hold_logout))
                }
                if (BuildConfig.REMANENCE_GOOGLE_SIGN_IN_ENABLED && authState == AuthUiState.SignedOut &&
                    !googleViewModel.hasTicket && googleState != dev.hryshyn.remanence.auth.GoogleAuthState.Working) {
                    HoldTextButton(onClick = { showLegacyAuth = !showLegacyAuth }) { Text(stringResource(R.string.mvp_other_sign_in)) }
                }
                if (!showLegacyAuth || recoveryBusy || googleViewModel.hasTicket || googleState == dev.hryshyn.remanence.auth.GoogleAuthState.Working) {
                    Spacer(Modifier.weight(1f))
                } else if (registering) {
                    val form by registrationViewModel.form.collectAsStateWithLifecycle()
                    val submit by registrationViewModel.submitState.collectAsStateWithLifecycle()
                    RegistrationFormScreen(
                        form, submit, registrationViewModel::onFieldChange, registrationViewModel::submit,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    HoldTextButton(onClick = { registering = false }, enabled = submit !is RegistrationSubmitState.Submitting) { Text(stringResource(R.string.hold_existing_account)) }
                } else {
                    val form by loginViewModel.form.collectAsStateWithLifecycle()
                    val submit by loginViewModel.submitState.collectAsStateWithLifecycle()
                    LoginScreen(
                        form, submit, loginViewModel::onEmailChange, loginViewModel::onPasswordChange, loginViewModel::submit,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    HoldTextButton(onClick = { registering = true }, enabled = submit !is LoginSubmitState.Submitting) { Text(stringResource(R.string.hold_create_account)) }
                }
                LanguageSwitchRow(current = appLocale, onSelect = onLocaleSelected)
            }
            }
        },
        homeContent = {
            val authenticated = authState as? AuthUiState.Authenticated
            // The recovery note is decided on the same frame this surface
            // appears: auth state is synchronous, capability resolves a
            // frame later. Without the union the note would pop in late and
            // shift the action cards after first paint.
            val showRecoveryNote = authState == AuthUiState.RecoveryRequired ||
                accountCapability == AccountCapabilityState.RecoveryRequired
            val home: @Composable () -> Unit = {
                HomeScreen(
                    state = healthMonitor.state, accountCapability = accountCapability,
                    publicEntry = authState == AuthUiState.SignedOut,
                    onCreate = { rootViewModel.requestHomeIntent(HomeIntent.MAKE) },
                    onScan = { rootViewModel.requestHomeIntent(HomeIntent.OPEN) },
                    appLocale = appLocale,
                    onLocaleSelected = onLocaleSelected,
                    showLanguageSwitch = authenticated == null,
                    showRecoveryNote = showRecoveryNote,
                )
            }
            if (authenticated != null) {
                AuthenticatedHomeChrome(authenticated.handle, rootViewModel::logout, home,
                    onSettings = rootViewModel::openSettings)
            } else {
                home()
            }
        },
        settingsContent = {
            val signedIn = authState as? AuthUiState.Authenticated
            if (signedIn != null) dev.hryshyn.remanence.ui.settings.SettingsScreen(signedIn.handle, appLocale,
                onLocaleSelected, signedIn.activeKeyBundleId?.let { container.recoveryReadiness.isReady(signedIn.userId, it) } == true,
                rootViewModel::returnToHome, rootViewModel::logout)
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
                    onExit = rootViewModel::returnToHome,
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
                AppDestination.Settings,
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
