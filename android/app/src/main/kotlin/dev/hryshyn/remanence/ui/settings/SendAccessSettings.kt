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
    SendAccessSection(if (state.owner == owner) state else SendAccessUiState(),
        viewModel::refresh, onSessionRequired)
}
