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
import dev.hryshyn.remanence.ui.motion.AstraContextArrival
import dev.hryshyn.remanence.ui.motion.rememberAstraMotion

/**
 * I03/FIX-M1-007-10 root renderer: picks exactly one surface from the guarded
 * set. Authentication hosts login/registration slots; authenticated users
 * land on Home and can reach ONLY the Create and Scan entry points (plus the
 * grant-gated capsule presentation). There is deliberately no gallery,
 * inbox, history, or feed surface anywhere in this hierarchy.
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
) {
    if (authState is AuthUiState.Resolving) {
        // Cold-start session check still in flight: hold a neutral frame so
        // neither the auth form nor any home surface flashes for a frame.
        // Replaced wholesale the moment the first terminal state publishes
        // (no timers); holds no private content by construction.
        Box(modifier.fillMaxSize().testTag("root_resolving_frame"))
        return
    }
    if (authState == AuthUiState.SignedOut && showPublicHome) {
        homeContent()
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

    val motion = rememberAstraMotion()

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
        AppDestination.Settings -> key(destination) {
            AstraContextArrival(motion, modifier.fillMaxSize(), contentKey = destination) { settingsContent() }
        }
        // Create owns its step-aware back arrow and pinned close control.
        // The root owns only the route boundary and session lifecycle.
        AppDestination.Create -> key(destination) {
            AstraContextArrival(motion = motion, modifier = modifier.fillMaxSize(), contentKey = destination) {
                createContent()
            }
        }

        AppDestination.Scan -> key(destination) {
            AstraContextArrival(motion = motion, modifier = modifier.fillMaxSize(), contentKey = destination) {
                Column(Modifier.fillMaxSize()) {
                    dev.hryshyn.remanence.ui.hold.HoldFlowHeader(onBack = onExitFlow, onClose = onExitFlow, testKey = "scan")
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        scanContent()
                    }
                }
            }
        }

        is AppDestination.Capsule ->
            // Scan-accepted opening: the published postcard rect carries into
            // the capsule through one overlapping movement. Without a rect
            // (rotation, restart, direct entry) this falls back to the plain
            // boundary arrival. A capsule opening carries like any other route
            // boundary; the arrival is incoming-only, so no private outgoing
            // node is ever retained. Keyed by grant: each grant arrives
            // exactly once.
            key(destination) {
                val front = remember(destination) {
                    carryFront.consumeIf(destination is AppDestination.Capsule)
                }
                if (front != null) {
                    AstraOpeningCarry(
                        motion = motion,
                        modifier = modifier.fillMaxSize(),
                        front = front,
                        backdropColor = MaterialTheme.colorScheme.background,
                        frontContent = { AstraPostcard(Modifier.fillMaxSize()) },
                    ) {
                        capsuleContent(destination.grantId)
                    }
                } else {
                    AstraContextArrival(motion = motion, modifier = modifier.fillMaxSize(), contentKey = destination) {
                        capsuleContent(destination.grantId)
                    }
                }
            }

        // Until a live grant exists, Home remains the fallback surface.
        // Home owns the return arrival so Back to Home carries like any
        // other boundary; the outgoing flow is dropped synchronously.
        else -> key(destination) {
            AstraContextArrival(motion = motion, modifier = modifier.fillMaxSize(), contentKey = destination) {
                homeContent()
            }
        }
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
) {
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
        Box(Modifier.weight(1f)) { homeContent() }
    }
}
