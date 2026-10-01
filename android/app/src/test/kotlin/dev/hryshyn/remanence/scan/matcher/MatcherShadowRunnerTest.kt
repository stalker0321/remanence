package dev.hryshyn.remanence.scan.matcher

import dev.hryshyn.remanence.core.recognition.StrictCombinedDecision
import dev.hryshyn.remanence.core.recognition.StrictCombinedEvidence
import dev.hryshyn.remanence.core.recognition.StrictCombinedRejectReason
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runner ownership proofs: snapshot exists only while the local switch is
 * on, the original v1 bytes are never touched, the owned copy is wiped on
 * run/failure/cancellation/pre-run paths, and the sink only ever receives
 * bounded diagnostics.
 */
class MatcherShadowRunnerTest {

    private class RecordingSink : MatcherShadowSink {
        val entries = mutableListOf<MatcherShadowDiagnostics>()
        override fun report(diagnostics: MatcherShadowDiagnostics) {
            entries += diagnostics
        }
    }

    private class AlwaysUnavailableStages : MatcherShadowStages {
        override fun decode(jpeg: ByteArray): MatcherShadowFrame? = null

        override fun localize(
            frameBgr: ByteArray,
            width: Int,
            height: Int,
        ): MatcherShadowLocalizerOutcome = MatcherShadowLocalizerOutcome.Unknown

        override fun catalogIndex(): ByteArray? = null

        override fun gate(
            baselineAccepted: Boolean,
            evidence: StrictCombinedEvidence?,
        ): StrictCombinedDecision =
            StrictCombinedDecision.Reject(StrictCombinedRejectReason.BASELINE_NOT_ACCEPTED)
    }

    private fun jpeg(): ByteArray = "front-jpeg-bytes-for-shadow".toByteArray()

    @Test
    fun disabledSwitchNeverCopiesInputAndNeverRuns() {
        val sink = RecordingSink()
        val runner = MatcherShadowRunner(MatcherShadowConfig.off(), AlwaysUnavailableStages(), sink)

        val input = runner.snapshot(jpeg())

        assertNull(input)
        assertTrue(sink.entries.isEmpty())
    }

    @Test
    fun enabledSwitchCopiesInputWithoutTouchingTheOriginalV1Bytes() {
        val sink = RecordingSink()
        val runner = MatcherShadowRunner(
            MatcherShadowConfig.telemetryOnly(),
            AlwaysUnavailableStages(),
            sink,
        )
        val original = jpeg()
        val before = original.copyOf()

        val input = requireNotNull(runner.snapshot(original))

        assertArrayEquals(before, original)
        assertNotEquals(original, input.bytes)
        assertArrayEquals(before, input.bytes)
        assertFalse(input.isWiped)
    }

    @Test
    fun runOwnedReportsBoundedDiagnosticsAndWipesTheOwnedCopy() {
        val sink = RecordingSink()
        val runner = MatcherShadowRunner(
            MatcherShadowConfig.telemetryOnly(),
            AlwaysUnavailableStages(),
            sink,
        )
        val original = jpeg()
        val input = requireNotNull(runner.snapshot(original))

        val diagnostics = runner.runOwned(input)

        assertEquals(MatcherShadowOutcome.UNAVAILABLE_INPUT, diagnostics.outcome)
        assertEquals(1, sink.entries.size)
        assertTrue(input.isWiped)
        assertTrue(input.bytes.all { it == 0.toByte() })
        // v1 original untouched by the whole run.
        assertArrayEquals(jpeg(), original)
    }

    @Test
    fun runOwnedWipesTheOwnedCopyOnCancellationWithoutReportingSuccess() {
        val sink = RecordingSink()
        val runner = MatcherShadowRunner(
            MatcherShadowConfig.telemetryOnly(),
            AlwaysUnavailableStages(),
            sink,
        )
        val input = requireNotNull(runner.snapshot(jpeg()))

        val diagnostics = runner.runOwned(input) { throw CancellationException("owner cancelled") }

        assertEquals(MatcherShadowOutcome.CANCELLED, diagnostics.outcome)
        assertEquals(1, sink.entries.size)
        assertTrue(input.isWiped)
        assertTrue(input.bytes.all { it == 0.toByte() })
    }

    @Test
    fun preRunWipeIsIdempotentAndCoversTheRejectPath() {
        val runner = MatcherShadowRunner(
            MatcherShadowConfig.telemetryOnly(),
            AlwaysUnavailableStages(),
            RecordingSink(),
        )
        val input = requireNotNull(runner.snapshot(jpeg()))

        // The scan flow's reject/stale branch wipes without ever running.
        input.wipe()
        input.wipe()

        assertTrue(input.isWiped)
        assertTrue(input.bytes.all { it == 0.toByte() })
    }

    @Test
    fun disabledRunnerFactoryProducesNoopStagesSnapshot() {
        val sink = RecordingSink()
        val runner = MatcherShadowRunner.disabled(sink)

        assertNull(runner.snapshot(jpeg()))
        assertTrue(sink.entries.isEmpty())
        assertFalse(runner.config.enabled)
    }
}
