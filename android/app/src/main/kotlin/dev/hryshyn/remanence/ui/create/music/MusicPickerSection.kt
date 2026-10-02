package dev.hryshyn.remanence.ui.create.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.data.network.MusicTrackHit
import dev.hryshyn.remanence.ui.hold.HoldInformation
import dev.hryshyn.remanence.ui.hold.HoldInput as OutlinedTextField
import dev.hryshyn.remanence.ui.hold.HoldTextButton as TextButton

/**
 * S4 release music picker section for the Create flow.
 *
 * Optional sample-catalog search; the selection is sealed into the
 * capsule manifest at publish time via
 * [dev.hryshyn.remanence.ui.create.CreateViewModel.musicSelection] (see
 * S2b-sender). Clearing the selection publishes without a track.
 */
@Composable
fun MusicPickerSection(
    state: MusicPickerStateMachine,
    modifier: Modifier = Modifier,
    /**
     * S4: explicit selection sinks. Defaults preserve the standalone
     * machine behavior; the Create flow passes ViewModel-owned callbacks
     * so only deliberate user select/clear ever reaches publish state —
     * never an implicit null from UI recreation.
     */
    onSelect: (MusicTrackHit) -> Unit = state::onSelect,
    onClearSelection: () -> Unit = state::onClearSelection,
) {
    val query by state.query.collectAsStateWithLifecycle()
    val uiState by state.uiState.collectAsStateWithLifecycle()
    val selection by state.selection.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxWidth().testTag("music_section")) {
        HoldInformation("Optional: search the sample catalog and attach a track to this capsule.")
        Spacer(Modifier.height(8.dp))
        // T150 explicit search state: a confirmed selection collapses the
        // query field and results; Change reveals one explicit new search and
        // Remove drops the selection. Late results never touch the selection.
        var searching by remember(selection) { mutableStateOf(selection == null) }
        val selected = selection
        if (selected != null && !searching) {
            Text(
                "Attached on publish: ${selected.title}",
                modifier = Modifier.testTag("music_selection"),
            )
            Text(
                selected.artists.joinToString(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("music_selection_artist"),
            )
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(
                    onClick = { searching = true },
                    modifier = Modifier.testTag("music_change"),
                ) { Text(stringResource(R.string.hold_track_change)) }
                TextButton(
                    onClick = onClearSelection,
                    modifier = Modifier.testTag("music_clear"),
                ) { Text(stringResource(R.string.hold_remove)) }
            }
            return@Column
        }
        if (selected != null) {
            Text(
                "Selected: ${selected.title} — ${selected.artists.joinToString()}",
                modifier = Modifier.testTag("music_selection"),
            )
            TextButton(
                onClick = onClearSelection,
                modifier = Modifier.testTag("music_clear"),
            ) { Text(stringResource(R.string.hold_remove)) }
            Spacer(Modifier.height(4.dp))
        }
        OutlinedTextField(
            value = query,
            onValueChange = state::onQueryChange,
            label = { Text("Search tracks") },
            modifier = Modifier.fillMaxWidth().testTag("music_query"),
        )
        Spacer(Modifier.height(8.dp))
        when (val current = uiState) {
            MusicPickerUiState.Idle -> Text(
                "Type to search the sample catalog.",
                modifier = Modifier.testTag("music_idle"),
            )
            MusicPickerUiState.Loading -> Text(
                "Searching…",
                modifier = Modifier.testTag("music_loading"),
            )
            is MusicPickerUiState.Results -> {
                Text(
                    "Found ${current.total} track(s).",
                    modifier = Modifier.testTag("music_count"),
                )
                Spacer(Modifier.height(4.dp))
                current.hits.forEach { hit ->
                    TextButton(
                        onClick = { onSelect(hit) },
                        modifier = Modifier.testTag("music_hit_${hit.id}"),
                    ) { Text("${hit.title} — ${hit.artists.joinToString()}") }
                }
            }
            MusicPickerUiState.Empty -> Text(
                "No tracks match.",
                modifier = Modifier.testTag("music_empty"),
            )
            is MusicPickerUiState.Unavailable -> {
                Text(
                    "Music search is temporarily unavailable.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("music_unavailable"),
                )
                TextButton(
                    onClick = state::retry,
                    modifier = Modifier.testTag("music_retry"),
                ) { Text("Retry search.") }
            }
            MusicPickerUiState.RateLimited -> {
                Text(
                    "Too many searches. Wait a minute and retry.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("music_rate_limited"),
                )
                TextButton(
                    onClick = state::retry,
                    modifier = Modifier.testTag("music_retry"),
                ) { Text("Retry search.") }
            }
            MusicPickerUiState.AuthInvalid -> Text(
                "Session expired. Sign in again to search.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("music_auth_invalid"),
            )
            MusicPickerUiState.ValidationFailed -> Text(
                "That search text is invalid.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("music_validation_failed"),
            )
            MusicPickerUiState.Error -> Text(
                "Something went wrong.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("music_error"),
            )
        }
    }
}
