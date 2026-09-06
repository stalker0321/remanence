package dev.hryshyn.remanence.ui.scan

import android.util.Log
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.core.recognition.MatchDiagnosticEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Debug-only recognition visibility; all payload-bearing fields stay out. */
internal object RecognitionDiagnostics {
    private const val TAG = "RemanenceRecognition"
    private val mutableState = MutableStateFlow("not run")
    val state: StateFlow<String> = mutableState

    fun report(event: MatchDiagnosticEvent) {
        if (!BuildConfig.DEBUG) return
        val summary = event.safeSummary()
        mutableState.value = summary
        Log.d(TAG, summary)
    }
}
