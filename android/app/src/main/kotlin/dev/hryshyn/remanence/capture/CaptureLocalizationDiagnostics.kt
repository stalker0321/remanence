package dev.hryshyn.remanence.capture

import android.util.Log
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.LocalizationProposalSource
import dev.hryshyn.remanence.core.recognition.QualityReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Terminal classification for one redacted production localization event. */
enum class CaptureLocalizationOutcome {
    ACCEPTED,
    REJECTED,
}

/** Why the V2 proposal was not the warp that reached extraction. */
enum class LocalizationFallbackReason {
    NONE,
    V2_NO_PROPOSAL,
    V2_WARP_INVALID,
}

/**
 * Numeric/enumerated-only capture telemetry. It deliberately has no image,
 * pixel, descriptor, coordinate, identifier, or exception/message fields.
 */
data class CaptureLocalizationEvent(
    val side: FingerprintSide,
    val featureV2Enabled: Boolean,
    val source: LocalizationProposalSource,
    val stage: CaptureDiagnosticStage,
    val outcome: CaptureLocalizationOutcome,
    val fallbackReason: LocalizationFallbackReason = LocalizationFallbackReason.NONE,
    val qualityReasons: Set<QualityReason> = emptySet(),
    val featureCount: Int? = null,
) {
    fun safeSummary(): String = buildString {
        append("feature=v2-line:").append(featureV2Enabled)
        append(" side=").append(side.name)
        append(" stage=").append(stage.name)
        append(" source=").append(source.name)
        append(" outcome=").append(outcome.name)
        append(" fallback=").append(fallbackReason.name)
        append(" quality=").append(
            qualityReasons.map { it.name }.sorted().ifEmpty { listOf("NONE") }.joinToString("|"),
        )
        append(" features=").append(featureCount?.toString() ?: "n/a")
    }
}

/** Debug-only local visibility; release builds never emit this telemetry. */
internal object CaptureLocalizationDiagnostics {
    private const val TAG = "RemanenceLocalization"
    private val mutableState = MutableStateFlow("not run")
    val state: StateFlow<String> = mutableState

    fun report(event: CaptureLocalizationEvent) {
        if (!BuildConfig.DEBUG) return
        val summary = event.safeSummary()
        mutableState.value = summary
        Log.d(TAG, summary)
    }
}
