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

    fun report(diagnostic: IncomingAcceptancePersistenceDiagnostic) {
        report(
            if (BuildConfig.DEBUG) {
                diagnostic.safeSummary()
            } else {
                "acceptance persistence failure"
            },
        )
    }

    fun reportPersistenceRetry(
        reason: IncomingCapsuleAcceptanceRetryReason,
        diagnostic: IncomingAcceptancePersistenceDiagnostic,
    ) {
        report(
            if (BuildConfig.DEBUG) {
                "acceptance retry: ${reason.name} ${diagnostic.safeSummary()}"
            } else {
                "acceptance retry: ${reason.name}"
            },
        )
    }
}

/** The bounded persistence stages exposed by the debug acceptance diagnostic. */
enum class IncomingAcceptancePersistenceStage {
    SEAL,
    PART_CREATE,
    PART_WRITE,
    FILE_FORCE,
    DIRECTORY_FORCE,
    PUBLICATION,
    DESTINATION_VERIFY,
    REPLAY_READ,
    REPLAY_UNSEAL,
}

/** No path, exception, secret, or payload is carried across this boundary. */
data class IncomingAcceptancePersistenceDiagnostic(
    val stage: IncomingAcceptancePersistenceStage,
) {
    fun safeSummary(): String = "acceptance persistence stage=${stage.name}"

    override fun toString(): String = "IncomingAcceptancePersistenceDiagnostic(<redacted>)"
}
