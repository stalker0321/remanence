package dev.hryshyn.remanence.scan.matcher

import dev.hryshyn.remanence.core.recognition.FrameGeometry
import dev.hryshyn.remanence.core.recognition.FrozenMatcherS3Contract
import dev.hryshyn.remanence.core.recognition.StrictCombinedDecision
import dev.hryshyn.remanence.core.recognition.StrictCombinedRejectReason
import kotlin.coroutines.cancellation.CancellationException

/**
 * image[y0:y1, x0:x1] crop of a BGR frame - the frozen
 * `run.py::frame_crop` semantic the S2 localizer wiring requires.
 *
 * Returns a NEW owned [MatcherShadowFrame]; the caller must wipe it.
 */
internal fun cropToFrame(source: MatcherShadowFrame, bounds: FrameGeometry.Bounds): MatcherShadowFrame {
    require(
        bounds.x0 >= 0 && bounds.y0 >= 0 &&
            bounds.x1 <= source.width && bounds.y1 <= source.height &&
            bounds.width > 0 && bounds.height > 0,
    ) { "frame bounds out of range" }
    val out = ByteArray(3 * bounds.width * bounds.height)
    var outIndex = 0
    for (y in bounds.y0 until bounds.y1) {
        var index = (y * source.width + bounds.x0) * 3
        repeat(bounds.width * 3) {
            out[outIndex++] = source.bgr[index++]
        }
    }
    return MatcherShadowFrame(out, bounds.width, bounds.height)
}

/**
 * Telemetry-only S1->S4 shadow execution.
 *
 * Runs the accepted path BESIDE v1 (this pipeline receives only owned shadow
 * input - it never reads, writes, or observes any v1 scan state), records
 * bounded per-stage latency/peak-heap/outcome scalars, and reduces the S4
 * decision to bounded enums. The sealed gate decision is never returned,
 * stored, or forwarded: [run] hands back [MatcherShadowDiagnostics] only, so a
 * shadow "keep" structurally cannot grant, open, choose, recapture, or alter
 * v1 in any way.
 *
 * Failure discipline: missing catalog, mixed-version input, localizer
 * unknowns, undecodable input and any other failure become one bounded
 * [MatcherShadowOutcome] - never an exception (cancellation is preserved as
 * the bounded [MatcherShadowOutcome.CANCELLED] record; the owning coroutine's
 * cancellation state is untouched). All owned buffers are wiped by [run]'s
 * callers via the frame lifecycle below.
 */
class MatcherShadowPipeline(
    private val stages: MatcherShadowStages,
    private val clock: () -> Long = System::nanoTime,
    private val heapSampler: () -> Long = {
        Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    },
) {

    /**
     * Executes one shadow run over [jpeg] (caller-owned bytes; this pipeline
     * neither wipes nor retains them). Never throws except through the
     * caller's [cancellationCheck], which is invoked between stages to keep
     * cancellation prompt and cooperative.
     */
    fun run(jpeg: ByteArray, cancellationCheck: () -> Unit = {}): MatcherShadowDiagnostics {
        val stageTimings = mutableListOf<MatcherShadowStageTiming>()
        val ownedFrames = mutableListOf<MatcherShadowFrame>()
        var peakHeap = heapSampler()
        var outcome = MatcherShadowOutcome.UNAVAILABLE_RUNTIME
        var gate = MatcherShadowGateDecision.NOT_RUN
        var gateRejectReason: StrictCombinedRejectReason? = null
        val startedAt = clock()

        fun sampleHeap() {
            val current = heapSampler()
            if (current > peakHeap) peakHeap = current
        }

        fun mark(
            stage: MatcherShadowStage,
            status: MatcherShadowStageStatus,
            stageStartedAt: Long,
        ) {
            stageTimings += MatcherShadowStageTiming(
                stage = stage,
                status = status,
                latencyMicros = microsSince(stageStartedAt, clock()),
            )
            sampleHeap()
        }

        fun finish(): MatcherShadowDiagnostics {
            sampleHeap()
            return MatcherShadowDiagnostics(
                outcome = outcome,
                stages = stageTimings.toList(),
                totalLatencyMicros = microsSince(startedAt, clock()),
                peakHeapBytes = peakHeap,
                gate = gate,
                gateRejectReason = gateRejectReason,
            )
        }

        try {
            // S0 decode -----------------------------------------------------
            cancellationCheck()
            var stageStartedAt = clock()
            var decodeThrew = false
            val decoded = try {
                stages.decode(jpeg)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                decodeThrew = true
                null
            }
            if (decoded == null) {
                mark(MatcherShadowStage.DECODE, MatcherShadowStageStatus.FAILED, stageStartedAt)
                outcome = if (decodeThrew) {
                    MatcherShadowOutcome.UNAVAILABLE_RUNTIME
                } else {
                    MatcherShadowOutcome.UNAVAILABLE_INPUT
                }
                return finish()
            }
            ownedFrames += decoded
            mark(MatcherShadowStage.DECODE, MatcherShadowStageStatus.OK, stageStartedAt)

            // S1 frame geometry + frame crop (accepted FrameGeometry) --------
            cancellationCheck()
            stageStartedAt = clock()
            var geometryInputError = false
            val cropped = try {
                cropToFrame(decoded, FrameGeometry.bounds(decoded.width, decoded.height))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                geometryInputError = true
                null
            } catch (_: Throwable) {
                null
            }
            if (cropped == null) {
                mark(
                    MatcherShadowStage.FRAME_GEOMETRY,
                    MatcherShadowStageStatus.FAILED,
                    stageStartedAt,
                )
                outcome = if (geometryInputError) {
                    MatcherShadowOutcome.UNAVAILABLE_INPUT
                } else {
                    MatcherShadowOutcome.UNAVAILABLE_RUNTIME
                }
                return finish()
            }
            ownedFrames += cropped
            mark(
                MatcherShadowStage.FRAME_GEOMETRY,
                MatcherShadowStageStatus.OK,
                stageStartedAt,
            )

            // S2 frozen localizer on the S1 crop ----------------------------
            cancellationCheck()
            stageStartedAt = clock()
            val localized = try {
                stages.localize(cropped.bgr, cropped.width, cropped.height)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mark(MatcherShadowStage.LOCALIZE, MatcherShadowStageStatus.FAILED, stageStartedAt)
                outcome = MatcherShadowOutcome.UNAVAILABLE_RUNTIME
                return finish()
            }
            if (localized !is MatcherShadowLocalizerOutcome.Proposal) {
                mark(MatcherShadowStage.LOCALIZE, MatcherShadowStageStatus.FAILED, stageStartedAt)
                outcome = MatcherShadowOutcome.UNAVAILABLE_LOCALIZER
                return finish()
            }
            mark(MatcherShadowStage.LOCALIZE, MatcherShadowStageStatus.OK, stageStartedAt)

            // S3 catalog presence + accepted S3 contract validation ----------
            cancellationCheck()
            stageStartedAt = clock()
            var catalogThrew = false
            val catalog = try {
                stages.catalogIndex()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                catalogThrew = true
                null
            }
            if (catalog == null) {
                mark(
                    MatcherShadowStage.CATALOG,
                    if (catalogThrew) MatcherShadowStageStatus.FAILED else MatcherShadowStageStatus.SKIPPED,
                    stageStartedAt,
                )
                mark(MatcherShadowStage.GATE, MatcherShadowStageStatus.SKIPPED, clock())
                outcome = if (catalogThrew) {
                    MatcherShadowOutcome.UNAVAILABLE_RUNTIME
                } else {
                    MatcherShadowOutcome.UNAVAILABLE_CATALOG_MISSING
                }
                return finish()
            }
            val catalogRejected = try {
                val header = FrozenMatcherS3Contract.loadIndex(catalog)
                // Accepted query-row guard: a foreign profile fails closed.
                FrozenMatcherS3Contract.requireS3Row(header.profileId)
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                true
            }
            if (catalogRejected) {
                mark(MatcherShadowStage.CATALOG, MatcherShadowStageStatus.FAILED, stageStartedAt)
                mark(MatcherShadowStage.GATE, MatcherShadowStageStatus.SKIPPED, clock())
                outcome = MatcherShadowOutcome.UNAVAILABLE_MIXED_VERSION
                return finish()
            }
            mark(MatcherShadowStage.CATALOG, MatcherShadowStageStatus.OK, stageStartedAt)

            // S4 frozen gate, honest inputs only ------------------------------
            cancellationCheck()
            stageStartedAt = clock()
            val decision = try {
                // No retrieval ran (S5 delivery is gated), so no baseline
                // accept exists and no image evidence was computed. This is
                // exactly the fail-closed input the frozen gate documents for
                // a caller that skips the baseline - nothing is fabricated.
                stages.gate(baselineAccepted = false, evidence = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mark(MatcherShadowStage.GATE, MatcherShadowStageStatus.FAILED, stageStartedAt)
                outcome = MatcherShadowOutcome.UNAVAILABLE_RUNTIME
                return finish()
            }
            mark(MatcherShadowStage.GATE, MatcherShadowStageStatus.OK, stageStartedAt)
            when (decision) {
                StrictCombinedDecision.Keep -> gate = MatcherShadowGateDecision.KEEP
                is StrictCombinedDecision.Reject -> {
                    gate = MatcherShadowGateDecision.REJECT
                    gateRejectReason = decision.reason
                }
            }
            // The decision is reduced to the bounded enums above and
            // discarded here - only diagnostics leave this function.
            outcome = MatcherShadowOutcome.COMPLETED_DISCARDED
            return finish()
        } catch (cancelled: CancellationException) {
            outcome = MatcherShadowOutcome.CANCELLED
            return finish()
        } catch (_: Throwable) {
            outcome = MatcherShadowOutcome.UNAVAILABLE_RUNTIME
            return finish()
        } finally {
            for (frame in ownedFrames) frame.wipe()
        }
    }

    private fun microsSince(startedAtNanos: Long, nowNanos: Long): Long =
        ((nowNanos - startedAtNanos) / 1_000L).coerceAtLeast(0L)
}
