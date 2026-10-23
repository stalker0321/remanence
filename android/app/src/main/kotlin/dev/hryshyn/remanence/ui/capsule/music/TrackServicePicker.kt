package dev.hryshyn.remanence.ui.capsule.music

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.ui.hold.HoldTextButton

/** Shared action for a sealed track, in either the sender preview or an opened capsule. */
@Composable
internal fun rememberTrackServiceAction(
    track: CapsuleTrackSnapshotV1?,
    openUrl: ((String) -> Boolean)? = null,
): (() -> Unit)? {
    val context = LocalContext.current
    var pickerOpen by remember(track) { mutableStateOf(false) }
    var noHandler by remember(track) { mutableStateOf(false) }
    if (track == null) return null
    if (pickerOpen) TrackServicePicker(
        onDismiss = { pickerOpen = false },
        onOpenService = { service ->
            val url = service.searchUrl(track.title, track.artistDisplay)
            if (openUrl != null) openUrl(url) else context.openTrackUrl(url)
        },
        onResult = { opened -> pickerOpen = false; noHandler = !opened },
    )
    if (noHandler) AlertDialog(
        onDismissRequest = { noHandler = false },
        text = { Text(stringResource(R.string.track_no_handler), Modifier.testTag("capsule_track_no_browser")) },
        confirmButton = {
            HoldTextButton(onClick = { noHandler = false }, modifier = Modifier.testTag("capsule_track_error_close")) {
                Text(stringResource(R.string.hold_close))
            }
        },
    )
    return { pickerOpen = true; noHandler = false }
}

/** Uses only the bundled service list; all user text goes through its encoded search URL builder. */
@Composable
internal fun TrackServicePicker(
    onDismiss: () -> Unit,
    onOpenService: (StreamingService) -> Boolean,
    onResult: (Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("capsule_track_service_picker"),
        title = { Text(stringResource(R.string.track_service_title)) },
        text = {
            Column {
                StreamingService.entries.forEach { service ->
                    HoldTextButton(
                        onClick = { onResult(runCatching { onOpenService(service) }.getOrDefault(false)) },
                        modifier = Modifier.testTag(service.testTag),
                    ) { Text(service.displayName, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            HoldTextButton(onClick = onDismiss, modifier = Modifier.testTag("capsule_track_open_dismiss")) {
                Text(stringResource(R.string.hold_close))
            }
        },
    )
}
