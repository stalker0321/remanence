package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.GeneratorExpression
import kotlin.math.abs

/** UI crop state of the fixed-frame crop stage, in the frozen `model.mjs` domain. */
data class CropTransform(val z: Double, val xPercent: Double, val yPercent: Double)

/** One direction-button/key press: exactly one axis, ±1 frame percent. */
data class PanDelta(val xPercent: Double, val yPercent: Double)

/** The four crop direction buttons of `guide.mjs:41`. */
enum class PanDirection { LEFT, UP, DOWN, RIGHT }

/** Geometry/analysis precondition failure, evaluated before the frozen classifier. */
enum class GeometryReject {
    NONFINITE_VALUE,
    ZOOM_OUT_OF_DOMAIN,
    PAN_BEYOND_LIMIT,
    INVALID_SOURCE_DIMENSIONS,
    INVALID_SLOT,
    BASE_CROP_OUT_OF_SOURCE,
    FRAME_ASPECT_MISMATCH,
    WINDOW_OUT_OF_SOURCE,
    MISSING_ANALYSIS,
    ANALYSIS_DIMENSION_MISMATCH,
}

/**
 * Outcome of one correction: an admitted source window, or exactly one typed
 * reason. [geometryReject] and [safetyReject] are mutually exclusive;
 * [classifierEvaluated] is true only when the frozen seam actually ran.
 */
data class CorrectedCropWindow(
    val window: NormalizedCrop?,
    val transform: CropTransform,
    val geometryReject: GeometryReject?,
    val safetyReject: CropRejectReason?,
    val classifierEvaluated: Boolean,
) {
    val accepted: Boolean get() = window != null
}

/**
 * Correction state identity: expression/candidate identity + slot, guarded by
 * the content id so a replaced photo in the same slot discards its correction.
 */
data class CorrectionKey(
    val expressionIdentity: String,
    val slot: Int,
    val contentId: String,
) {
    fun matches(expressionIdentity: String, slot: Int, contentId: String): Boolean =
        this.expressionIdentity == expressionIdentity &&
            this.slot == slot &&
            this.contentId == contentId
}

/**
 * Fixed-frame crop transform math and safe window validation (T157).
 *
 * The frame (slot cell) is fixed; only the source moves beneath it. The UI
 * state is `translate(xPercent%, yPercent%) scale(z)` on the source fragment
 * inside the frame, with `z ∈ [1, 1.25]` and `|x|,|y| ≤ (z − 1) × 50` percent
 * (`motion/model.mjs:48-53`), percent of the **frame box** (`engine.mjs:114`).
 *
 * [sourceWindow] inverts that transform against the immutable solver-approved
 * crop; [correct] gates the result on every geometry precondition and on the
 * frozen classifier through the existing internal seam
 * `Envelope50CropSolver.rejectionReason`. Absence of a diagnostic reason is
 * never an acceptance by itself, an unclamped transform is rejected rather
 * than silently repaired, and a missing analysis is a rejection.
 *
 * Not provided here (see `tasks/T157/interface.md`): freeze/hash wiring,
 * `ExpressionChange` invalidation, quantization/seal of the corrected window,
 * UI state, or any solver/model change. Zoom is relative to the approved
 * original crop and never a wider automatic zoom search.
 */
object FixedFrameCropCorrection {

    const val ZOOM_MIN: Double = 1.0
    const val ZOOM_MAX: Double = 1.25
    const val ZOOM_STEP: Double = 0.01
    const val PAN_LIMIT_PERCENT_PER_UNIT_ZOOM: Double = 50.0

    /**
     * Relative tolerance for "the approved crop has the frame's aspect".
     * Solver-normalized windows match to floating-point noise; a 0..10000
     * quantized window fits with room to spare, a 0..1000 window of an
     * extremely narrow slot approaches it.
     */
    const val DEFAULT_ASPECT_TOLERANCE: Double = 5e-3

    private const val EPSILON = 1e-9

    /** `limit = (z − 1) × 50` percent; NaN in, NaN out. */
    fun panLimitPercent(z: Double): Double = (z - 1.0) * PAN_LIMIT_PERCENT_PER_UNIT_ZOOM

    /**
     * Exact `clampCrop` parity (`motion/model.mjs:48-53`): clamp `z` to
     * `[1, 1.25]` first, then clamp both pan axes to `(z − 1) × 50`. At `z = 1`
     * the limit is 0, so both axes clamp to exactly `0.0` (never `-0.0`).
     * Returns `null` for non-finite input instead of propagating NaN the way
     * JavaScript would; callers clamp before [correct].
     */
    fun clampTransform(z: Double, xPercent: Double, yPercent: Double): CropTransform? {
        if (!z.isFinite() || !xPercent.isFinite() || !yPercent.isFinite()) return null
        val zoom = z.coerceIn(ZOOM_MIN, ZOOM_MAX)
        val limit = panLimitPercent(zoom)
        if (limit <= 0.0) return CropTransform(zoom, 0.0, 0.0)
        return CropTransform(
            z = zoom,
            xPercent = xPercent.coerceIn(-limit, limit),
            yPercent = yPercent.coerceIn(-limit, limit),
        )
    }

    /** Direction-button/key press delta, identical to `engine.mjs:118 pan(dir)`. */
    fun panDelta(direction: PanDirection): PanDelta = when (direction) {
        PanDirection.LEFT -> PanDelta(-1.0, 0.0)
        PanDirection.RIGHT -> PanDelta(1.0, 0.0)
        PanDirection.UP -> PanDelta(0.0, -1.0)
        PanDirection.DOWN -> PanDelta(0.0, 1.0)
    }

    /**
     * Sealed integer window → normalized `[0,1]`. `null` for a non-positive
     * unit bound or non-positive size; out-of-source values are reported later
     * as [GeometryReject.BASE_CROP_OUT_OF_SOURCE] by [correct].
     */
    fun normalizedCrop(
        window: GeneratorExpression.CropWindow,
        unitMax: Int = GeneratorExpression.CROP_UNIT_MAX_V3,
    ): NormalizedCrop? {
        if (unitMax <= 0) return null
        val scale = unitMax.toDouble()
        val width = window.width / scale
        val height = window.height / scale
        if (!width.isFinite() || !height.isFinite() || width <= 0.0 || height <= 0.0) return null
        return NormalizedCrop(window.x / scale, window.y / scale, width, height)
    }

    /**
     * Pure inversion of the stage transform: the source window the frame shows
     * for [transform] over the approved crop [baseCrop].
     *
     * `left = bx + bw × (0.5 − (0.5 + x/100) / z)`, `width = bw / z`, and
     * likewise on y; the window centre is `bx + bw × (0.5 − (x/100)/z)`, so a
     * positive `xPercent` (photo moved right) moves the window left in the
     * source. Identity (`z=1, x=y=0`) returns [baseCrop] exactly. `null` for
     * non-finite input or a non-positive zoom.
     *
     * Preview/math only: persistence and sealing must go through [correct].
     */
    fun sourceWindow(baseCrop: NormalizedCrop, transform: CropTransform): NormalizedCrop? {
        if (!isFinite(baseCrop) || !isFinite(transform)) return null
        if (transform.z <= 0.0) return null
        val inverse = 1.0 / transform.z
        if (!inverse.isFinite()) return null
        val left = baseCrop.x + baseCrop.width * (0.5 - (0.5 + transform.xPercent / 100.0) * inverse)
        val top = baseCrop.y + baseCrop.height * (0.5 - (0.5 + transform.yPercent / 100.0) * inverse)
        val width = baseCrop.width * inverse
        val height = baseCrop.height * inverse
        if (!isFinite(NormalizedCrop(left, top, width, height))) return null
        return NormalizedCrop(left, top, width, height)
    }

    /**
     * Derive the corrected source window and admit it only when every
     * precondition holds. Preconditions are evaluated in a fixed order and the
     * first failure is reported:
     *
     * 1. all values finite → [GeometryReject.NONFINITE_VALUE]
     * 2. `z ∈ [1, 1.25]` → [GeometryReject.ZOOM_OUT_OF_DOMAIN]
     * 3. `|x|,|y| ≤ (z−1)×50` → [GeometryReject.PAN_BEYOND_LIMIT] (rejected,
     *    never silently re-clamped; clamp with [clampTransform] first)
     * 4. positive source dimensions → [GeometryReject.INVALID_SOURCE_DIMENSIONS]
     * 5. positive finite slot geometry → [GeometryReject.INVALID_SLOT]
     * 6. approved crop inside `[0,1]²` → [GeometryReject.BASE_CROP_OUT_OF_SOURCE]
     * 7. approved crop pixel aspect equals the slot aspect →
     *    [GeometryReject.FRAME_ASPECT_MISMATCH]
     * 8. derived window inside `[0,1]²` → [GeometryReject.WINDOW_OUT_OF_SOURCE]
     * 9. analysis present → [GeometryReject.MISSING_ANALYSIS]
     * 10. analysis dimensions equal the source dimensions →
     *     [GeometryReject.ANALYSIS_DIMENSION_MISMATCH]
     * 11. frozen classifier (`Envelope50CropSolver.rejectionReason`) returns
     *     `null` → accepted, otherwise [CorrectedCropWindow.safetyReject] holds
     *     the frozen [CropRejectReason].
     *
     * Steps 1–10 are additional to the classifier, which assumes a well-formed
     * in-bounds box of the right aspect. Acceptance therefore always proves
     * geometry, source bounds, frame aspect, analysis presence/consistency, and
     * frozen safety **and resolution** coverage with `classifierEvaluated`.
     * [baseCrop] is never re-solved and `slot.zooms` is never consulted: zoom is
     * relative to the approved original crop.
     */
    fun correct(
        baseCrop: NormalizedCrop,
        slot: Envelope50CropSolver.Slot,
        sourceWidth: Int,
        sourceHeight: Int,
        transform: CropTransform,
        analysis: CollageAnalysis?,
    ): CorrectedCropWindow {
        fun geometryReject(reason: GeometryReject) =
            CorrectedCropWindow(null, transform, reason, null, classifierEvaluated = false)

        if (!isFinite(baseCrop) || !isFinite(transform)) {
            return geometryReject(GeometryReject.NONFINITE_VALUE)
        }
        if (transform.z < ZOOM_MIN || transform.z > ZOOM_MAX) {
            return geometryReject(GeometryReject.ZOOM_OUT_OF_DOMAIN)
        }
        val limit = panLimitPercent(transform.z)
        if (abs(transform.xPercent) > limit + EPSILON || abs(transform.yPercent) > limit + EPSILON) {
            return geometryReject(GeometryReject.PAN_BEYOND_LIMIT)
        }
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return geometryReject(GeometryReject.INVALID_SOURCE_DIMENSIONS)
        }
        if (!slot.width.isFinite() || !slot.height.isFinite() || slot.width <= 0.0 || slot.height <= 0.0) {
            return geometryReject(GeometryReject.INVALID_SLOT)
        }
        if (baseCrop.width <= 0.0 || baseCrop.height <= 0.0 ||
            baseCrop.x < -EPSILON || baseCrop.y < -EPSILON ||
            baseCrop.x + baseCrop.width > 1.0 + EPSILON ||
            baseCrop.y + baseCrop.height > 1.0 + EPSILON
        ) {
            return geometryReject(GeometryReject.BASE_CROP_OUT_OF_SOURCE)
        }
        val slotAspect = slot.width / slot.height
        val cropAspect =
            (baseCrop.width * sourceWidth) / (baseCrop.height * sourceHeight)
        if (abs(cropAspect - slotAspect) / slotAspect > DEFAULT_ASPECT_TOLERANCE) {
            return geometryReject(GeometryReject.FRAME_ASPECT_MISMATCH)
        }
        val window = sourceWindow(baseCrop, transform)
            ?: return geometryReject(GeometryReject.NONFINITE_VALUE)
        if (window.width <= 0.0 || window.height <= 0.0 ||
            window.x < -EPSILON || window.y < -EPSILON ||
            window.x + window.width > 1.0 + EPSILON ||
            window.y + window.height > 1.0 + EPSILON
        ) {
            return geometryReject(GeometryReject.WINDOW_OUT_OF_SOURCE)
        }
        if (analysis == null) {
            return geometryReject(GeometryReject.MISSING_ANALYSIS)
        }
        if (analysis.width != sourceWidth || analysis.height != sourceHeight) {
            return geometryReject(GeometryReject.ANALYSIS_DIMENSION_MISMATCH)
        }
        val reason = Envelope50CropSolver.rejectionReason(
            analysis = analysis,
            slot = slot,
            rect = NormBox(window.x, window.y, window.width, window.height),
            shape = slot.shape,
        )
        return if (reason == null) {
            CorrectedCropWindow(window, transform, null, null, classifierEvaluated = true)
        } else {
            CorrectedCropWindow(null, transform, null, reason, classifierEvaluated = true)
        }
    }

    /**
     * Correction state key factory: expression/candidate identity + slot +
     * content-id guard. `null` for a blank identity, a negative slot or a blank
     * content id — an unusable identity never yields a usable key.
     */
    fun correctionKey(
        expressionIdentity: String,
        slot: Int,
        contentId: String,
    ): CorrectionKey? {
        if (expressionIdentity.isBlank() || slot < 0 || contentId.isBlank()) return null
        return CorrectionKey(expressionIdentity, slot, contentId)
    }

    private fun isFinite(crop: NormalizedCrop): Boolean =
        crop.x.isFinite() && crop.y.isFinite() && crop.width.isFinite() && crop.height.isFinite()

    private fun isFinite(transform: CropTransform): Boolean =
        transform.z.isFinite() && transform.xPercent.isFinite() && transform.yPercent.isFinite()
}
