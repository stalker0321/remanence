package dev.hryshyn.remanence.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
        HoldTextButton(onClick = { failed = !context.openRemanenceLink("privacy") }, modifier = Modifier.testTag("settings_privacy_policy")) {
            Text(stringResource(R.string.beta_privacy_policy))
        }
        HoldTextButton(onClick = { failed = !context.openRemanenceLink("terms") }, modifier = Modifier.testTag("settings_terms")) {
            Text(stringResource(R.string.beta_terms))
        }
        HoldTextButton(onClick = { failed = !context.requestAccountDeletion(handle) }, modifier = Modifier.testTag("settings_delete_account")) {
            Text(stringResource(R.string.beta_delete_account), color = HoldColors.Destructive)
        }
        Text(SUPPORT_EMAIL, color = HoldColors.Muted, style = MaterialTheme.typography.bodySmall)
        if (failed) Text(stringResource(R.string.beta_external_failed), color = HoldColors.Destructive)
    }
}

/** Mandatory beta UGC consent before entering Create. Offline/missing API stays closed. */
@Composable
internal fun BetaTermsGate(repository: SafetyRepository, owner: String, content: @Composable () -> Unit) {
    var state by remember(owner) { mutableStateOf<SafetyState?>(null) }
    var busy by remember(owner) { mutableStateOf(true) }
    var failed by remember(owner) { mutableStateOf(false) }
    var agreed by remember(owner) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(owner) { state = repository.state(); failed = state == null; busy = false }
    if (state?.terms_accepted == true) { content(); return }
    HoldFormScaffold(primary = {
        HoldButton(enabled = agreed && state != null && !busy, onClick = {
            busy = true; scope.launch {
                if (repository.acceptTerms()) { state = repository.state(); failed = state == null } else failed = true
                busy = false
            }
        }, modifier = Modifier.fillMaxWidth().testTag("beta_terms_continue")) { Text(stringResource(R.string.mvp_continue)) }
    }) {
        Text(stringResource(R.string.beta_terms_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.beta_terms_summary))
        HoldTextButton(onClick = { failed = !context.openRemanenceLink("terms") }) { Text(stringResource(R.string.beta_terms)) }
        HoldTextButton(onClick = { failed = !context.openRemanenceLink("privacy") }) { Text(stringResource(R.string.beta_privacy_policy)) }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {}
            .toggleable(value = agreed, enabled = !busy, role = Role.Checkbox, onValueChange = { agreed = it }),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = agreed, onCheckedChange = null, enabled = !busy)
            Text(stringResource(R.string.beta_terms_agree), Modifier.weight(1f))
        }
        if (busy) CircularProgressIndicator()
        if (failed) {
            Text(stringResource(R.string.beta_network_failed), color = HoldColors.Destructive)
            HoldTextButton(onClick = { busy = true; scope.launch { state = repository.state(); failed = state == null; busy = false } }) {
                Text(stringResource(R.string.hold_retry))
            }
        }
    }
}

@Composable
internal fun BetaSafetyPanel(repository: SafetyRepository, owner: String) {
    var state by remember(owner) { mutableStateOf<SafetyState?>(null) }
    var handle by remember(owner) { mutableStateOf("") }
    var reason by remember(owner) { mutableStateOf("SPAM") }
    var busy by remember(owner) { mutableStateOf(false) }
    var message by remember(owner) { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    var expanded by remember(owner) { mutableStateOf(false) }
    LaunchedEffect(owner) { state = repository.state() }
    fun operation(action: suspend () -> Boolean, success: Int) {
        busy = true; message = null
        scope.launch {
            message = if (action()) success else R.string.beta_network_failed
            state = repository.state(); busy = false
        }
    }
    Column(Modifier.fillMaxWidth().testTag("settings_contact_safety"), verticalArrangement = Arrangement.spacedBy(HoldSpace.Small)) {
        HoldTextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(R.string.beta_safety_title), color = HoldColors.Ink)
        }
        if (expanded) {
        Text(stringResource(R.string.beta_safety_detail), style = MaterialTheme.typography.bodyMedium)
        HoldInput(handle, { if (it.length <= 30) handle = it.lowercase().removePrefix("@") },
            label = { Text(stringResource(R.string.mvp_handle_label)) }, singleLine = true, enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("settings_block_handle"))
        val valid = handle.matches(Regex("[a-z0-9_.]{3,30}")) && !busy
        HoldTextButton(enabled = valid, onClick = { operation({ repository.block(handle) }, R.string.beta_blocked) }) {
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
        HoldTextButton(enabled = valid, onClick = { operation({ repository.report(handle, reason) }, R.string.beta_report_received) }) {
            Text(stringResource(R.string.beta_report))
        }
        state?.blocked_handles?.forEach { blocked ->
            HoldTextButton(enabled = !busy, onClick = { operation({ repository.unblock(blocked) }, R.string.beta_unblocked) }) {
                Text(stringResource(R.string.beta_unblock, blocked))
            }
        }
        if (busy) CircularProgressIndicator()
        message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
