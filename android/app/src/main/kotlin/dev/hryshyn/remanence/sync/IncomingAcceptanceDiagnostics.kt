package dev.hryshyn.remanence.sync

import android.util.Log
import dev.hryshyn.remanence.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Debug-build-only, redacted visibility into the background acceptance boundary. */
internal object IncomingAcceptanceDiagnostics {
    private const val TAG = "RemanenceIncomingAcceptance"
    private val mutableState = MutableStateFlow("not run")
    val state: StateFlow<String> = mutableState

    fun report(value: String) {
        mutableState.value = value
        if (BuildConfig.DEBUG) {
            // Local JVM tests do not provide Android's Log implementation;
            // diagnostic logging must never affect acceptance behavior.
            runCatching { Log.d(TAG, value) }
        }
    }

    fun report(diagnostic: IncomingAcceptanceDownloadDiagnostic) {
        report(
            if (BuildConfig.DEBUG) {
                diagnostic.safeSummary()
            } else {
                "acceptance download failure"
            },
        )
    }
}
