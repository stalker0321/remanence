package dev.hryshyn.remanence.core.recognition

/** Local rollout switch for the production postcard proposal source. */
enum class LocalizationStrategy {
    LEGACY_CONTOUR,
    V2_LINE_THEN_LEGACY,
}

/**
 * Constructor-injected rather than remotely controlled so a bad rollout can
 * be disabled without changing matcher thresholds or persisted data.
 */
data class LocalizationFeatureConfig(
    val strategy: LocalizationStrategy = LocalizationStrategy.LEGACY_CONTOUR,
) {
    val usesV2LineLocator: Boolean
        get() = strategy == LocalizationStrategy.V2_LINE_THEN_LEGACY

    companion object {
        fun legacyDefault(): LocalizationFeatureConfig = LocalizationFeatureConfig()

        fun v2LineThenLegacy(): LocalizationFeatureConfig = LocalizationFeatureConfig(
            strategy = LocalizationStrategy.V2_LINE_THEN_LEGACY,
        )
    }
}
