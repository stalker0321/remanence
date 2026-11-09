package dev.hryshyn.remanence.ui.capsule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.hold.HoldColors
import dev.hryshyn.remanence.ui.hold.HoldTextButton

/**
 * The long note beside the sheet: one quiet action under the postcard that
 * opens the text full screen. It is never drawn on the sheet or exported with it.
 */
@Composable
internal fun CapsuleLetterAction(letter: String?) {
    if (letter.isNullOrBlank()) return
    var open by remember(letter) { mutableStateOf(false) }
    HoldTextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        .testTag("capsule_letter_open")) {
        Text(stringResource(R.string.capsule_letter_open))
    }
    if (open) CapsuleLetterWindow(letter, onDismiss = { open = false })
}

@Composable
private fun CapsuleLetterWindow(letter: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        securePolicy = SecureFlagPolicy.SecureOn,
    )) {
        Column(Modifier.fillMaxSize().background(HoldColors.Paper).safeDrawingPadding()
            .testTag("capsule_letter_window")) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                HoldTextButton(onClick = onDismiss, modifier = Modifier.testTag("capsule_letter_close")) {
                    Text(stringResource(R.string.hold_close))
                }
            }
            Text(
                letter,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 28.sp),
                color = HoldColors.Ink,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp).testTag("capsule_letter_text"),
            )
        }
    }
}
