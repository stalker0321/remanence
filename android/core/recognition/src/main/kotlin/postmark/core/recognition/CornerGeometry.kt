package postmark.core.recognition

/** Double-precision 2D point used across corner math. */
data class PointD(val x: Double, val y: Double)

/**
 * Four-corner ordering and validation math (docs/recognition.md section 4):
 * corners are canonically ordered clockwise from top-left in image
 * coordinates (y grows downward). All predicates are pure functions.
 */
object CornerGeometry {

    const val DEFAULT_EPSILON: Double = 1e-9

    /**
     * Canonicalizes any four distinct corners into clockwise-from-top-left
     * order. Throws when points coincide or collapse onto a line/point.
     */
    fun orderClockwiseFromTopLeft(corners: List<PointD>, epsilon: Double = DEFAULT_EPSILON): List<PointD> {
        require(corners.size == 4) { "exactly four corners required" }
        val unique = LinkedHashSet(corners)
        require(unique.size == 4) { "duplicate corners" }

        val centroidX = corners.sumOf { it.x } / 4.0
        val centroidY = corners.sumOf { it.y } / 4.0

        // Sort by angle around centroid, normalized into [0, 2*pi) so the
        // atan2 branch cut cannot split the polygon. Screen coordinates make
        // increasing normalized angle clockwise visually.
        fun normalizedAngle(p: PointD): Double {
            val TWO_PI = 2.0 * Math.PI
            val raw = kotlin.math.atan2(p.y - centroidY, p.x - centroidX)
            val positive = raw % TWO_PI
            return if (positive < 0) positive + TWO_PI else positive
        }
        val sorted = corners.sortedBy(::normalizedAngle)

        // Rotate so the top-left-most corner (min x+y) leads.
        val startIndex = sorted.indices.minByOrNull { idx ->
            val p = sorted[idx]
            p.x + p.y
        } ?: 0
        return List(4) { i -> sorted[(startIndex + i) % 4] }
    }

    /** Shoelace signed area; positive means clockwise in image coordinates. */
    fun signedArea(polygon: List<PointD>): Double {
        if (polygon.size < 3) return 0.0
        var sum = 0.0
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2.0
    }

    /** Convexity via cross-product sign consistency over consecutive edges. */
    fun isConvex(polygon: List<PointD>, epsilon: Double = DEFAULT_EPSILON): Boolean {
        if (polygon.size != 4) return false
        var sign = 0
        for (i in polygon.indices) {
            val o = polygon[i]
            val a = polygon[(i + 1) % polygon.size]
            val b = polygon[(i + 2) % polygon.size]
            val cross = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
            when {
                cross > epsilon -> {
                    if (sign < 0) return false
                    sign = 1
                }
                cross < -epsilon -> {
                    if (sign > 0) return false
                    sign = -1
                }
                else -> return false // collinear triple: degenerate corner
            }
        }
        return true
    }

    /** True when any two non-adjacent segments of the ordered polygon intersect. */
    fun selfIntersects(polygon: List<PointD>, epsilon: Double = DEFAULT_EPSILON): Boolean {
        if (polygon.size != 4) return false
        fun segmentsIntersect(p1: PointD, p2: PointD, p3: PointD, p4: PointD): Boolean {
            fun direction(a: PointD, b: PointD, c: PointD): Double =
                (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
            val d1 = direction(p3, p4, p1)
            val d2 = direction(p3, p4, p2)
            val d3 = direction(p1, p2, p3)
            val d4 = direction(p1, p2, p4)
            if (((d1 > epsilon && d2 < -epsilon) || (d1 < -epsilon && d2 > epsilon)) &&
                ((d3 > epsilon && d4 < -epsilon) || (d3 < -epsilon && d4 > epsilon))
            ) {
                return true
            }
            return false
        }
        // An ordered quad is simple iff its two pairs of OPPOSITE EDGES do
        // not cross (the diagonals of every convex quad always cross).
        return segmentsIntersect(polygon[0], polygon[1], polygon[2], polygon[3]) ||
            segmentsIntersect(polygon[1], polygon[2], polygon[3], polygon[0])
    }

    sealed interface QuadValidation {
        data class Valid(val orderedCorners: List<PointD>) : QuadValidation

        data class Invalid(val reason: Reason) : QuadValidation

        enum class Reason {
            WRONG_POINT_COUNT,
            DUPLICATE_CORNERS,
            DEGENERATE_AREA,
            NOT_CONVEX,
            SELF_INTERSECTING,
            WRONG_WINDING,
        }
    }

    /**
     * Full gate used before perspective warp: canonical order, strict convex,
     * simple (non-self-intersecting), positive clockwise area above [minArea].
     */
    fun validateQuad(
        corners: List<PointD>,
        minArea: Double = 1.0,
        epsilon: Double = DEFAULT_EPSILON,
    ): QuadValidation {
        if (corners.size != 4) return QuadValidation.Invalid(QuadValidation.Reason.WRONG_POINT_COUNT)
        if (LinkedHashSet(corners).size != 4) {
            return QuadValidation.Invalid(QuadValidation.Reason.DUPLICATE_CORNERS)
        }
        if (selfIntersects(corners, epsilon)) {
            return QuadValidation.Invalid(QuadValidation.Reason.SELF_INTERSECTING)
        }
        if (!isConvex(corners, epsilon)) {
            return QuadValidation.Invalid(QuadValidation.Reason.NOT_CONVEX)
        }
        val area = signedArea(corners)
        if (area <= minArea) {
            return QuadValidation.Invalid(QuadValidation.Reason.DEGENERATE_AREA)
        }
        return QuadValidation.Valid(corners)
    }
}
