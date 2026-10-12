package dev.hryshyn.remanence.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.hryshyn.remanence.ui.hold.HoldSpace
import kotlinx.coroutines.delay

/** Keep the field's explanation in its focus scroll rectangle, including after the IME opens. */
@Composable
internal fun AuthFieldGroup(message: String?, tag: String, field: @Composable (Modifier) -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    // Deliberately keyed to focus/error/IME, not the entered value. Typing a valid
    // value does not scroll repeatedly or request/take focus from another field.
    LaunchedEffect(focused, message, imeBottom) {
        if (focused) {
            delay(150) // Coalesce the IME/layout transition before measuring the helper.
            requester.bringIntoView()
        }
    }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(requester),
        verticalArrangement = Arrangement.spacedBy(HoldSpace.Related)) {
        field(Modifier.onFocusChanged { focused = it.isFocused })
        if (message != null) Text(message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite })
    }
}
