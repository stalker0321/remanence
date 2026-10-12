package dev.hryshyn.remanence.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.data.network.SafetyRepository
import dev.hryshyn.remanence.core.data.network.SafetyState
import dev.hryshyn.remanence.ui.hold.*
import dev.hryshyn.remanence.ui.auth.AccessPage
import dev.hryshyn.remanence.ui.auth.AccessHeading
import kotlinx.coroutines.launch

internal const val SUPPORT_EMAIL = "hryshyn.dev@gmail.com"

internal fun Context.openRemanenceLink(path: String): Boolean = try {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.API_BASE_URL + "legal/" + path)))
    true
} catch (_: android.content.ActivityNotFoundException) { false }

internal fun Context.requestAccountDeletion(handle: String): Boolean = try {
    val uri = Uri.parse("mailto:$SUPPORT_EMAIL").buildUpon()
        .appendQueryParameter("subject", "Remanence account deletion")
        .appendQueryParameter("body", "Please delete my Remanence account @$handle and its associated data.\nI understand that copies already received or exported by others cannot be erased remotely.\n")
        .build()
    startActivity(Intent(Intent.ACTION_SENDTO, uri))
    true
} catch (_: android.content.ActivityNotFoundException) { openRemanenceLink("delete-account") }

@Composable
internal fun BetaLegalLinks(handle: String) {
    // These pages belong to the separately deployed beta backend. Do not expose
    // dead legal links in the existing recovery-test APK while it is unchanged.
    if (!BuildConfig.REMANENCE_PLAY_BETA) return
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HoldSpace.Small)) {
        SettingsActionRow(stringResource(R.string.beta_privacy_policy), onClick = { failed = !context.openRemanenceLink("privacy") }, modifier = Modifier.testTag("settings_privacy_policy"))
        SettingsActionRow(stringResource(R.string.beta_terms), onClick = { failed = !context.openRemanenceLink("terms") }, modifier = Modifier.testTag("settings_terms"))
        SettingsActionRow(stringResource(R.string.beta_delete_account), onClick = { failed = !context.requestAccountDeletion(handle) }, modifier = Modifier.testTag("settings_delete_account"), destructive = true)
        Text(SUPPORT_EMAIL, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = HoldColors.Muted, style = MaterialTheme.typography.bodySmall)
        if (failed) Text(stringResource(R.string.beta_external_failed), color = HoldColors.Destructive)
    }
}

/** Mandatory beta UGC consent before entering Create. Offline/missing API stays closed. */
@Composable
internal fun BetaTermsGate(repository: SafetyRepository, owner: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    BetaTermsGateWithActions(owner, repository::state, repository::acceptTerms,
        { context.openRemanenceLink(it) }, onBack, content)
}

/** Scriptable UI boundary; production callbacks remain the fenced repository. */
@Composable
internal fun BetaTermsGateWithActions(owner: String, fetchState: suspend () -> SafetyState?,
    acceptTerms: suspend () -> Boolean, openLink: (String) -> Boolean,
    onBack: () -> Unit, content: @Composable () -> Unit) {
    // Saveable inputs alone are not checked when restoring a saved instance.
    // Include the owner in the composition identity, not just remember inputs.
    key(owner) { BetaTermsOwnerContent(owner, fetchState, acceptTerms, openLink, onBack, content) }
}

@Composable
private fun BetaTermsOwnerContent(owner: String, fetchState: suspend () -> SafetyState?,
    acceptTerms: suspend () -> Boolean, openLink: (String) -> Boolean,
    onBack: () -> Unit, content: @Composable () -> Unit) {
    var state by remember(owner) { mutableStateOf<SafetyState?>(null) }
    var busy by remember(owner) { mutableStateOf(true) }
    var failed by remember(owner) { mutableStateOf(false) }
    var linkFailed by remember(owner) { mutableStateOf(false) }
    // Only local form input is restored. Never persist an acceptance verdict:
    // the repository must confirm consent again after recreation.
    var agreed by rememberSaveable(owner) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(owner) { state = fetchState(); failed = state == null; busy = false }
    if (state?.terms_accepted == true) { content(); return }
    // A pending lookup is not an unaccepted user. Previously the full consent
    // form flashed on every Home -> Create entry, then vanished after the HTTP
    // response, making the route look like two abrupt transitions.
    if (busy && state == null && !failed) {
        BetaTermsPendingPage(onBack)
        return
    }
    BetaTermsPage(agreed = agreed, busy = busy, available = state != null, failed = failed,
        onAgree = { agreed = it }, onBack = onBack,
        onTerms = { linkFailed = !openLink("terms") },
        onPrivacy = { linkFailed = !openLink("privacy") },
        onRetry = {
            if (!busy) {
                busy = true
                scope.launch { state = fetchState(); failed = state == null; busy = false }
            }
        },
        onContinue = {
            if (!busy && agreed && state != null) {
                busy = true; scope.launch {
                    if (acceptTerms()) { state = fetchState(); failed = state?.terms_accepted != true } else failed = true
                    busy = false
                }
            }
        }, linkFailed = linkFailed)
}

@Composable
internal fun BetaTermsPendingPage(onBack: () -> Unit) {
    AccessPage(onBack = onBack) {
        Box(Modifier.fillMaxSize().testTag("beta_terms_pending"),
            contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator(color = HoldColors.Accent, strokeWidth = 2.dp)
        }
    }
}

/** Shared testable layout; the repository alone decides consent is saved. */
@Composable
internal fun BetaTermsPage(agreed: Boolean, busy: Boolean, available: Boolean, failed: Boolean,
    onAgree: (Boolean) -> Unit, onBack: () -> Unit, onTerms: () -> Unit,
    onPrivacy: () -> Unit, onRetry: () -> Unit, onContinue: () -> Unit,
    linkFailed: Boolean = false) {
    AccessPage(onBack = onBack) {
      HoldFormScaffold(modifier = Modifier.padding(bottom = 16.dp), primary = {
        HoldButton(enabled = agreed && available && !busy, onClick = onContinue,
            modifier = Modifier.fillMaxWidth().testTag("beta_terms_continue")) { Text(stringResource(R.string.mvp_continue)) }
      }) {
        AccessHeading(stringResource(R.string.beta_terms_title), stringResource(R.string.beta_terms_summary))
        Spacer(Modifier.height(8.dp))
        SettingsGroup {
            SettingsActionRow(stringResource(R.string.beta_terms), onClick = onTerms, modifier = Modifier.testTag("beta_terms_link"))
            SettingsActionRow(stringResource(R.string.beta_privacy_policy), onClick = onPrivacy, modifier = Modifier.testTag("beta_privacy_link"))
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("beta_terms_agreement")
            .semantics(mergeDescendants = true) {}
            .toggleable(value = agreed, enabled = !busy, role = Role.Checkbox, onValueChange = onAgree)
            .padding(vertical = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = agreed, onCheckedChange = null, enabled = !busy)
            Text(stringResource(R.string.beta_terms_agree), Modifier.weight(1f))
        }
        if (busy) CircularProgressIndicator()
        if (linkFailed) Text(stringResource(R.string.beta_external_failed),
            Modifier.testTag("beta_terms_link_failed"), color = HoldColors.Destructive)
        if (failed) {
            Text(stringResource(R.string.beta_network_failed), color = HoldColors.Destructive)
            HoldTextButton(enabled = !busy, onClick = onRetry, modifier = Modifier.testTag("beta_terms_retry")) {
                Text(stringResource(R.string.hold_retry))
            }
        }
      }
    }
}

@Composable
internal fun BetaSafetyPanel(repository: SafetyRepository, owner: String) {
    BetaSafetyPanelWithActions(owner, repository::state, repository::block,
        repository::report, repository::unblock)
}

@Composable
internal fun BetaSafetyPanelWithActions(owner: String, fetchState: suspend () -> SafetyState?,
    block: suspend (String) -> Boolean, report: suspend (String, String) -> Boolean,
    unblock: suspend (String) -> Boolean) {
    key(owner) { BetaSafetyOwnerContent(owner, fetchState, block, report, unblock) }
}

@Composable
private fun BetaSafetyOwnerContent(owner: String, fetchState: suspend () -> SafetyState?,
    block: suspend (String) -> Boolean, report: suspend (String, String) -> Boolean,
    unblock: suspend (String) -> Boolean) {
    var state by remember(owner) { mutableStateOf<SafetyState?>(null) }
    var handle by rememberSaveable(owner) { mutableStateOf("") }
    var reason by rememberSaveable(owner) { mutableStateOf("SPAM") }
    var busy by remember(owner) { mutableStateOf(true) }
    var fetchFailed by remember(owner) { mutableStateOf(false) }
    var message by remember(owner) { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable(owner) { mutableStateOf(false) }
    LaunchedEffect(owner) { state = fetchState(); fetchFailed = state == null; busy = false }
    fun operation(action: suspend () -> Boolean, success: Int) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            message = if (action()) success else R.string.beta_network_failed
            state = fetchState(); fetchFailed = state == null; busy = false
        }
    }
    Column(Modifier.fillMaxWidth().testTag("settings_contact_safety"), verticalArrangement = Arrangement.spacedBy(HoldSpace.Small)) {
        SettingsActionRow(stringResource(R.string.beta_safety_title), onClick = { expanded = !expanded },
            modifier = Modifier.testTag("settings_safety_row"), expanded = expanded)
        if (expanded) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.beta_safety_detail), style = MaterialTheme.typography.bodyMedium)
        HoldInput(handle, { if (it.length <= 30) handle = it.lowercase().removePrefix("@") },
            label = { Text(stringResource(R.string.mvp_handle_label)) }, singleLine = true, enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("settings_block_handle"))
        val valid = handle.matches(Regex("[a-z0-9_.]{3,30}")) && !busy
        HoldSecondaryButton(enabled = valid, onClick = { operation({ block(handle) }, R.string.beta_blocked) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.beta_block))
        }
        val reasons = listOf("SPAM" to R.string.beta_reason_spam, "HARASSMENT" to R.string.beta_reason_harassment,
            "INAPPROPRIATE_CONTENT" to R.string.beta_reason_content, "OTHER" to R.string.beta_reason_other)
        var chooseReason by remember { mutableStateOf(false) }
        Box {
            HoldTextButton(enabled = !busy, onClick = { chooseReason = true }) {
                Text(stringResource(reasons.first { it.first == reason }.second))
            }
            DropdownMenu(expanded = chooseReason, onDismissRequest = { chooseReason = false }) {
                reasons.forEach { (key, label) -> DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { reason = key; chooseReason = false }) }
            }
        }
        HoldSecondaryButton(enabled = valid, onClick = { operation({ report(handle, reason) }, R.string.beta_report_received) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.beta_report))
        }
        state?.blocked_handles?.forEach { blocked ->
            HoldTextButton(enabled = !busy, onClick = { operation({ unblock(blocked) }, R.string.beta_unblocked) }) {
                Text(stringResource(R.string.beta_unblock, blocked))
            }
        }
        if (busy) CircularProgressIndicator()
        if (fetchFailed) {
            Text(stringResource(R.string.beta_network_failed), Modifier.testTag("settings_safety_fetch_failed"),
                color = HoldColors.Destructive, style = MaterialTheme.typography.bodySmall)
            HoldTextButton(enabled = !busy, onClick = {
                if (!busy) {
                    busy = true
                    scope.launch { state = fetchState(); fetchFailed = state == null; busy = false }
                }
            }, modifier = Modifier.testTag("settings_safety_retry")) { Text(stringResource(R.string.hold_retry)) }
        }
        // A failed operation followed by a failed refresh has the same network
        // guidance; keep one visible copy alongside the refresh Retry action.
        message?.takeUnless { fetchFailed && it == R.string.beta_network_failed }
            ?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        }
        }
    }
}
