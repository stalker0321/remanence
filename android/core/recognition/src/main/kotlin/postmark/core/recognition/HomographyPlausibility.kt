package postmark.core.recognition

import kotlin.math.abs

/** M1-M04 outcome for every documented plausibility signal plus the verdict. */
data class HomographyPlausibilityReport(
    val finiteQuad: Boolean,
    val convexQuad: Boolean,
    val orientationPreserved: Boolean,
    val mappedAreaRatio: Double,
    val maxOppositeEdgeRatio: Double,
    val medianErrorWithinLimit: Boolean,
    private val areaRatioMin: Double,
    private val areaRatioMax: Double,
    private val oppositeEdgeRatioMax: Double,
) {
    val plausible: Boolean
        get() = finiteQuad && convexQuad && orientationPreserved &&
            mappedAreaRatio >= areaRatioMin && mappedAreaRatio <= areaRatioMax &&
            maxOppositeEdgeRatio <= oppositeEdgeRatioMax && medianErrorWithinLimit
}

/**
 * Hard geometry gates for a candidate homography (docs/recognition.md
 * section 7 "Homography plausibility"): the transformed reference corners
 * must form a finite, convex, non-self-intersecting quadrilateral preserving
 * orientation, keep the mapped area inside [areaRatioMin, areaRatioMax], keep
 * every opposite edge pair within [oppositeEdgeRatioMax]x, and carry a median
 * inlier reprojection error within its configured limit. Any violation is a
 * hard rejection, never a score penalty.
 */
class HomographyPlausibilityGate(
    private val areaRatioMin: Double,
    private val areaRatioMax: Double,
    private val oppositeEdgeRatioMax: Double,
) {

    constructor(match: RecognitionProfile.MatchThresholds) : this(
        areaRatioMin = match.homographyAreaRatioMin,
        areaRatioMax = match.homographyAreaRatioMax,
        oppositeEdgeRatioMax = match.homographyMaxOppositeEdgeRatio,
    )

    fun check(
        matrix: DoubleArray,
        medianInlierErrorNormalized: Double,
        medianErrorLimitNormalized: Double,
        referenceCorners: List<Pair<Double, Double>> = DEFAULT_CARD_CORNERS,
    ): HomographyPlausibilityReport {
        require(matrix.size == NINE) { "homography must have nine entries" }
        require(referenceCorners.size == CORNER_COUNT) { "four reference corners required" }

        val transformed = referenceCorners.map { (x, y) ->
            val dx = matrix[0] * x + matrix[1] * y + matrix[2]
            val dy = matrix[3] * x + matrix[4] * y + matrix[5]
            val dw = matrix[6] * x + matrix[7] * y + matrix[8]
            Triple(dx, dy, dw)
        }
        val finite = transformed.all { (dx, dy, dw) ->
            dw != ZERO && dx.isFinite() && dy.isFinite() && abs(dw).isFinite()
        }
        if (!finite) {
            return HomographyPlausibilityReport(
                finiteQuad = false,
                convexQuad = false,
                orientationPreserved = false,
                mappedAreaRatio = ZERO,
                maxOppositeEdgeRatio = Double.MAX_VALUE,
                medianErrorWithinLimit = false,
                areaRatioMin = areaRatioMin,
                areaRatioMax = areaRatioMax,
                oppositeEdgeRatioMax = oppositeEdgeRatioMax,
            )
        }

        // Projective maps can send individual points behind the camera line;
        // a sign change in w means the projected quad self-intersects there.
        val positiveW = transformed.all { (_, _, dw) -> dw > ZERO } || transformed.all { (_, _, dw) -> dw < ZERO }
        val quad = transformed.map { (dx, dy, dw) -> dx / dw to dy / dw }
        val allFinitePoints = quad.all { (x, y) -> x.isFinite() && y.isFinite() }
        val finiteQuad = positiveW && allFinitePoints

        val signedAreaTwice = quad.indices.sumOf { i ->
            val (ax, ay) = quad[i]
            val (bx, by) = quad[(i + 1) % quad.size]
            ax * by - bx * ay
        }
        val orientationPreserved = signedAreaTwice > ORIENTATION_EPSILON

        val convex = convexNonSelfIntersecting(quad)
        val mappedAreaRatio = abs(signedAreaTwice) / TWO / REFERENCE_AREA

        var worstOppositeEdgeRatio = ONE
        val edgeLengths = quad.indices.map { i ->
            val (ax, ay) = quad[i]
            val (bx, by) = quad[(i + 1) % quad.size]
            val ex = bx - ax
            val ey = by - ay
            kotlin.math.sqrt(ex * ex + ey * ey)
        }
        for (i in 0 until EDGE_PAIRS) {
            val short = minOf(edgeLengths[i], edgeLengths[i + OPPOSITE_OFFSET])
            val long = maxOf(edgeLengths[i], edgeLengths[i + OPPOSITE_OFFSET])
            if (short <= LENGTH_EPSILON) {
                worstOppositeEdgeRatio = Double.MAX_VALUE
                break
            }
            worstOppositeEdgeRatio = maxOf(worstOppositeEdgeRatio, long / short)
        }

        return HomographyPlausibilityReport(
            finiteQuad = finiteQuad,
            convexQuad = convex,
            orientationPreserved = orientationPreserved,
            mappedAreaRatio = mappedAreaRatio,
            maxOppositeEdgeRatio = worstOppositeEdgeRatio,
            medianErrorWithinLimit = medianInlierErrorNormalized <= medianErrorLimitNormalized,
            areaRatioMin = areaRatioMin,
            areaRatioMax = areaRatioMax,
            oppositeEdgeRatioMax = oppositeEdgeRatioMax,
        )
    }

    /** Strictly convex polygon test: every consecutive turn shares one sign. */
    private fun convexNonSelfIntersecting(quad: List<Pair<Double, Double>>): Boolean {
        var sign = ZERO
        for (i in quad.indices) {
            val o = quad[i]
            val a = quad[(i + 1) % quad.size]
            val b = quad[(i + 2) % quad.size]
            val cross = (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
            when {
                cross > TURN_EPSILON -> {
                    if (sign < ZERO) return false
                    sign = ONE
                }
                cross < -TURN_EPSILON -> {
                    if (sign > ZERO) return false
                    sign = -ONE
                }
                else -> return false
            }
        }
        return true
    }

    internal companion object {
        const val NINE = 9
        const val CORNER_COUNT = 4
        const val EDGE_PAIRS = 2
        const val OPPOSITE_OFFSET = 2
        const val TWO = 2.0
        const val ONE = 1.0
        const val ZERO = 0.0
        const val ORIENTATION_EPSILON = 1e-12
        const val TURN_EPSILON = 1e-12
        const val LENGTH_EPSILON = 1e-12
        const val REFERENCE_AREA = 1.0

        /** Reference postcard corners in normalized coordinates (CCW). */
        val DEFAULT_CARD_CORNERS = listOf(
            ZERO to ZERO,
            ONE to ZERO,
            ONE to ONE,
            ZERO to ONE,
        )
    }
}
