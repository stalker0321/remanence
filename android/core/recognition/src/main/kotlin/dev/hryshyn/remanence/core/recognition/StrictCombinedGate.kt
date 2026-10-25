package dev.hryshyn.remanence.core.recognition

/**
 * Image-derived evidence for the frozen `strict_combined` safety gate (S4).
 *
 * The decision surface reads **only** these five image-derived values. Card
 * identity, expected value, path, scenario, batch and environment are
 * deliberately not representable here — the frozen contract allows no
 * identity-, scenario- or environment-conditioned threshold, and the gate must
 * be replayable from a matcher trace alone.
 */
data class StrictCombinedEvidence(
    /** Frozen matcher says the homography/geometry was verified. */
    val geometryVerified: Boolean,
    /** Frozen matcher score (final, after the frozen floors). */
    val finalScore: Double,
    /** Inlier / match ratio of the accepted homography. */
    val inlierRatio: Double,
    /** Reference coverage of the accepted homography. */
    val coverage: Double,
    /** Number of connected components in the localizer mask. */
    val components: Int,
)

/**
 * Reject reasons for the frozen gate.
 *
 * The first five codes are byte-identical to the frozen policy's reason
 * strings (`frame_first_safety_v2/policies.py::decide`); their declaration
 * order also fixes the frozen check order. The last two are fail-closed
 * additions for inputs the frozen Python never had to model (a caller that
 * skips the baseline, and invalid/non-finite/incomplete evidence).
 */
enum class StrictCombinedRejectReason(val code: String) {
    /** Caller passed a baseline reject: never promoted. */
    BASELINE_NOT_ACCEPTED("baseline_not_accepted"),
    /** Missing, non-finite or out-of-range evidence: fail closed. */
    INVALID_EVIDENCE("invalid_evidence"),
    GEOMETRY_NOT_VERIFIED("geometry_not_verified"),
    FINAL_SCORE_BELOW_FLOOR("final_score_below_floor"),
    INLIER_RATIO_BELOW_FLOOR("inlier_ratio_below_floor"),
    COVERAGE_BELOW_FLOOR("coverage_below_floor"),
    MULTIPLE_COMPONENTS_NOT_ADMITTED("multiple_components_not_admitted"),
}

/** Result of the gate: keep the baseline accept, or reject it. */
sealed interface StrictCombinedDecision {
    data object Keep : StrictCombinedDecision

    data class Reject(val reason: StrictCombinedRejectReason) : StrictCombinedDecision {
        val code: String get() = reason.code
    }
}

/**
 * Frozen `strict_combined` reject-only acceptance gate (S4).
 *
 * Ported verbatim from the authoritative
 * `evidence/frame_first_safety_v2/policies.py` preset and `decide()`:
 *
 * ```
 * require_geometry=true, max_components=1, multi_min_coverage=0.5,
 * multi_min_inlier_ratio=0.9, min_score=20, min_coverage=0.2,
 * min_inlier_ratio=0.85
 * ```
 *
 * Contract:
 *  - the gate runs **only after** a baseline matcher accept and may only keep
 *    or reject it; a baseline reject is never promoted;
 *  - the frozen check order and reason strings are preserved exactly;
 *  - invalid, non-finite or incomplete evidence fails closed;
 *  - no identity, expected value, path, scenario, batch or environment input
 *    exists on the decision surface.
 *
 * Reject is success: a rejected top-1 is surfaced as `unknown` (retry UX), never
 * as a match.
 */
object StrictCombinedGate {
    /** Frozen preset name (`policies.py::presets()["strict_combined"]`). */
    const val NAME: String = "strict_combined"

    /** Version pin for the decision surface; bump only with a frozen re-run. */
    const val VERSION: String = "strict_combined/1"

    const val MIN_SCORE: Double = 20.0
    const val MIN_INLIER_RATIO: Double = 0.85
    const val MIN_COVERAGE: Double = 0.20
    const val MAX_COMPONENTS: Int = 1
    const val MULTI_MIN_COVERAGE: Double = 0.50
    const val MULTI_MIN_INLIER_RATIO: Double = 0.90

    /**
     * Keep [evidence] only if every frozen constraint passes.
     *
     * @param baselineAccepted the frozen matcher's own accept/reject. A reject
     *   is returned unchanged ([StrictCombinedRejectReason.BASELINE_NOT_ACCEPTED]);
     *   this gate can never promote it.
     * @param evidence image-derived evidence, or `null` to fail closed.
     */
    fun decide(
        baselineAccepted: Boolean,
        evidence: StrictCombinedEvidence?,
    ): StrictCombinedDecision = decideWith(baselineAccepted, evidence, MIN_INLIER_RATIO)

    /**
     * The same gate with [DECISIVE_MIN_INLIER_RATIO] as the inlier-ratio
     * floor, for a decisive winner (the caller checks score and separation).
     */
    fun decideDecisive(
        baselineAccepted: Boolean,
        evidence: StrictCombinedEvidence?,
    ): StrictCombinedDecision = decideWith(baselineAccepted, evidence, DECISIVE_MIN_INLIER_RATIO)

    /** Inlier-ratio floor for a decisive winner; see [decideDecisive]. */
    const val DECISIVE_MIN_INLIER_RATIO: Double = 0.6

    private fun decideWith(
        baselineAccepted: Boolean,
        evidence: StrictCombinedEvidence?,
        minInlierRatio: Double,
    ): StrictCombinedDecision {
        if (!baselineAccepted) {
            return StrictCombinedDecision.Reject(
                StrictCombinedRejectReason.BASELINE_NOT_ACCEPTED,
            )
        }
        val ev = evidence ?: return reject(StrictCombinedRejectReason.INVALID_EVIDENCE)
        if (!ev.finalScore.isFinite() ||
            !ev.inlierRatio.isFinite() ||
            !ev.coverage.isFinite()
        ) {
            return reject(StrictCombinedRejectReason.INVALID_EVIDENCE)
        }
        if (ev.components < 0) {
            return reject(StrictCombinedRejectReason.INVALID_EVIDENCE)
        }

        // Frozen check order — do not reorder: the recorded reason must match
        // the first constraint the image actually violated.
        if (!ev.geometryVerified) {
            return reject(StrictCombinedRejectReason.GEOMETRY_NOT_VERIFIED)
        }
        if (ev.finalScore < MIN_SCORE) {
            return reject(StrictCombinedRejectReason.FINAL_SCORE_BELOW_FLOOR)
        }
        if (ev.inlierRatio < minInlierRatio) {
            return reject(StrictCombinedRejectReason.INLIER_RATIO_BELOW_FLOOR)
        }
        if (ev.coverage < MIN_COVERAGE) {
            return reject(StrictCombinedRejectReason.COVERAGE_BELOW_FLOOR)
        }
        if (ev.components > MAX_COMPONENTS) {
            val admitted = ev.coverage >= MULTI_MIN_COVERAGE &&
                ev.inlierRatio >= MULTI_MIN_INLIER_RATIO
            if (!admitted) {
                return reject(StrictCombinedRejectReason.MULTIPLE_COMPONENTS_NOT_ADMITTED)
            }
        }
        return StrictCombinedDecision.Keep
    }

    private fun reject(reason: StrictCombinedRejectReason): StrictCombinedDecision =
        StrictCombinedDecision.Reject(reason)
}
