package dev.hryshyn.remanence.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Debug-build-only, redacted visibility into the background acceptance boundary. */
internal object IncomingAcceptanceDiagnostics {
    private val mutableState = MutableStateFlow("not run")
    val state: StateFlow<String> = mutableState

    fun report(value: String) {
        mutableState.value = value
    }
}
