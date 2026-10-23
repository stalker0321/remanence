package dev.hryshyn.remanence.ui.settings

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hryshyn.remanence.core.data.network.SessionRequestLease

/** Used by the real root Settings slot; never mounted on send/recovery screens. */
@Composable
internal fun SendAccessSettings(
    owner: String, session: SessionRequestLease?, viewModel: SendAccessViewModel,
    onSessionRequired: () -> Unit,
) {
    DisposableEffect(owner, session, viewModel) {
        viewModel.enter(owner)
        onDispose { viewModel.leave() }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Observe the flow, but derive visible metadata through the synchronous lease fence.
    val visible = if (state.owner == owner) viewModel.visibleFor(owner, session) else SendAccessUiState()
    SendAccessSection(visible,
        viewModel::refresh, onSessionRequired)
}
