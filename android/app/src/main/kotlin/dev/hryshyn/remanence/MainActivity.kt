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
import dev.hryshyn.remanence.ui.auth.AccessPage
import dev.hryshyn.remanence.ui.auth.AccessHeading
import dev.hryshyn.remanence.ui.auth.GoogleSignInButton
import dev.hryshyn.remanence.ui.auth.HandleSetupContent
import dev.hryshyn.remanence.ui.auth.AccessLanguageControl
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
            val factory = remember { RemanenceViewModelFactory(container) }
            val rootViewModel: RootViewModel = viewModel(factory = factory)
            val destination by rootViewModel.destination.collectAsStateWithLifecycle()
            HoldTheme {
                // Transparent system bars use the same viewer mat as the
                // capsule, so safe-area insets do not become white stripes.
                Surface(Modifier.fillMaxSize(), color = if (destination is AppDestination.Capsule)
                    dev.hryshyn.remanence.ui.hold.HoldColors.Field else MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                        RootSurface(
                            container = container,
                            rootViewModel = rootViewModel,
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
    rootViewModel: RootViewModel,
    appLocale: AppLocale,
    onLocaleSelected: (AppLocale) -> Unit,
) {
    val factory = remember { RemanenceViewModelFactory(container) }

    // I02/I03: cold-start session bootstrap decides the first surface.
    var recoveryBusy by remember { mutableStateOf(false) }
    // BUG-014: the health epoch advances on every real foreground/resume
    // transition alongside the session refresh, so the check below re-runs.
    var healthCheckEpoch by remember { mutableIntStateOf(0) }
    ForegroundResumeEffect {
        runCatching { dev.hryshyn.remanence.ui.capsule.CapsuleExportFiles.sweep(container.appContext) }
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
            loginSubmit is LoginSubmitState.RecoveryRequired) {
            rootViewModel.onSessionEstablished()
        }
    }
    val registrationSubmit by registrationViewModel.submitState.collectAsStateWithLifecycle()
    LaunchedEffect(registrationSubmit) {
        if (registrationSubmit is RegistrationSubmitState.Completed) {
            rootViewModel.onSessionEstablished()
        }
    }
    // T186 M7a: a committed first-send handle re-resolves the root, which
    // reloads Active WITH the handle and continues into Create via the gate.
    val setupViewModel: dev.hryshyn.remanence.pending.PendingHandleSetupViewModel =
        viewModel(factory = factory)
    val setupState by setupViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(setupState) {
        if (setupState is dev.hryshyn.remanence.pending.PendingHandleSetupViewModel.SetupState.Committed) {
            rootViewModel.onSessionEstablished()
        }
    }

    val authState by rootViewModel.authState.collectAsStateWithLifecycle()
    val destination by rootViewModel.destination.collectAsStateWithLifecycle()

    // Real capability derivation: authenticated AND both keysets on device.
    LaunchedEffect(authState) {
        capabilityViewModel.onAuthStateChanged(authState)
        setupViewModel.onAuthChanged()
    }
    val accountCapability by capabilityViewModel.capability.collectAsStateWithLifecycle()

    val homeIntent by rootViewModel.homeIntent.collectAsStateWithLifecycle()
    var registering by rememberSaveable { mutableStateOf(false) }
    var showLegacyAuth by rememberSaveable { mutableStateOf(!BuildConfig.REMANENCE_GOOGLE_SIGN_IN_ENABLED) }
    var logoutRequested by remember { mutableStateOf(false) }
    BackHandler(authState == AuthUiState.SignedOut && homeIntent != null) {
        // Consume Back while a provider request owns this surface; otherwise
        // the activity may finish and silently cancel its composition scope.
        if (!recoveryBusy && googleState != dev.hryshyn.remanence.auth.GoogleAuthState.Working) {
            if (showLegacyAuth && BuildConfig.REMANENCE_GOOGLE_SIGN_IN_ENABLED) showLegacyAuth = false
            else {
                registering = false
                showLegacyAuth = !BuildConfig.REMANENCE_GOOGLE_SIGN_IN_ENABLED
                rootViewModel.cancelHomeIntent()
            }
        }
    }

    RootScreen(
        showPublicHome = homeIntent == null,
        authState = authState,
        destination = destination,
        authenticationContent = {
            val setupRequired = authState as? AuthUiState.RecoverySetupRequired
            if (setupRequired != null) {
                AccessPage(footer = { AccessLanguageControl(current = appLocale, onSelect = onLocaleSelected) }) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = HoldSpace.Gutter),
                        verticalArrangement = Arrangement.spacedBy(HoldSpace.Section)) {
                        // T186: handleless accounts show no handle row here either.
                        if (setupRequired.handle != null) {
                            Text("\u2068@${setupRequired.handle}\u2069", style = MaterialTheme.typography.labelLarge,
                                color = dev.hryshyn.remanence.ui.hold.HoldColors.Muted)
                        }
                        dev.hryshyn.remanence.recovery.RecoveryActions(container, true, false,
                            rootViewModel::onSessionEstablished, onBusyChange = { recoveryBusy = it }, mandatorySetup = true)
                        HoldInformation(stringResource(R.string.mvp_setup_saved))
                        HoldTextButton(enabled = !recoveryBusy, onClick = rootViewModel::onAppForegrounded) {
                            Text(stringResource(R.string.mvp_retry_connection))
                        }
                        HoldTextButton(enabled = !recoveryBusy, onClick = { logoutRequested = true }) {
                            Text(stringResource(R.string.hold_logout), color = dev.hryshyn.remanence.ui.hold.HoldColors.Destructive)
                        }
                    }
                }
            } else {
                val googleWorking = googleState == dev.hryshyn.remanence.auth.GoogleAuthState.Working
                val failed = googleState as? dev.hryshyn.remanence.auth.GoogleAuthState.Failed
                // Keep RecoveryActions at the same composition site while its
                // dialog is working; moving branches would cancel the operation.
                val legacyVisible = showLegacyAuth && !googleViewModel.hasTicket && !googleWorking &&
                    authState != AuthUiState.RecoveryRequired
                AccessPage(
                    onBack = if (authState == AuthUiState.SignedOut) ({
                        if (showLegacyAuth && BuildConfig.REMANENCE_GOOGLE_SIGN_IN_ENABLED) showLegacyAuth = false
                        else { registering = false; rootViewModel.cancelHomeIntent() }
                    }) else if (authState == AuthUiState.RequiresConnectivity) ({ logoutRequested = true }) else null,
                    backEnabled = !recoveryBusy && !googleWorking,
                    footer = { AccessLanguageControl(current = appLocale, onSelect = onLocaleSelected) },
                ) {
                    if (googleViewModel.hasTicket && authState == AuthUiState.SignedOut) {
                        var handle by rememberSaveable { mutableStateOf("") }
                        if (failed != null) HoldInformation(stringResource(failed.message.resourceId))
                        HoldTextButton(enabled = !googleWorking, onClick = { googleViewModel.start(googleCredentials) }) {
                            Text(stringResource(R.string.access_google_again))
                        }
                        Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = HoldSpace.Group)) {
                            HandleSetupContent(handle, { if (it.length <= 30) handle = it }, googleWorking,
                                onContinue = { googleViewModel.register(handle) })
                        }
                    } else if (legacyVisible) {
                        AccessHeading(stringResource(if (registering) R.string.hold_register_title else R.string.hold_auth_title))
                        if (authState == AuthUiState.RequiresConnectivity) {
                            HoldInformation(stringResource(R.string.access_connection_detail))
                            HoldTextButton(onClick = rootViewModel::onAppForegrounded) { Text(stringResource(R.string.mvp_retry_connection)) }
                        }
                        Spacer(Modifier.height(HoldSpace.Gutter))
                        if (recoveryBusy) {
                            Spacer(Modifier.weight(1f))
                        } else if (registering) {
                            val form by registrationViewModel.form.collectAsStateWithLifecycle()
                            val submit by registrationViewModel.submitState.collectAsStateWithLifecycle()
                            RegistrationFormScreen(form, submit, registrationViewModel::onFieldChange, registrationViewModel::submit,
                                modifier = Modifier.weight(1f).fillMaxWidth())
                            HoldTextButton(onClick = { registering = false }, enabled = submit !is RegistrationSubmitState.Submitting) {
                                Text(stringResource(R.string.hold_existing_account))
                            }
                        } else {
                            val form by loginViewModel.form.collectAsStateWithLifecycle()
                            val submit by loginViewModel.submitState.collectAsStateWithLifecycle()
                            LoginScreen(form, submit, loginViewModel::onEmailChange, loginViewModel::onPasswordChange, loginViewModel::submit,
                                modifier = Modifier.weight(1f).fillMaxWidth())
                            HoldTextButton(onClick = { registering = true }, enabled = submit !is LoginSubmitState.Submitting) {
                                Text(stringResource(R.string.hold_create_account))
                            }
                        }
                        if (!BuildConfig.REMANENCE_GOOGLE_SIGN_IN_ENABLED) dev.hryshyn.remanence.recovery.RecoveryActions(
                            container, hasIdentity = false, recoveryRequired = false,
                            onRecovered = rootViewModel::onSessionEstablished, onBusyChange = { recoveryBusy = it })
                    } else {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = HoldSpace.Gutter),
                            verticalArrangement = Arrangement.spacedBy(HoldSpace.Section)) {
                            AccessHeading(stringResource(if (authState == AuthUiState.RecoveryRequired) R.string.access_restore_title else R.string.hold_auth_title), if (authState == AuthUiState.RecoveryRequired) null else stringResource(when (homeIntent) {
                                HomeIntent.OPEN -> R.string.access_open_detail
                                HomeIntent.MAKE -> R.string.access_make_detail
                                else -> R.string.access_default_detail
                            }))
                            if (authState == AuthUiState.RequiresConnectivity) {
                                HoldInformation(stringResource(R.string.access_connection_detail))
                                HoldTextButton(onClick = rootViewModel::onAppForegrounded) { Text(stringResource(R.string.mvp_retry_connection)) }
                            }
                            if (BuildConfig.REMANENCE_GOOGLE_SIGN_IN_ENABLED && authState == AuthUiState.SignedOut) {
                                GoogleSignInButton(onClick = { googleViewModel.start(googleCredentials) }, enabled = !googleWorking && !recoveryBusy, busy = googleWorking)
                                if (failed != null) Text(stringResource(failed.message.resourceId),
                                    color = dev.hryshyn.remanence.ui.hold.HoldColors.Error, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (!googleWorking) dev.hryshyn.remanence.recovery.RecoveryActions(container, hasIdentity = false,
                                recoveryRequired = authState == AuthUiState.RecoveryRequired, onRecovered = rootViewModel::onSessionEstablished,
                                onBusyChange = { recoveryBusy = it })
                            if (authState == AuthUiState.RecoveryRequired || authState == AuthUiState.RequiresConnectivity) HoldTextButton(enabled = !recoveryBusy, onClick = { logoutRequested = true }) {
                                Text(stringResource(R.string.hold_logout), color = dev.hryshyn.remanence.ui.hold.HoldColors.Destructive)
                            }
                            if (BuildConfig.REMANENCE_GOOGLE_SIGN_IN_ENABLED && authState == AuthUiState.SignedOut && !googleWorking) {
                                HoldTextButton(enabled = !recoveryBusy, onClick = { showLegacyAuth = true }) { Text(stringResource(R.string.mvp_other_sign_in)) }
                            }
                        }
                    }
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
                AuthenticatedHomeChrome(authenticated.handle, { logoutRequested = true }, home,
                    onSettings = rootViewModel::openSettings)
            } else {
                home()
            }
        },
        settingsContent = {
            val signedIn = authState as? AuthUiState.Authenticated
            if (signedIn != null) dev.hryshyn.remanence.ui.settings.SettingsScreen(signedIn.handle, appLocale,
                onLocaleSelected, signedIn.activeKeyBundleId?.let { container.recoveryReadiness.isReady(signedIn.userId, it) } == true,
                rootViewModel::returnToHome, rootViewModel::logout,
                telemetryContent = { dev.hryshyn.remanence.ui.settings.TelemetryPreferences(container.telemetry) },
                safetyContent = {
                    if (BuildConfig.REMANENCE_PLAY_BETA) dev.hryshyn.remanence.ui.settings.BetaSafetyPanel(
                        container.apiStack.safetyRepository, signedIn.userId.toString())
                })
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
                // T186 first-send handle gate: a handleless receive-only
                // account never enters the send flow (no placeholder
                // identity, no silent v1 send). The notice explains and
                // returns; the R17 commit lands in milestone 4.
                val handleGate = dev.hryshyn.remanence.pending.PendingFeatureGate.isEnabled() &&
                    dev.hryshyn.remanence.pending.PendingReceivePolicy.shouldPromptForHandleOnSend(
                        dev.hryshyn.remanence.pending.PendingReceiveProfile(
                            handle = (authState as? AuthUiState.Authenticated)?.handle,
                        ),
                    )
                if (handleGate) {
                    // T186 M7a: real first-send handle form (frozen R17).
                    // Committed refreshes the root above, which re-resolves
                    // Active WITH the handle and continues into Create.
                    // setupViewModel is hoisted at the root surface so the
                    // committed observer fires exactly once per commit.
                    dev.hryshyn.remanence.pending.PendingHandleSetupScreen(
                        viewModel = setupViewModel,
                        onBack = rootViewModel::returnToHome,
                    )
                } else {
                val create: @Composable () -> Unit = {
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
                if (BuildConfig.REMANENCE_PLAY_BETA && authenticatedOwner != null)
                    dev.hryshyn.remanence.ui.settings.BetaTermsGate(container.apiStack.safetyRepository,
                        authenticatedOwner.toString(), { createViewModel.endSession(); rootViewModel.returnToHome() }, content = create,
                        telemetryContent = { dev.hryshyn.remanence.ui.settings.TelemetryPreferences(container.telemetry) })
                else create()
                }
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
                // T186: genuine supported-presentation hook. Nonblocking: the
                // container persists + schedules on its application scope and
                // re-validates grant + owner; display never waits for it.
                onSupportedReady = { capsuleId ->
                    val owner = (authState as? AuthUiState.Authenticated)?.userId
                    if (owner != null) {
                        var grantLive = false
                        try {
                            rootViewModel.requireLivePresentationGrant(grantId)
                            grantLive = true
                        } catch (_: Exception) {
                            grantLive = false
                        }
                        container.notifySupportedPresentation(
                            ownerUserId = owner,
                            capsuleId = capsuleId,
                            grantLive = grantLive,
                            routeReady = true,
                            supported = true,
                            // Exact-byte H derivation from grant-bound published
                            // material lands with the claim-envelope work;
                            // hash-less items stay queued, never sent.
                            publicationHash = null,
                        )
                    }
                },
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
                    // T186 M7b: the receiving intent flows through the SAME
                    // auth + mandatory PRF enrollment gates as every scan
                    // (no bypass); the lookup itself is anonymous budgeted.
                    val pendingLookup: (suspend (String) -> dev.hryshyn.remanence.core.data.network.GiftLookupResult)? =
                        if (dev.hryshyn.remanence.pending.PendingFeatureGate.isEnabled()) {
                            { code -> container.pendingGiftLookupPort.lookup(code) }
                        } else {
                            null
                        }
                    val newProofVm: ((kotlinx.coroutines.CoroutineScope) -> dev.hryshyn.remanence.pending.PendingProofViewModel)? =
                        if (dev.hryshyn.remanence.pending.PendingFeatureGate.isEnabled()) {
                            { scope -> container.newPendingProofViewModel(scope) }
                        } else {
                            null
                        }
                    ScanFlowSurface(
                        rootViewModel = rootViewModel,
                        scanViewModel = scanViewModel,
                        pendingLookup = pendingLookup,
                        newProofVm = newProofVm,
                    )
                }
            } else {
                Text(stringResource(R.string.capsule_audit_preparing_scan), modifier = Modifier.testTag("scan_session_initializing"))
            }
        },
        onExitFlow = rootViewModel::returnToHome,
    )
    // Register after child routes so an activity-level Back cannot dispose a
    // session-bound provider request. The provider sheet owns its own cancel.
    BackHandler(recoveryBusy || googleState == dev.hryshyn.remanence.auth.GoogleAuthState.Working) { }
    if (logoutRequested && authState != AuthUiState.SignedOut) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { logoutRequested = false },
            title = { Text(stringResource(R.string.home_audit_logout_title)) },
            text = { Text(stringResource(R.string.home_audit_logout_detail)) },
            confirmButton = {
                HoldTextButton(enabled = !recoveryBusy, onClick = { logoutRequested = false; rootViewModel.logout() }) {
                    Text(stringResource(R.string.hold_logout))
                }
            },
            dismissButton = { HoldTextButton(onClick = { logoutRequested = false }) { Text(stringResource(R.string.home_audit_logout_cancel)) } },
        )
    }
}

/**
 * FIX-M1-007-12: the live scan surface. A verified grant navigates through
 * the guarded capsule route - only while this surface is composed.
 */
@Composable
private fun ScanFlowSurface(
    rootViewModel: RootViewModel,
    scanViewModel: ScanViewModel,
    /**
     * T186 M7b: real R6 lookup entry (anonymous, budgeted). Null keeps the
     * fail-closed sheet. Production passes the container port when the
     * pending flag is on.
     */
    pendingLookup: (suspend (String) -> dev.hryshyn.remanence.core.data.network.GiftLookupResult)? = null,
    /**
     * T186 M7c: proof-flow factory (flow-scoped VM, sheet scope). Null keeps
     * lookup-only mode.
     */
    newProofVm: ((kotlinx.coroutines.CoroutineScope) -> dev.hryshyn.remanence.pending.PendingProofViewModel)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(scanViewModel) {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = scanViewModel.refreshPendingConnectivity()
            override fun onLost(network: android.net.Network) = scanViewModel.refreshPendingConnectivity()
            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) =
                scanViewModel.refreshPendingConnectivity()
        }
        val registered = runCatching { manager?.registerDefaultNetworkCallback(callback); manager != null }.getOrDefault(false)
        onDispose { if (registered) runCatching { manager?.unregisterNetworkCallback(callback) } }
    }
    ForegroundResumeEffect { scanViewModel.refreshPendingConnectivity() }
    val terminal by scanViewModel.terminal.collectAsStateWithLifecycle()
    LaunchedEffect(terminal) {
        val granted = terminal as? ScanTerminalState.Granted ?: return@LaunchedEffect
        // FIX-REVIEW-03: only the random grant ID travels; the root resolves
        // the capsule ID through THE authoritative grant manager itself.
        val opened = rootViewModel.openCapsuleWithGrant(
            grantId = granted.grantId,
            onPresentationClosed = scanViewModel::resetSession,
        )
        if (!opened) scanViewModel.resetSession()
    }
    ScanScreen(
        viewModel = scanViewModel,
        pendingLookup = pendingLookup,
        newProofVm = newProofVm,
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
