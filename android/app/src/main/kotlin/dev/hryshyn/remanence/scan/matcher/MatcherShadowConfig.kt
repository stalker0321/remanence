package dev.hryshyn.remanence.scan.matcher

/**
 * Local rollout switch for the telemetry-only frozen matcher S1-S4 shadow.
 *
 * Constructor-injected rather than remotely controlled so a bad rollout can
 * be disabled without changing matcher thresholds, v1 scan results, or any
 * persisted data (same rationale as `LocalizationFeatureConfig`).
 */
enum class MatcherShadowStrategy {
    OFF,
    TELEMETRY,
}

/**
 * The injectable/local strategy switch for the shadow path. With [OFF] (or a
 * null `matcherShadow` in `ScanViewModel`) the scan flow never copies capture
 * bytes, never starts a shadow job, and behaves exactly as before. With
 * [TELEMETRY] the accepted S1->S4 path runs beside the v1 chain, records only
 * bounded scalar diagnostics, and every shadow decision is discarded.
 */
data class MatcherShadowConfig(
    val strategy: MatcherShadowStrategy = MatcherShadowStrategy.OFF,
) {
    val enabled: Boolean
        get() = strategy == MatcherShadowStrategy.TELEMETRY

    companion object {
        fun off(): MatcherShadowConfig = MatcherShadowConfig(MatcherShadowStrategy.OFF)

        fun telemetryOnly(): MatcherShadowConfig =
            MatcherShadowConfig(MatcherShadowStrategy.TELEMETRY)
    }
}
