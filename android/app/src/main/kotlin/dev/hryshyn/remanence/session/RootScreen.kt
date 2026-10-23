package dev.hryshyn.remanence.session

import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import dev.hryshyn.remanence.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import dev.hryshyn.remanence.ui.hold.HoldTextButton as OutlinedButton
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.ui.motion.AstraCarryFront
import dev.hryshyn.remanence.ui.motion.AstraOpeningCarry
import dev.hryshyn.remanence.ui.motion.LocalAstraCarryFront
import dev.hryshyn.remanence.ui.scan.AstraPostcard
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import dev.hryshyn.remanence.ui.motion.AstraCrossfade
import dev.hryshyn.remanence.ui.motion.rememberAstraMotion

/**
 * I03/FIX-M1-007-10 root renderer: picks exactly one surface from the guarded
 * set. Authentication hosts login/registration slots; authenticated users
 * land on Home and can reach Create, Scan and outgoing metadata (plus the
 * grant-gated capsule presentation). There is deliberately no gallery,
 * inbox or feed. Outgoing history is metadata only, never capsule content.
 */
@Composable
fun RootScreen(
    authState: AuthUiState,
    destination: AppDestination,
    authenticationContent: @Composable () -> Unit,
    homeContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    createContent: @Composable () -> Unit = {},
    scanContent: @Composable () -> Unit = {},
    capsuleContent: @Composable (grantId: String) -> Unit = {},
    onExitFlow: () -> Unit = {},
    showPublicHome: Boolean = false,
    settingsContent: @Composable () -> Unit = {},
    historyContent: @Composable () -> Unit = {},
    onOpenSettings: (() -> Unit)? = null,
    onOpenHistory: (() -> Unit)? = null,
) {
    // One transition per boundary, owned by the root: the whole surface
    // cross-dissolves from its last frame, so auth, shell and flows all move
    // the same way. Signing out never fades — private pixels end with the
    // session.
    val motion = rememberAstraMotion()
    val boundary: Any = when {
        authState is AuthUiState.Resolving -> RootBoundary.RESOLVING
        authState == AuthUiState.SignedOut && showPublicHome -> RootBoundary.PUBLIC_HOME
        authState !is AuthUiState.Authenticated -> authState::class
        else -> destination
    }
    AstraCrossfade(
        motion = motion,
        contentKey = boundary,
        modifier = modifier.fillMaxSize(),
        animate = { from, to ->
            val leavingSession = from is AppDestination && to !is AppDestination
            // Scan -> Capsule carries the postcard itself; a dissolve on top
            // would double the movement.
            val carried = from == AppDestination.Scan && (to is AppDestination.Capsule)
            !(leavingSession || carried)
        },
    ) {
        RootSurface(
            authState, destination, authenticationContent, homeContent, createContent, scanContent,
            capsuleContent, onExitFlow, showPublicHome, settingsContent, historyContent, onOpenSettings, onOpenHistory,
        )
    }
}

private enum class RootBoundary { RESOLVING, PUBLIC_HOME }

@Composable
private fun RootSurface(
    authState: AuthUiState,
    destination: AppDestination,
    authenticationContent: @Composable () -> Unit,
    homeContent: @Composable () -> Unit,
    createContent: @Composable () -> Unit,
    scanContent: @Composable () -> Unit,
    capsuleContent: @Composable (grantId: String) -> Unit,
    onExitFlow: () -> Unit,
    showPublicHome: Boolean,
    settingsContent: @Composable () -> Unit,
    historyContent: @Composable () -> Unit,
    onOpenSettings: (() -> Unit)?,
    onOpenHistory: (() -> Unit)?,
) {
    val modifier = Modifier
    if (authState is AuthUiState.Resolving) {
        // Cold-start session check still in flight: hold a neutral frame so
        // neither the auth form nor any home surface flashes for a frame.
        // Replaced wholesale the moment the first terminal state publishes
        // (no timers); holds no private content by construction.
        Box(modifier.fillMaxSize().testTag("root_resolving_frame"))
        return
    }
    if (authState == AuthUiState.SignedOut && showPublicHome) {
        Box(modifier.fillMaxSize()) { homeContent() }
        return
    }
    if (authState !is AuthUiState.Authenticated) {
        // Auth fills the remaining height so the form can pin submit above IME.
        Column(modifier = modifier.fillMaxSize()) {
            if (authState is AuthUiState.RecoveryRequired) {
                Text(
                    androidx.compose.ui.res.stringResource(dev.hryshyn.remanence.R.string.home_audit_recovery_required),
                    modifier = Modifier.padding(16.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                authenticationContent()
            }
        }
        return
    }

    BackHandler(
        enabled = destination == AppDestination.Create || destination == AppDestination.Scan || destination == AppDestination.Settings,
        onBack = onExitFlow,
    )

    val section = when (destination) {
        AppDestination.Home -> dev.hryshyn.remanence.ui.hold.ProductSection.HOME
        AppDestination.History -> dev.hryshyn.remanence.ui.hold.ProductSection.SENT
        AppDestination.Settings -> dev.hryshyn.remanence.ui.hold.ProductSection.SETTINGS
        else -> null
    }
    if (section != null && onOpenSettings != null && onOpenHistory != null) {
        dev.hryshyn.remanence.ui.hold.ProductFrame(section, onExitFlow, onOpenHistory, onOpenSettings) {
            key(destination) {
                when (destination) {
                    AppDestination.Settings -> settingsContent()
                    AppDestination.History -> historyContent()
                    else -> homeContent()
                }
            }
        }
        return
    }

    // Opening-Carry front holder: remembered outside key(destination) so the
    // Scan branch's disposal cannot wipe the published postcard bounds before
    // the Capsule entry consumes them.
    val carryFront = remember { AstraCarryFront() }
    // Stop-scan and other non-scan/non-capsule destinations must not leave a
    // stale rect behind for a later opening.
    SideEffect {
        if (destination != AppDestination.Scan && destination !is AppDestination.Capsule) {
            carryFront.clear()
        }
    }

    CompositionLocalProvider(LocalAstraCarryFront provides carryFront) {
    when (destination) {
        AppDestination.Settings -> key(destination) { settingsContent() }
        AppDestination.History -> key(destination) { historyContent() }
        // Create owns its step-aware back arrow and pinned close control.
        // The root owns only the route boundary and session lifecycle.
        AppDestination.Create -> key(destination) { Box(modifier.fillMaxSize()) { createContent() } }

        AppDestination.Scan -> key(destination) {
            Column(modifier.fillMaxSize()) {
                dev.hryshyn.remanence.ui.hold.HoldFlowHeader(onBack = onExitFlow, onClose = onExitFlow, testKey = "scan")
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    scanContent()
                }
            }
        }

        is AppDestination.Capsule ->
            // Scan-accepted opening: the published postcard rect carries into
            // the capsule through one overlapping movement. Without a rect
            // (rotation, restart, direct entry) the root dissolve applies.
            // Keyed by grant: each grant arrives exactly once.
            key(destination) {
                val front = remember(destination) {
                    carryFront.consumeIf(destination is AppDestination.Capsule)
                }
                val face = remember(destination) {
                    carryFront.consumeFaceIf(destination is AppDestination.Capsule)
                }
                val openingMotion = rememberAstraMotion()
                if (front != null && face != null && openingMotion == dev.hryshyn.remanence.ui.motion.AstraMotionSpec.Resolved.FULL) {
                    // The recipient's own card turns over; its back is the capsule.
                    dev.hryshyn.remanence.ui.motion.AstraPostcardFlip(
                        front = front,
                        face = face,
                        backdropColor = MaterialTheme.colorScheme.background,
                        modifier = modifier.fillMaxSize(),
                    ) {
                        capsuleContent(destination.grantId)
                    }
                } else if (front != null) {
                    AstraOpeningCarry(
                        motion = rememberAstraMotion(),
                        modifier = modifier.fillMaxSize(),
                        front = front,
                        backdropColor = MaterialTheme.colorScheme.background,
                        frontContent = { AstraPostcard(Modifier.fillMaxSize()) },
                    ) {
                        capsuleContent(destination.grantId)
                    }
                } else {
                    Box(modifier.fillMaxSize()) { capsuleContent(destination.grantId) }
                }
            }

        // Until a live grant exists, Home remains the fallback surface.
        else -> key(destination) { Box(modifier.fillMaxSize()) { homeContent() } }
    }
    }
}

/** Shared exit chrome for the two reachable flows; leaving drops flow state. */
@Composable
private fun FlowHeader(
    @StringRes title: Int,
    testKey: String,
    onExit: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onExit, modifier = Modifier.testTag("flow_exit_$testKey")) {
            Text(stringResource(R.string.hold_back_to_home))
        }
        Spacer(Modifier.weight(1f))
        Text(stringResource(title),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(end = 12.dp).testTag("flow_title_$testKey"))
    }
}

/** Home chrome including the logout action, rendered for authenticated users. */
@Composable
fun AuthenticatedHomeChrome(
    handle: String?,
    onLogout: () -> Unit,
    homeContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    recoveryContent: @Composable () -> Unit = {},
    onSettings: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
) {
    if (onSettings != null && onHistory != null) {
        dev.hryshyn.remanence.ui.hold.ProductFrame(
            dev.hryshyn.remanence.ui.hold.ProductSection.HOME, {}, onHistory, onSettings, homeContent)
        return
    }
    Column(modifier = modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            // T186: handleless receive-only accounts show no handle row —
            // never an empty "@" placeholder.
            if (handle != null) {
                Text("\u2068@$handle\u2069", modifier = Modifier.weight(1f).testTag("root_signed_in_as"), style = MaterialTheme.typography.labelMedium)
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (onSettings == null) {
                OutlinedButton(onClick = onLogout, modifier = Modifier.testTag("root_logout_button")) { Text(stringResource(R.string.hold_logout)) }
            } else {
                OutlinedButton(onClick = onSettings, modifier = Modifier.testTag("root_settings_button")) { Text(stringResource(R.string.mvp_settings)) }
            }
        }
        recoveryContent()
        if (onHistory != null) OutlinedButton(onClick = onHistory,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("root_history_button")) {
            Text(stringResource(R.string.history_title))
        }
        Box(Modifier.weight(1f)) { homeContent() }
    }
}
