package dev.hryshyn.remanence.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.hold.*
import dev.hryshyn.remanence.ui.locale.AppLocale

/** Quiet navigation and bounded content; the activity owns system/IME insets. */
@Composable
fun AccessPage(onBack: (() -> Unit)? = null, backEnabled: Boolean = true,
               footer: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = HoldSpace.pageGutter())) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                val back = stringResource(R.string.hold_back)
                IconButton(onClick = onBack, enabled = backEnabled,
                    modifier = Modifier.size(48.dp).testTag("access_back").semantics { contentDescription = back }) {
                    Text("←", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth(), content = content)
        footer()
        Spacer(Modifier.height(HoldSpace.Small))
    }
}

@Composable
fun AccessHeading(title: String, detail: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(HoldSpace.Related)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = HoldColors.Ink,
            modifier = Modifier.semantics { heading() })
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = HoldColors.Muted)
    }
}

/** Compact before sign-in; unlike a four-label row this survives large text/IME. */
@Composable
fun AccessLanguageControl(current: AppLocale, onSelect: (AppLocale) -> Unit, showCurrent: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    fun label(locale: AppLocale) = when (locale) {
        AppLocale.SYSTEM -> R.string.hold_language_system
        AppLocale.ENGLISH -> R.string.hold_language_english
        AppLocale.RUSSIAN -> R.string.hold_language_russian
        AppLocale.UKRAINIAN -> R.string.hold_language_ukrainian
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        HoldTextButton(onClick = { expanded = true }, modifier = Modifier.testTag("access_language")) {
            Text(
                if (showCurrent) stringResource(R.string.hold_language_label) + " · " + stringResource(label(current))
                else stringResource(R.string.hold_language_label),
                color = HoldColors.Muted,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppLocale.options.forEach { locale ->
                DropdownMenuItem(text = { Text(stringResource(label(locale))) },
                    onClick = { expanded = false; onSelect(locale) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("language_option_${locale.name.lowercase()}")
                        .semantics { selected = current == locale; role = Role.RadioButton })
            }
        }
    }
}

/** Official Google G and Google Sans, deliberately independent of key-manager branding. */
@Composable
fun GoogleSignInButton(onClick: () -> Unit, enabled: Boolean = true, busy: Boolean = false) {
    OutlinedButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("access_google"),
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF747775)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color(0xFF1F1F1F)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp).clearAndSetSemantics {}, strokeWidth = 2.dp)
        else Image(painterResource(R.drawable.google_signin_g), null, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(if (busy) R.string.mvp_working else R.string.mvp_google_continue),
            fontFamily = FontFamily(Font(R.font.google_sans_medium, FontWeight.Medium)),
            fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

/**
 * Shared ticket-claim host: the retry action first, then the weighted
 * handle form. Mounted by the ticket branch and by host regression
 * tests; both observe the same tree, tags, and error slot. Must be
 * called inside a Column: the form's weight ParentData is only
 * meaningful with a Column parent (lexical scope never flows into a
 * plain function body, so this is enforced by receiver, not convention).
 */
@Composable
fun ColumnScope.TicketClaimContent(
    handle: String,
    onHandleChange: (String) -> Unit,
    busy: Boolean,
    serverError: String?,
    onRetryAccount: () -> Unit,
    onContinue: () -> Unit,
) {
    HoldTextButton(
        enabled = !busy,
        onClick = onRetryAccount,
        modifier = Modifier.testTag("access_google_retry"),
    ) {
        Text(stringResource(R.string.access_google_again))
    }
    Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = HoldSpace.Group)) {
        HandleSetupContent(
            handle = handle,
            onChange = onHandleChange,
            busy = busy,
            onContinue = onContinue,
            serverError = serverError,
        )
    }
}

@Composable
fun HandleSetupContent(handle: String, onChange: (String) -> Unit, busy: Boolean, onContinue: () -> Unit,
                       serverError: String? = null) {
    val valid = handle.isNotEmpty() && RegistrationFormValidator.handleError(handle) == null
    val invalid = handle.isNotEmpty() && !valid
    HoldFormScaffold(primary = {
        HoldButton(onClick = onContinue, enabled = !busy && valid,
            modifier = Modifier.fillMaxWidth().testTag("access_handle_continue")) {
            Text(stringResource(if (busy) R.string.mvp_working else R.string.mvp_continue))
        }
    }) {
        AccessHeading(stringResource(R.string.mvp_handle_title), stringResource(R.string.access_handle_detail))
        Spacer(Modifier.height(HoldSpace.Related))
        HoldInput(value = handle, onValueChange = onChange, enabled = !busy, isError = invalid,
            label = { Text(stringResource(R.string.mvp_handle_label)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (!busy && valid) onContinue() }),
            modifier = Modifier.fillMaxWidth().testTag("access_handle_input"))
        Text(stringResource(R.string.auth_handle_rule),
            style = MaterialTheme.typography.bodySmall,
            color = if (invalid) HoldColors.Error else HoldColors.Muted,
            modifier = Modifier.testTag("access_handle_guidance").semantics { liveRegion = LiveRegionMode.Polite })
        // Server-side failure (e.g. handle conflict) lives HERE, next to the
        // input — never above the heading where it would shove the title,
        // change-account action and CTA down. The slot always reserves two
        // lines (wrapping freely beyond that), so toggling the error never
        // moves surrounding content at any locale or font scale. Empty text
        // carries no content and announces nothing.
        Text(serverError.orEmpty(),
            minLines = 2,
            style = MaterialTheme.typography.bodySmall,
            color = HoldColors.Error,
            modifier = Modifier.testTag("access_handle_server_error").semantics { liveRegion = LiveRegionMode.Polite })
        Text(stringResource(R.string.mvp_handle_permanent), style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
    }
}
