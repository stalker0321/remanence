package postmark.core.recognition

/**
 * Manual four-corner crop input model (docs/recognition.md section 3 step 5).
 * Pure state + validation only; no UI and no OpenCV types cross this file.
 */
data class ManualCropQuad(
    val corners: List<PointD>,
    val frameWidth: Int,
    val frameHeight: Int,
) {

    sealed interface Validation {
        data class Valid(val orderedCorners: List<PointD>) : Validation

        data class Invalid(val reason: Reason) : Validation

        enum class Reason {
            WRONG_POINT_COUNT,
            DUPLICATE_CORNERS,
            OUT_OF_FRAME,
            NOT_CONVEX,
            SELF_INTERSECTING,
            DEGENERATE_AREA,
        }
    }

    /**
     * Validates the raw user-dragged corners against the capture frame:
     * exactly four distinct points, every corner inside the frame, the quad
     * simple/convex, and its canonical (clockwise-from-top-left) ordering
     * carrying positive area above [minAreaPx].
     */
    fun validate(
        minAreaPx: Double = DEFAULT_MIN_AREA_PX,
        epsilon: Double = CornerGeometry.DEFAULT_EPSILON,
    ): Validation {
        if (corners.size != 4) return Validation.Invalid(Validation.Reason.WRONG_POINT_COUNT)
        if (LinkedHashSet(corners).size != 4) return Validation.Invalid(Validation.Reason.DUPLICATE_CORNERS)
        for (point in corners) {
            val inside = point.x >= -epsilon && point.y >= -epsilon &&
                point.x <= frameWidth + epsilon && point.y <= frameHeight + epsilon
            if (!inside) return Validation.Invalid(Validation.Reason.OUT_OF_FRAME)
        }
        if (CornerGeometry.selfIntersects(corners, epsilon)) {
            return Validation.Invalid(Validation.Reason.SELF_INTERSECTING)
        }
        if (!CornerGeometry.isConvex(corners, epsilon)) {
            return Validation.Invalid(Validation.Reason.NOT_CONVEX)
        }
        val area = CornerGeometry.signedArea(corners)
        if (area <= minAreaPx) return Validation.Invalid(Validation.Reason.DEGENERATE_AREA)
        return Validation.Valid(CornerGeometry.orderClockwiseFromTopLeft(corners, epsilon))
    }

    companion object {
        const val DEFAULT_MIN_AREA_PX: Double = 400.0
    }
}
