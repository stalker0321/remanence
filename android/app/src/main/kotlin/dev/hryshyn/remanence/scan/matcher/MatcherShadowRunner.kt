package dev.hryshyn.remanence.scan.matcher

/**
 * One owned shadow input copy: a private snapshot of the delivered FRONT
 * JPEG, taken ONLY while the local strategy switch is on. The scan flow owns
 * the original v1 bytes either way; this copy exists solely for the shadow
 * run and is wiped on every path (run, reject, stale, cancel, teardown).
 * [wipe] is idempotent.
 */
class MatcherShadowInput internal constructor(
    val bytes: ByteArray,
) {
    @Volatile
    private var wiped = false

    val isWiped: Boolean get() = wiped

    fun wipe() {
        if (!wiped) {
            wiped = true
            bytes.fill(0)
        }
    }
}

/**
 * Owns the injectable/local strategy switch, the accepted-code seam, and the
 * bounded sink for one scan surface.
 *
 * Contract:
 * * [snapshot] copies input only when [MatcherShadowConfig.enabled]; when
 *   disabled it returns null and the scan flow provably does nothing extra.
 * * [runOwned] always wipes its input (even on unexpected failure) and always
 *   leaves cancellation state to the owning coroutine - it never rethrows a
 *   shadow failure into the scan flow.
 * * The diagnostics handed to the sink are scalar-only; no decision, grant,
 *   chooser, or v1 state is reachable from this type.
 */
open class MatcherShadowRunner(
    val config: MatcherShadowConfig,
    stages: MatcherShadowStages,
    private val sink: MatcherShadowSink,
) {
    private val pipeline = MatcherShadowPipeline(stages)

    /** Copies [jpeg] iff the switch is on; null means "disabled, do nothing". */
    open fun snapshot(jpeg: ByteArray): MatcherShadowInput? {
        if (!config.enabled) return null
        return MatcherShadowInput(jpeg.copyOf())
    }

    /**
     * Runs the shadow over the owned [input], reports bounded diagnostics to
     * the sink, and wipes the input on every path. [cancellationCheck] is the
     * owning coroutine's cooperative cancellation probe.
     */
    fun runOwned(
        input: MatcherShadowInput,
        cancellationCheck: () -> Unit = {},
    ): MatcherShadowDiagnostics {
        try {
            val diagnostics = pipeline.run(input.bytes, cancellationCheck)
            sink.report(diagnostics)
            return diagnostics
        } finally {
            input.wipe()
        }
    }

    companion object {
        /** Disabled runner: snapshot returns null, nothing ever runs. */
        fun disabled(sink: MatcherShadowSink = MatcherShadowSink { }): MatcherShadowRunner =
            MatcherShadowRunner(MatcherShadowConfig.off(), NoopMatcherShadowStages, sink)
    }
}

/**
 * Never-executed stage binding used only by the disabled runner; every
 * member fails closed if it were ever reached, which cannot happen because
 * a disabled runner never snapshots input.
 */
internal object NoopMatcherShadowStages : MatcherShadowStages {
    override fun decode(jpeg: ByteArray): MatcherShadowFrame? = null

    override fun localize(
        frameBgr: ByteArray,
        width: Int,
        height: Int,
    ): MatcherShadowLocalizerOutcome = MatcherShadowLocalizerOutcome.Unknown

    override fun catalogIndex(): ByteArray? = null

    override fun gate(
        baselineAccepted: Boolean,
        evidence: dev.hryshyn.remanence.core.recognition.StrictCombinedEvidence?,
    ): dev.hryshyn.remanence.core.recognition.StrictCombinedDecision =
        dev.hryshyn.remanence.core.recognition.StrictCombinedDecision.Reject(
            dev.hryshyn.remanence.core.recognition.StrictCombinedRejectReason.BASELINE_NOT_ACCEPTED,
        )
}
