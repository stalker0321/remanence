package dev.hryshyn.remanence.ui.create.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hryshyn.remanence.core.data.network.MusicSearchResult
import dev.hryshyn.remanence.core.data.network.MusicTrackHit

/**
 * S4 release wiring between the picker UI and the ViewModel-owned
 * selection sealed at publish time.
 *
 * Exactly two writes exist, both explicit user actions: select forwards
 * the hit to the machine display AND [onSelect]; clear forwards to both
 * [onClearSelection] sinks. There is deliberately no selection-flow
 * bridge: a recreated machine seeds its display from [vmSelection] and
 * can never write a silent null back (the S4 STOP bug class). The reverse
 * sync below only clears stale UI display when the owner cleared — it
 * never touches owner state.
 */
@Composable
fun MusicPickerContent(
    search: suspend (query: String, limit: Int, offset: Int) -> MusicSearchResult,
    vmSelection: MusicTrackHit?,
    onSelect: (MusicTrackHit) -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val machine = remember {
        MusicPickerStateMachine(search, scope, initialSelection = vmSelection)
    }
    MusicPickerSection(
        state = machine,
        modifier = modifier,
        onSelect = { hit ->
            machine.onSelect(hit)
            onSelect(hit)
        },
        onClearSelection = {
            machine.onClearSelection()
            onClearSelection()
        },
    )
    LaunchedEffect(vmSelection) {
        if (vmSelection == null) {
            machine.onClearSelection()
        }
    }
}
