package dev.hryshyn.remanence.scan.matcher

import dev.hryshyn.remanence.core.recognition.StrictCombinedRejectReason

/**
 * Bounded shadow outcome. Exactly one value is recorded per shadow run; no
 * free-form failure text, image, path, identity, or provider token is
 * representable here.
 *
 * * [COMPLETED_DISCARDED] - S1..S4 all ran; the gate decision was reduced to
 *   bounded scalars and discarded (it can never grant/open/choose).
 * * [CANCELLED] - cancellation observed; nothing was reported as success.
 * * [UNAVAILABLE_INPUT] - undecodable/out-of-range frame input.
 * * [UNAVAILABLE_LOCALIZER] - S2 returned no proposal (bounded fail-closed).
 * * [UNAVAILABLE_CATALOG_MISSING] - no S5 catalog bytes delivered (lead-owned
 *   per T017/T081/T116); never fabricated here.
 * * [UNAVAILABLE_MIXED_VERSION] - the accepted S3 contract rejected the
 *   catalog/query input (foreign format, profile, or fingerprint).
 * * [UNAVAILABLE_RUNTIME] - any other bounded failure.
 */
enum class MatcherShadowOutcome {
    COMPLETED_DISCARDED,
    CANCELLED,
    UNAVAILABLE_INPUT,
    UNAVAILABLE_LOCALIZER,
    UNAVAILABLE_CATALOG_MISSING,
    UNAVAILABLE_MIXED_VERSION,
    UNAVAILABLE_RUNTIME,
}

/** Ordered shadow stages; mirrors freeze `run.py` decode/frame/localize order. */
enum class MatcherShadowStage {
    DECODE,
    FRAME_GEOMETRY,
    LOCALIZE,
    CATALOG,
    GATE,
}

/** Per-stage execution status; a stage never carries payload bytes or text. */
enum class MatcherShadowStageStatus {
    OK,
    SKIPPED,
    FAILED,
}

/** Reduced S4 gate verdict; the sealed decision itself is never retained. */
enum class MatcherShadowGateDecision {
    NOT_RUN,
    KEEP,
    REJECT,
}

/** One stage timing scalar: non-negative microseconds only. */
data class MatcherShadowStageTiming(
    val stage: MatcherShadowStage,
    val status: MatcherShadowStageStatus,
    val latencyMicros: Long,
) {
    init {
        require(latencyMicros >= 0L) { "latency must be non-negative" }
    }
}

/**
 * Telemetry containing ONLY enums and numeric scalars. It intentionally has
 * no image bytes, paths, identity, handles, provider names, exception text,
 * or decision payloads - [safeSummary] is therefore always redaction-safe.
 */
data class MatcherShadowDiagnostics(
    val outcome: MatcherShadowOutcome,
    val stages: List<MatcherShadowStageTiming>,
    val totalLatencyMicros: Long,
    val peakHeapBytes: Long,
    val gate: MatcherShadowGateDecision,
    val gateRejectReason: StrictCombinedRejectReason?,
) {
    init {
        require(totalLatencyMicros >= 0L) { "total latency must be non-negative" }
        require(peakHeapBytes >= 0L) { "peak heap must be non-negative" }
        require(gateRejectReason == null || gate == MatcherShadowGateDecision.REJECT) {
            "a reject reason requires a REJECT decision"
        }
    }

    /** Stable redacted representation suitable for a DEBUG log line. */
    fun safeSummary(): String = buildString {
        append("shadow outcome=").append(outcome.name)
        append(" totalMicros=").append(totalLatencyMicros)
        append(" peakHeapBytes=").append(peakHeapBytes)
        append(" gate=").append(gate.name)
        append(" gateReason=").append(gateRejectReason?.code ?: "n/a")
        for (stage in stages) {
            append(' ').append(stage.stage.name)
                .append(':').append(stage.status.name)
                .append(':').append(stage.latencyMicros)
        }
    }
}

/** Where bounded shadow diagnostics are delivered. */
fun interface MatcherShadowSink {
    fun report(diagnostics: MatcherShadowDiagnostics)
}

/**
 * Process-bounded collector: keeps at most [capacity] newest entries, drops
 * the oldest, and holds only [MatcherShadowDiagnostics] (scalars/enums).
 */
class BoundedMatcherShadowLog(private val capacity: Int = 64) : MatcherShadowSink {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val entries = ArrayDeque<MatcherShadowDiagnostics>()

    override fun report(diagnostics: MatcherShadowDiagnostics) {
        synchronized(entries) {
            if (entries.size >= capacity) entries.removeFirst()
            entries.addLast(diagnostics)
        }
    }

    fun snapshot(): List<MatcherShadowDiagnostics> = synchronized(entries) { entries.toList() }

    fun size(): Int = synchronized(entries) { entries.size }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}

/**
 * The single process-bounded collector the production shadow reports into.
 * [MatcherShadowTelemetry.sink] never receives image, path, identity, or
 * decision payload - only [MatcherShadowDiagnostics].
 */
object MatcherShadowTelemetry {
    private val log = BoundedMatcherShadowLog(capacity = 64)

    val sink: MatcherShadowSink get() = log

    fun snapshot(): List<MatcherShadowDiagnostics> = log.snapshot()

    fun clear() {
        log.clear()
    }
}
