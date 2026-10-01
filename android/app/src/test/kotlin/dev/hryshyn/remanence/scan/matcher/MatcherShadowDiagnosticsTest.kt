package dev.hryshyn.remanence.scan.matcher

import dev.hryshyn.remanence.core.recognition.StrictCombinedRejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostics contract: every field is a bounded enum or numeric scalar, the
 * summary is redaction-safe by construction, the collector is process-bounded,
 * and the strategy switch is a plain local value.
 */
class MatcherShadowDiagnosticsTest {

    private fun diagnostics(
        outcome: MatcherShadowOutcome = MatcherShadowOutcome.COMPLETED_DISCARDED,
        gate: MatcherShadowGateDecision = MatcherShadowGateDecision.REJECT,
        reason: StrictCombinedRejectReason? = StrictCombinedRejectReason.BASELINE_NOT_ACCEPTED,
    ) = MatcherShadowDiagnostics(
        outcome = outcome,
        stages = listOf(
            MatcherShadowStageTiming(MatcherShadowStage.DECODE, MatcherShadowStageStatus.OK, 12L),
            MatcherShadowStageTiming(MatcherShadowStage.GATE, MatcherShadowStageStatus.OK, 3L),
        ),
        totalLatencyMicros = 100L,
        peakHeapBytes = 4_096L,
        gate = gate,
        gateRejectReason = reason,
    )

    @Test
    fun everyFieldOfEveryRecordIsScalarOrEnum() {
        val allowed = setOf(
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Double::class.javaPrimitiveType,
            List::class.java,
        )
        for (klass in listOf(MatcherShadowDiagnostics::class.java, MatcherShadowStageTiming::class.java)) {
            for (field in klass.declaredFields) {
                if (field.isSynthetic) continue
                val type = field.type
                assertTrue(
                    "${klass.simpleName}.${field.name} of type $type must be scalar/enum",
                    type in allowed || type.isEnum,
                )
            }
        }
    }

    @Test
    fun safeSummaryCarriesOnlyBoundedTokensAndNumbers() {
        val summary = diagnostics().safeSummary()

        assertTrue(summary.contains("shadow outcome=COMPLETED_DISCARDED"))
        assertTrue(summary.contains("gate=REJECT"))
        assertTrue(summary.contains("gateReason=baseline_not_accepted"))
        assertTrue(summary.contains("totalMicros=100"))
        assertTrue(summary.contains("peakHeapBytes=4096"))
        assertTrue(summary.contains("DECODE:OK:12"))
        assertTrue(summary.contains("GATE:OK:3"))
        // No path/image/identity-shaped tokens can be constructed from it.
        assertFalse(summary.contains("/"))
        assertFalse(summary.contains("\\"))
        assertFalse(summary.contains("."))
    }

    @Test
    fun rejectReasonIsOnlyRepresentableWithARejectDecision() {
        var rejected = false
        try {
            MatcherShadowDiagnostics(
                outcome = MatcherShadowOutcome.COMPLETED_DISCARDED,
                stages = emptyList(),
                totalLatencyMicros = 0L,
                peakHeapBytes = 0L,
                gate = MatcherShadowGateDecision.KEEP,
                gateRejectReason = StrictCombinedRejectReason.BASELINE_NOT_ACCEPTED,
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun negativeScalarsAreRejected() {
        var rejected = false
        try {
            MatcherShadowStageTiming(MatcherShadowStage.DECODE, MatcherShadowStageStatus.OK, -1L)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun boundedLogKeepsOnlyTheNewestCapacityEntries() {
        val log = BoundedMatcherShadowLog(capacity = 2)
        repeat(3) { index ->
            log.report(
                MatcherShadowDiagnostics(
                    outcome = MatcherShadowOutcome.UNAVAILABLE_CATALOG_MISSING,
                    stages = emptyList(),
                    totalLatencyMicros = index.toLong(),
                    peakHeapBytes = 0L,
                    gate = MatcherShadowGateDecision.NOT_RUN,
                    gateRejectReason = null,
                ),
            )
        }

        assertEquals(2, log.size())
        val kept = log.snapshot()
        assertEquals(1L, kept.first().totalLatencyMicros)
        assertEquals(2L, kept.last().totalLatencyMicros)
        log.clear()
        assertEquals(0, log.size())
    }

    @Test
    fun strategySwitchIsALocalValueWithExplicitFactories() {
        assertTrue(MatcherShadowConfig.telemetryOnly().enabled)
        assertFalse(MatcherShadowConfig.off().enabled)
        assertFalse(MatcherShadowConfig().enabled)
        assertEquals(MatcherShadowStrategy.OFF, MatcherShadowConfig().strategy)
    }
}
