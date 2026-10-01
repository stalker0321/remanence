package dev.hryshyn.remanence.scan.matcher

import dev.hryshyn.remanence.core.recognition.FrameGeometry
import dev.hryshyn.remanence.core.recognition.FrozenMatcherS3Contract
import dev.hryshyn.remanence.core.recognition.StrictCombinedDecision
import dev.hryshyn.remanence.core.recognition.StrictCombinedEvidence
import dev.hryshyn.remanence.core.recognition.StrictCombinedGate
import dev.hryshyn.remanence.core.recognition.StrictCombinedRejectReason
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun syntheticJpeg(): ByteArray =
    "/private/secret/path/identity-card.jpg::SECRET_TOKEN_MARKER".toByteArray()

private fun syntheticFrame(): MatcherShadowFrame =
    MatcherShadowFrame(ByteArray(3 * 8 * 6) { (it % 251).toByte() }, width = 8, height = 6)

/**
 * Hermetic shadow-pipeline proofs: bounded unavailable outcomes for missing
 * catalog / mixed-version / failure inputs, the accepted S1-S4 invocation
 * order with honest inputs, decision reduction to scalars only, buffer
 * cleanup, and redacted scalar diagnostics.
 */
class MatcherShadowPipelineTest {

    private class FakeStages(
        var decode: () -> MatcherShadowFrame? = { syntheticFrame() },
        var localize: () -> MatcherShadowLocalizerOutcome = { MatcherShadowLocalizerOutcome.Proposal },
        var catalog: () -> ByteArray? = { null },
        var gate: (Boolean, StrictCombinedEvidence?) -> StrictCombinedDecision = { _, _ ->
            StrictCombinedDecision.Reject(StrictCombinedRejectReason.BASELINE_NOT_ACCEPTED)
        },
    ) : MatcherShadowStages {
        var gateCalls = 0
        var gateBaselineAccepted: Boolean? = null
        var gateEvidence: StrictCombinedEvidence? = null
        val seenDecodeJpeg = mutableListOf<ByteArray>()
        val seenFrames = mutableListOf<MatcherShadowFrame>()
        val seenLocalizeBgr = mutableListOf<ByteArray>()

        override fun decode(jpeg: ByteArray): MatcherShadowFrame? {
            seenDecodeJpeg += jpeg
            return decode.invoke()
        }

        override fun localize(
            frameBgr: ByteArray,
            width: Int,
            height: Int,
        ): MatcherShadowLocalizerOutcome {
            seenLocalizeBgr += frameBgr
            return localize.invoke()
        }

        override fun catalogIndex(): ByteArray? = catalog.invoke()

        override fun gate(
            baselineAccepted: Boolean,
            evidence: StrictCombinedEvidence?,
        ): StrictCombinedDecision {
            gateCalls++
            gateBaselineAccepted = baselineAccepted
            gateEvidence = evidence
            return gate.invoke(baselineAccepted, evidence)
        }
    }

    private fun run(
        stages: MatcherShadowStages,
        cancellationCheck: () -> Unit = {},
        jpeg: ByteArray = syntheticJpeg(),
    ): MatcherShadowDiagnostics = MatcherShadowPipeline(stages).run(jpeg, cancellationCheck)

    // ------------------------------------------------------------------
    // Missing catalog is bounded and non-authoritative
    // ------------------------------------------------------------------

    @Test
    fun missingCatalogIsBoundedUnavailableAndNeverGates() {
        val stages = FakeStages()
        val result = run(stages)

        assertEquals(MatcherShadowOutcome.UNAVAILABLE_CATALOG_MISSING, result.outcome)
        assertEquals(
            listOf(
                MatcherShadowStage.DECODE to MatcherShadowStageStatus.OK,
                MatcherShadowStage.FRAME_GEOMETRY to MatcherShadowStageStatus.OK,
                MatcherShadowStage.LOCALIZE to MatcherShadowStageStatus.OK,
                MatcherShadowStage.CATALOG to MatcherShadowStageStatus.SKIPPED,
                MatcherShadowStage.GATE to MatcherShadowStageStatus.SKIPPED,
            ),
            result.stages.map { it.stage to it.status },
        )
        assertEquals(MatcherShadowGateDecision.NOT_RUN, result.gate)
        assertEquals(null, result.gateRejectReason)
        assertEquals(0, stages.gateCalls)
        assertTrue(result.stages.all { it.latencyMicros >= 0L })
        assertTrue(result.totalLatencyMicros >= 0L)
        assertTrue(result.peakHeapBytes >= 0L)
    }

    // ------------------------------------------------------------------
    // Accepted S1-S4 invocation with honest inputs; decision discarded
    // ------------------------------------------------------------------

    @Test
    fun validCatalogInvokesAcceptedGateWithHonestNoBaselineInputs() {
        val stages = FakeStages(
            catalog = { FrozenMatcherS3Contract.buildIndex(emptyList()) },
            gate = { baseline, evidence -> StrictCombinedGate.decide(baseline, evidence) },
        )
        val result = run(stages)

        assertEquals(MatcherShadowOutcome.COMPLETED_DISCARDED, result.outcome)
        assertEquals(MatcherShadowGateDecision.REJECT, result.gate)
        // Honest inputs only: no retrieval ran, so no baseline accept and no
        // fabricated evidence - exactly what the frozen gate fail-closes on.
        assertEquals(false, stages.gateBaselineAccepted)
        assertEquals(null, stages.gateEvidence)
        assertEquals(StrictCombinedRejectReason.BASELINE_NOT_ACCEPTED, result.gateRejectReason)
        assertEquals(MatcherShadowStageStatus.OK, result.stages.last().status)
        assertEquals(MatcherShadowStage.GATE, result.stages.last().stage)
    }

    @Test
    fun shadowKeepDecisionIsReducedToScalarsAndDiscarded() {
        val stages = FakeStages(
            catalog = { FrozenMatcherS3Contract.buildIndex(emptyList()) },
            gate = { _, _ -> StrictCombinedDecision.Keep },
        )
        val result = run(stages)

        // The strongest shadow output imaginable: KEEP. It survives only as a
        // bounded enum on the diagnostics record - there is no decision, no
        // capsule id, and no path to grant/open/choose anywhere in the type.
        assertEquals(MatcherShadowOutcome.COMPLETED_DISCARDED, result.outcome)
        assertEquals(MatcherShadowGateDecision.KEEP, result.gate)
        assertEquals(null, result.gateRejectReason)
        assertEquals(
            setOf(
                "outcome",
                "stages",
                "totalLatencyMicros",
                "peakHeapBytes",
                "gate",
                "gateRejectReason",
            ),
            MatcherShadowDiagnostics::class.java.declaredFields
                // `$stable` is the Compose-compiler stability marker the app
                // module injects into every class; it is not part of the
                // diagnostics contract and is stripped here so the assertion
                // stays exactly about the six semantic fields.
                .filterNot { it.isSynthetic || it.name.startsWith('$') }
                .map { it.name }
                .toSet(),
        )
    }

    // ------------------------------------------------------------------
    // Mixed-version / invalid catalog input
    // ------------------------------------------------------------------

    @Test
    fun foreignCatalogBytesAreMixedVersionAndNeverReachTheGate() {
        val stages = FakeStages(catalog = { byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8) })
        val result = run(stages)

        assertEquals(MatcherShadowOutcome.UNAVAILABLE_MIXED_VERSION, result.outcome)
        assertEquals(MatcherShadowStageStatus.FAILED, result.stages.last { it.stage == MatcherShadowStage.CATALOG }.status)
        assertEquals(MatcherShadowStage.GATE, result.stages.last().stage)
        assertEquals(MatcherShadowStageStatus.SKIPPED, result.stages.last().status)
        assertEquals(0, stages.gateCalls)
    }

    // ------------------------------------------------------------------
    // Failure inputs are bounded unavailable, never thrown
    // ------------------------------------------------------------------

    @Test
    fun undecodableInputIsUnavailableInputAndStopsImmediately() {
        val stages = FakeStages(decode = { null })
        val result = run(stages)

        assertEquals(MatcherShadowOutcome.UNAVAILABLE_INPUT, result.outcome)
        assertEquals(1, result.stages.size)
        assertEquals(MatcherShadowStage.DECODE, result.stages.single().stage)
        assertEquals(MatcherShadowStageStatus.FAILED, result.stages.single().status)
        assertEquals(MatcherShadowGateDecision.NOT_RUN, result.gate)
    }

    @Test
    fun decodeExceptionIsBoundedRuntimeFailure() {
        val stages = FakeStages(decode = { throw IllegalStateException("boom") })
        val result = run(stages)

        assertEquals(MatcherShadowOutcome.UNAVAILABLE_RUNTIME, result.outcome)
        assertEquals(MatcherShadowStageStatus.FAILED, result.stages.single().status)
    }

    @Test
    fun localizerUnknownIsUnavailableLocalizerAndGateNeverRuns() {
        val stages = FakeStages(localize = { MatcherShadowLocalizerOutcome.Unknown })
        val result = run(stages)

        assertEquals(MatcherShadowOutcome.UNAVAILABLE_LOCALIZER, result.outcome)
        assertEquals(
            listOf(MatcherShadowStage.DECODE, MatcherShadowStage.FRAME_GEOMETRY, MatcherShadowStage.LOCALIZE),
            result.stages.map { it.stage },
        )
        assertEquals(MatcherShadowStageStatus.FAILED, result.stages.last().status)
        assertEquals(0, stages.gateCalls)
    }

    @Test
    fun localizeExceptionIsBoundedRuntimeFailureAndNeverEscapes() {
        val stages = FakeStages(localize = { throw RuntimeException("localizer exploded with /path") })
        val result = run(stages)

        assertEquals(MatcherShadowOutcome.UNAVAILABLE_RUNTIME, result.outcome)
        assertFalse(result.safeSummary().contains("/path"))
    }

    @Test
    fun catalogProviderExceptionIsBoundedRuntimeFailure() {
        val stages = FakeStages(catalog = { throw RuntimeException("catalog reader failed") })
        val result = run(stages)

        assertEquals(MatcherShadowOutcome.UNAVAILABLE_RUNTIME, result.outcome)
        assertEquals(MatcherShadowStageStatus.FAILED, result.stages.last { it.stage == MatcherShadowStage.CATALOG }.status)
        assertEquals(0, stages.gateCalls)
    }

    // ------------------------------------------------------------------
    // Cancellation: bounded record, preserved, never success
    // ------------------------------------------------------------------

    @Test
    fun cancellationIsRecordedAsBoundedCancelledAndNeverInvokesTheGate() {
        var checks = 0
        val stages = FakeStages()
        val result = run(stages, cancellationCheck = {
            checks++
            if (checks == 3) throw CancellationException("cancelled by owner")
        })

        assertEquals(MatcherShadowOutcome.CANCELLED, result.outcome)
        assertEquals(MatcherShadowGateDecision.NOT_RUN, result.gate)
        assertEquals(0, stages.gateCalls)
        assertEquals(
            listOf(MatcherShadowStage.DECODE, MatcherShadowStage.FRAME_GEOMETRY),
            result.stages.map { it.stage },
        )
    }

    @Test
    fun ownedFramesAreWipedOnEveryOutcome() {
        val owned = mutableListOf<MatcherShadowFrame>()
        val missingCatalog = FakeStages(decode = { syntheticFrame().also { owned += it } })
        run(missingCatalog)
        // Decode frame is pipeline-owned: its finally must wipe it even on the
        // early missing-catalog return, and the crop buffer handed to S2 too.
        assertEquals(1, owned.size)
        assertTrue("decode frame must be wiped", owned.single().isWiped)
        assertEquals(1, missingCatalog.seenLocalizeBgr.size)
        assertTrue(
            "crop buffer must be wiped",
            missingCatalog.seenLocalizeBgr.single().all { it == 0.toByte() },
        )

        val owned2 = mutableListOf<MatcherShadowFrame>()
        val completed = FakeStages(
            decode = { syntheticFrame().also { owned2 += it } },
            catalog = { FrozenMatcherS3Contract.buildIndex(emptyList()) },
        )
        run(completed)
        assertTrue("decode frame must be wiped on completion", owned2.single().isWiped)
        assertTrue(
            "crop buffer must be wiped on completion",
            completed.seenLocalizeBgr.single().all { it == 0.toByte() },
        )
    }

    @Test
    fun cropMatchesFrozenFrameCropSemantics() {
        val source = MatcherShadowFrame(ByteArray(3 * 8 * 6) { it.toByte() }, width = 8, height = 6)
        val bounds = FrameGeometry.bounds(8, 6)
        val cropped = cropToFrame(source, bounds)
        try {
            assertEquals(bounds.width, cropped.width)
            assertEquals(bounds.height, cropped.height)
            // byte-exact image[y0:y1, x0:x1] slice
            for (y in bounds.y0 until bounds.y1) {
                for (x in bounds.x0 until bounds.x1) {
                    for (channel in 0 until 3) {
                        val expected = source.bgr[(y * 8 + x) * 3 + channel]
                        val actual = cropped.bgr[((y - bounds.y0) * bounds.width + (x - bounds.x0)) * 3 + channel]
                        assertEquals(expected, actual)
                    }
                }
            }
        } finally {
            cropped.wipe()
            source.wipe()
        }
    }

    // ------------------------------------------------------------------
    // Diagnostics are scalar/redacted end-to-end
    // ------------------------------------------------------------------

    @Test
    fun adversarialInputNeverReachesDiagnosticsOrSummary() {
        val marker = "SECRET_TOKEN_MARKER"
        val stages = FakeStages(catalog = { FrozenMatcherS3Contract.buildIndex(emptyList()) })
        val result = run(stages, jpeg = syntheticJpeg())

        val summary = result.safeSummary()
        assertFalse(summary.contains(marker))
        assertFalse(summary.contains(".jpg"))
        assertFalse(summary.contains("/private"))
        // Field-level: the record itself cannot hold the input at all.
        for (field in MatcherShadowDiagnostics::class.java.declaredFields) {
            val type = field.type
            val scalar = type in setOf(
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                List::class.java,
            ) || type.isEnum || MatcherShadowStageTiming::class.java.isAssignableFrom(type)
            assertTrue("field ${field.name} of type $type must be scalar/enum", scalar)
            if (type == String::class.java) error("string field not allowed")
        }
    }

    @Test
    fun stageLatenciesAreMonotonicNonNegativeScalars() {
        val stages = FakeStages(catalog = { FrozenMatcherS3Contract.buildIndex(emptyList()) })
        val result = run(stages)

        assertTrue(result.stages.isNotEmpty())
        assertTrue(result.stages.all { it.latencyMicros >= 0L })
        assertTrue(result.totalLatencyMicros >= 0L)
        assertTrue(result.peakHeapBytes >= 0L)
        // The stages are recorded in execution order.
        assertEquals(
            listOf(
                MatcherShadowStage.DECODE,
                MatcherShadowStage.FRAME_GEOMETRY,
                MatcherShadowStage.LOCALIZE,
                MatcherShadowStage.CATALOG,
                MatcherShadowStage.GATE,
            ),
            result.stages.map { it.stage },
        )
    }
}
