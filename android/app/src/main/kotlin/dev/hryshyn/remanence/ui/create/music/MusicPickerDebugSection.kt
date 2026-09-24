package dev.hryshyn.remanence.ui.create.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hryshyn.remanence.ui.hold.HoldButton as Button
import dev.hryshyn.remanence.ui.hold.HoldInformation
import dev.hryshyn.remanence.ui.hold.HoldInput as OutlinedTextField
import dev.hryshyn.remanence.ui.hold.HoldTextButton as TextButton

/**
 * S1 DEBUG-only music picker section for the Create flow.
 *
 * Rendered exclusively behind `BuildConfig.DEBUG` (release builds strip
 * the call site entirely) and wired to nothing in the publish path: the
 * selection lives only in [MusicPickerStateMachine] and is never read by
 * [dev.hryshyn.remanence.create.CapsulePublisher]. Until S2 defines the
 * encrypted track attachment, no release surface may claim a track is
 * attached to the capsule.
 */
@Composable
fun MusicPickerDebugSection(
    state: MusicPickerStateMachine,
    modifier: Modifier = Modifier,
) {
    val query by state.query.collectAsStateWithLifecycle()
    val uiState by state.uiState.collectAsStateWithLifecycle()
    val selection by state.selection.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxWidth().testTag("music_debug_section")) {
        HoldInformation("DEBUG music picker (S1): selection is preview-only, never published.")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = state::onQueryChange,
            label = { Text("Search tracks (debug)") },
            modifier = Modifier.fillMaxWidth().testTag("music_debug_query"),
        )
        Spacer(Modifier.height(8.dp))
        when (val current = uiState) {
            MusicPickerUiState.Idle -> Text(
                "Type to search the sample catalog.",
                modifier = Modifier.testTag("music_debug_idle"),
            )
            MusicPickerUiState.Loading -> Text(
                "Searching…",
                modifier = Modifier.testTag("music_debug_loading"),
            )
            is MusicPickerUiState.Results -> {
                Text(
                    "Found ${current.total} track(s).",
                    modifier = Modifier.testTag("music_debug_count"),
                )
                Spacer(Modifier.height(4.dp))
                current.hits.forEach { hit ->
                    TextButton(
                        onClick = { state.onSelect(hit) },
                        modifier = Modifier.testTag("music_debug_hit_${hit.id}"),
                    ) { Text("${hit.title} — ${hit.artists.joinToString()}") }
                }
            }
            MusicPickerUiState.Empty -> Text(
                "No tracks match.",
                modifier = Modifier.testTag("music_debug_empty"),
            )
            is MusicPickerUiState.Unavailable -> {
                Text(
                    "Music search is temporarily unavailable.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("music_debug_unavailable"),
                )
                TextButton(
                    onClick = state::retry,
                    modifier = Modifier.testTag("music_debug_retry"),
                ) { Text("Retry search.") }
            }
            MusicPickerUiState.RateLimited -> {
                Text(
                    "Too many searches. Wait a minute and retry.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("music_debug_rate_limited"),
                )
                TextButton(
                    onClick = state::retry,
                    modifier = Modifier.testTag("music_debug_retry"),
                ) { Text("Retry search.") }
            }
            MusicPickerUiState.AuthInvalid -> Text(
                "Session expired. Sign in again to search.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("music_debug_auth_invalid"),
            )
            MusicPickerUiState.ValidationFailed -> Text(
                "That search text is invalid.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("music_debug_validation_failed"),
            )
            MusicPickerUiState.Error -> Text(
                "Something went wrong.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("music_debug_error"),
            )
        }
        selection?.let { selected ->
            Spacer(Modifier.height(8.dp))
            Text(
                "Selected (preview only): ${selected.title}",
                modifier = Modifier.testTag("music_debug_selection"),
            )
            TextButton(
                onClick = state::onClearSelection,
                modifier = Modifier.testTag("music_debug_clear"),
            ) { Text("Clear selection") }
        }
    }
}
