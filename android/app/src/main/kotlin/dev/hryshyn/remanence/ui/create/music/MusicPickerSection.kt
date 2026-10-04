package dev.hryshyn.remanence.ui.create.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.data.network.MusicTrackHit
import dev.hryshyn.remanence.ui.hold.HoldColors
import dev.hryshyn.remanence.ui.hold.HoldTextButton

/** Optional music stays compact in the content form; searching is an explicit action. */
@Composable
fun MusicPickerSection(
    state: MusicPickerStateMachine,
    modifier: Modifier = Modifier,
    onSelect: (MusicTrackHit) -> Unit = state::onSelect,
    onClearSelection: () -> Unit = state::onClearSelection,
    selectedArtwork: ImageBitmap? = null,
) {
    val query by state.query.collectAsStateWithLifecycle()
    val uiState by state.uiState.collectAsStateWithLifecycle()
    val selection by state.selection.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().testTag("music_section")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.music_picker_optional),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = HoldColors.Ink,
            )
            if (selection == null) {
                HoldTextButton(onClick = { searching = true }, modifier = Modifier.testTag("music_add")) {
                    Text(stringResource(R.string.music_picker_add), style = MaterialTheme.typography.labelMedium)
                }
            } else {
                HoldTextButton(onClick = onClearSelection, modifier = Modifier.testTag("music_clear")) {
                    Text(stringResource(R.string.hold_remove), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        selection?.let { selected ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectedArtwork != null) {
                    Image(
                        bitmap = selectedArtwork,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).testTag("music_selection_artwork"),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(selected.title, Modifier.testTag("music_selection"), style = MaterialTheme.typography.bodyMedium, color = HoldColors.Ink)
                    Text(selected.artists.joinToString(), Modifier.testTag("music_selection_artist"), style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
                }
                HoldTextButton(onClick = { searching = true }, modifier = Modifier.testTag("music_change")) {
                    Text(stringResource(R.string.hold_track_change), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    if (searching) {
        MusicSearchDialog(
            query = query,
            state = uiState,
            onQueryChange = state::onQueryChange,
            onRetry = state::retry,
            onDismiss = { searching = false },
            onSelect = { hit ->
                onSelect(hit)
                searching = false
            },
        )
    }
}

@Composable
private fun MusicSearchDialog(
    query: String,
    state: MusicPickerUiState,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (MusicTrackHit) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val closeLabel = stringResource(R.string.hold_close)
    val searchLabel = stringResource(R.string.music_picker_search_hint)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
                .widthIn(max = 480.dp).fillMaxWidth().fillMaxHeight(.82f).testTag("music_search_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = HoldColors.Paper,
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.music_picker_search_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, color = HoldColors.Ink)
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("music_search_close").semantics { contentDescription = closeLabel }) {
                        Text("×", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.headlineSmall, color = HoldColors.Ink)
                    }
                }
                Spacer(Modifier.height(16.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = HoldColors.Ink),
                    cursorBrush = SolidColor(HoldColors.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().testTag("music_query")
                        .semantics { contentDescription = searchLabel }
                        .background(HoldColors.Field, RoundedCornerShape(8.dp)),
                    decorationBox = { input ->
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 16.dp)) {
                            if (query.isEmpty()) Text(searchLabel, style = MaterialTheme.typography.bodyLarge, color = HoldColors.Muted)
                            input()
                        }
                    },
                )
                Spacer(Modifier.height(16.dp))
                when (state) {
                    MusicPickerUiState.Idle -> MusicSearchMessage(R.string.music_picker_idle, "music_idle")
                    MusicPickerUiState.Loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.testTag("music_loading"),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = HoldColors.Accent, strokeWidth = 2.dp)
                        Text(stringResource(R.string.music_picker_loading), style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
                    }
                    is MusicPickerUiState.Results -> {
                        Text(stringResource(R.string.music_picker_results, state.total), Modifier.testTag("music_count"), style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.weight(1f).testTag("music_results"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(state.hits, key = { it.id }) { hit -> TrackSearchResult(hit, onSelect) }
                        }
                    }
                    MusicPickerUiState.Empty -> MusicSearchMessage(R.string.music_picker_empty, "music_empty")
                    is MusicPickerUiState.Unavailable -> MusicSearchFailure(R.string.music_picker_unavailable, "music_unavailable", onRetry.takeIf { state.retryable })
                    MusicPickerUiState.RateLimited -> MusicSearchFailure(R.string.music_picker_rate_limited, "music_rate_limited", onRetry)
                    MusicPickerUiState.AuthInvalid -> MusicSearchFailure(R.string.music_picker_auth_invalid, "music_auth_invalid")
                    MusicPickerUiState.ValidationFailed -> MusicSearchFailure(R.string.music_picker_validation_failed, "music_validation_failed")
                    MusicPickerUiState.Error -> MusicSearchFailure(R.string.music_picker_error, "music_error", onRetry)
                }
            }
        }
    }
}

@Composable
private fun TrackSearchResult(hit: MusicTrackHit, onSelect: (MusicTrackHit) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = { onSelect(hit) })
            .heightIn(min = 72.dp).padding(vertical = 12.dp, horizontal = 8.dp).testTag("music_hit_${hit.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A music symbol is a content-type cue, never a fabricated album cover.
        Box(Modifier.size(44.dp).background(HoldColors.Field, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Text("♪", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.titleLarge, color = HoldColors.Accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(hit.title, style = MaterialTheme.typography.bodyMedium, color = HoldColors.Ink)
            Text(hit.artists.joinToString(), style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
            val release = listOfNotNull(hit.release?.takeIf { it.isNotBlank() }, hit.year?.toString()).joinToString(" · ")
            if (release.isNotEmpty()) Text(release, style = MaterialTheme.typography.bodySmall, color = HoldColors.Muted)
        }
        Text("+", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.titleMedium, color = HoldColors.Accent)
    }
}

@Composable
private fun MusicSearchMessage(message: Int, tag: String) {
    Text(stringResource(message), Modifier.testTag(tag), style = MaterialTheme.typography.bodyMedium, color = HoldColors.Muted)
}

@Composable
private fun MusicSearchFailure(message: Int, tag: String, onRetry: (() -> Unit)? = null) {
    Text(stringResource(message), Modifier.testTag(tag), style = MaterialTheme.typography.bodyMedium, color = HoldColors.Error)
    if (onRetry != null) {
        HoldTextButton(onClick = onRetry, modifier = Modifier.testTag("music_retry")) {
            Text(stringResource(R.string.hold_retry), style = MaterialTheme.typography.labelLarge)
        }
    }
}
