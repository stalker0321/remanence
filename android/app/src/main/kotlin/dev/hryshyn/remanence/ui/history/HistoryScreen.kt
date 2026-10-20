package dev.hryshyn.remanence.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.data.network.*
import dev.hryshyn.remanence.ui.hold.*
import dev.hryshyn.remanence.ui.settings.SettingsActionRow
import dev.hryshyn.remanence.ui.settings.SettingsGroup
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onBackToList: () -> Unit,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    canCancel: (OutgoingItem) -> Boolean,
    onRequestCancel: (String) -> Unit,
    onDismissCancel: () -> Unit,
    onConfirmCancel: () -> Unit,
    copyCode: (String) -> String?,
) {
    val selected = state.selected
    val back = if (state.selectedKey != null) onBackToList else onBack
    val listScroll = rememberLazyListState()
    val detailScroll = rememberLazyListState()
    LaunchedEffect(state.selectedKey) {
        if (state.selectedKey != null) detailScroll.scrollToItem(0)
    }
    BackHandler(onBack = if (state.confirmKey != null) onDismissCancel else back)
    Column(Modifier.fillMaxSize().background(HoldColors.Paper).testTag("history_screen")) {
        HoldFlowHeader(onBack = back, onClose = onBack, testKey = "history")
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("history_scroll"),
            state = if (state.selectedKey != null) detailScroll else listScroll,
            contentPadding = PaddingValues(horizontal = HoldSpace.pageGutter(), vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.history_metadata_only), style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
            }
            state.outcome?.let { outcome -> item {
                Text(stringResource(when (outcome) {
                    HistoryCancelResult.SUCCEEDED -> R.string.history_cancel_success
                    HistoryCancelResult.CONFLICT -> R.string.history_cancel_conflict
                    HistoryCancelResult.UNAVAILABLE -> R.string.history_cancel_unavailable
                    HistoryCancelResult.UNKNOWN -> R.string.history_cancel_unknown
                    HistoryCancelResult.SIGN_IN -> R.string.history_sign_in
                }), Modifier.testTag("history_cancel_outcome"))
            } }
            if (state.cancelling) item { Text(stringResource(R.string.history_cancelling), Modifier.testTag("history_cancelling")) }
            if (state.loading) item { Text(stringResource(R.string.history_loading), Modifier.testTag("history_loading")) }
            state.error?.let { failure -> item {
                Text(stringResource(historyFailureCopy(failure)), Modifier.testTag("history_error"), color = HoldColors.Error)
            } }
            if (state.loaded && !state.loading && state.error == null && state.items.isEmpty()) item {
                Text(stringResource(R.string.history_empty), Modifier.testTag("history_empty"))
            }
            if (selected != null) {
                item {
                    HistoryDetail(selected, canCancel(selected), onRequestCancel, copyCode)
                }
            } else {
                items(state.items, key = { it.key }) { entry ->
                    SettingsGroup {
                        SettingsActionRow(recipientLabel(entry),
                            stringResource(statusCopy(entry.status)) + "\n" +
                                stringResource(R.string.history_created, historyDate(entry.createdAt)),
                            { onSelect(entry.key) }, Modifier.testTag("history_row_${entry.key}"))
                    }
                }
                if (state.nextCursor != null) item {
                    HoldButton(onClick = onMore, enabled = !state.loading && !state.cancelling,
                        modifier = Modifier.fillMaxWidth().testTag("history_more")) {
                        Text(stringResource(R.string.history_more))
                    }
                }
            }
            item {
                HoldTextButton(onClick = if (state.error == HistoryFailure.SIGN_IN) onBack else onRefresh,
                    enabled = !state.loading && !state.cancelling,
                    modifier = Modifier.fillMaxWidth().testTag("history_retry")) {
                    Text(stringResource(when {
                        state.error == HistoryFailure.SIGN_IN -> R.string.hold_back_to_home
                        state.error != null -> R.string.mvp_retry_connection
                        else -> R.string.history_refresh
                    }))
                }
            }
        }
    }
    if (state.confirmKey != null) AlertDialog(
        onDismissRequest = onDismissCancel,
        title = { Text(stringResource(R.string.create_audit_revoke_title)) },
        text = { Text(stringResource(R.string.history_cancel_warning)) },
        confirmButton = {
            HoldTextButton(onClick = onConfirmCancel, enabled = !state.cancelling,
                modifier = Modifier.testTag("history_cancel_confirm")) {
                Text(stringResource(R.string.create_audit_revoke_confirm), color = HoldColors.Destructive)
            }
        },
        dismissButton = {
            HoldTextButton(onClick = onDismissCancel, modifier = Modifier.testTag("history_cancel_keep")) {
                Text(stringResource(R.string.create_audit_revoke_keep))
            }
        },
    )
}

@Composable
private fun HistoryDetail(item: OutgoingItem, cancellable: Boolean,
                          onCancel: (String) -> Unit, codeForCopy: (String) -> String?) {
    val context = LocalContext.current
    var copied by remember(item.key, item.code) { mutableStateOf<Boolean?>(null) }
    Column(Modifier.fillMaxWidth().testTag("history_detail"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(recipientLabel(item), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(statusCopy(item.status)), Modifier.testTag("history_status"))
        DetailDate(R.string.history_created, item.createdAt)
        item.publishedAt?.let { DetailDate(R.string.history_published, it) }
        item.expiresAt?.let { DetailDate(R.string.history_expires, it) }
        item.firstOpenedAt?.let { DetailDate(R.string.history_opened, it) }
        item.revokedAt?.let { DetailDate(R.string.history_revoked, it) }
        val displayedCode = item.code
        if (displayedCode != null) {
            Text(displayedCode, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("history_code"))
            HoldButton(onClick = {
                copied = runCatching {
                    val code = codeForCopy(item.key) ?: error("code is no longer available")
                    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: error("clipboard unavailable")
                    clipboard.setPrimaryClip(ClipData.newPlainText("Remanence", code))
                }.isSuccess
            }, modifier = Modifier.testTag("history_copy")) { Text(stringResource(R.string.history_copy)) }
            copied?.let {
                Text(stringResource(if (it) R.string.history_copied else R.string.history_copy_failed),
                    Modifier.testTag("history_copy_result"))
            }
        } else {
            Text(stringResource(if (item.locatorStatus == LocatorStatus.RETIRED_OPEN ||
                item.locatorStatus == LocatorStatus.RETIRED_TERMINAL) R.string.history_code_retired else R.string.history_code_unavailable),
                Modifier.testTag("history_code_unavailable"), color = HoldColors.Muted)
        }
        item.cancelDeadline?.let { DetailDate(R.string.history_cancel_deadline, it) }
        if (cancellable) HoldDestructiveButton(onClick = { onCancel(item.key) },
            modifier = Modifier.fillMaxWidth().testTag("history_cancel")) {
            Text(stringResource(R.string.create_audit_revoke_confirm))
        } else Text(stringResource(R.string.history_cancel_unavailable), Modifier.testTag("history_no_cancel"), color = HoldColors.Muted)
    }
}

@Composable
fun RecentRecipients(state: RecentUiState, enabled: Boolean, onRetry: () -> Unit, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("recent_recipients"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.history_recent), Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge)
        when {
            state.loading -> Text(stringResource(R.string.history_loading), Modifier.testTag("recent_loading"))
            state.error != null -> {
                Text(stringResource(historyFailureCopy(state.error)), Modifier.testTag("recent_error"), color = HoldColors.Muted)
                HoldTextButton(onClick = onRetry, enabled = enabled, modifier = Modifier.testTag("recent_retry")) {
                    Text(stringResource(if (state.error == HistoryFailure.SIGN_IN) R.string.hold_back_to_home else R.string.mvp_retry_connection))
                }
            }
            state.loaded && state.items.isEmpty() -> Text(stringResource(R.string.history_recent_empty), Modifier.testTag("recent_empty"), color = HoldColors.Muted)
            else -> state.items.forEach { recipient ->
                HoldTextButton(onClick = { onSelect(recipient.userId) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("recent_${recipient.userId}")) {
                    Text("\u2068@${recipient.handle}\u2069")
                }
            }
        }
    }
}

@Composable private fun recipientLabel(item: OutgoingItem): String = when {
    item.isSelf == true -> stringResource(R.string.history_self)
    item.recipientHandle != null -> "\u2068@${item.recipientHandle}\u2069"
    item.recipientHint != null -> "\u2068${item.recipientHint}\u2069"
    else -> stringResource(R.string.history_recipient)
}
@Composable private fun DetailDate(label: Int, date: String) {
    Text(stringResource(label, historyDate(date)), style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
}
@Composable private fun historyDate(raw: String): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return runCatching { OffsetDateTime.parse(raw).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)) }
        .getOrDefault(raw)
}
internal fun historyFailureCopy(reason: HistoryFailure): Int = when (reason) {
    HistoryFailure.SIGN_IN -> R.string.history_sign_in
    HistoryFailure.UNAVAILABLE -> R.string.history_unavailable
    else -> R.string.history_error
}
internal fun statusCopy(status: OutgoingStatus): Int = when (status) {
    OutgoingStatus.DRAFT -> R.string.history_status_draft
    OutgoingStatus.ABORTED -> R.string.history_status_aborted
    OutgoingStatus.READY -> R.string.history_status_ready
    OutgoingStatus.OPENED -> R.string.history_status_opened
    OutgoingStatus.REVOKED -> R.string.history_status_revoked
    OutgoingStatus.PENDING -> R.string.history_status_pending
    OutgoingStatus.CLAIMED -> R.string.history_status_claimed
    OutgoingStatus.CANCELLED -> R.string.history_status_cancelled
    OutgoingStatus.EXPIRED -> R.string.history_status_expired
    OutgoingStatus.UNKNOWN -> R.string.history_status_unknown
}
