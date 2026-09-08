package dev.hryshyn.rv01probe.probe

/**
 * Non-secret controller handoff for a fresh D2 target. It contains only the
 * exact provider slot and trusted synthetic context; it never contains U, P,
 * a canary, plaintext, or provider/account metadata.
 *
 * D2 is deliberately constructed through this boundary rather than by
 * copying the source case's D1 context. A D1 context is rejected at creation.
 */
class ExpectedContextFixture private constructor(
    val key: String,
    val expectedContext: ExpectedContext,
) {
    companion object {
        fun fromExternal(
            key: String,
            expectedContext: ExpectedContext,
        ): ExpectedContextFixture? = if (
            ProbeKey.isValid(key) &&
            expectedContext.targetRole == ContextTargetRole.D2_TARGET
        ) {
            ExpectedContextFixture(key, expectedContext)
        } else {
            null
        }
    }
}
