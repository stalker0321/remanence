package postmark.core.recognition

import kotlin.math.abs

/**
 * M1-M03 result (docs/recognition.md section 7 "Spatial coverage"): the
 * normalized convex-hull areas of inlier points on both sides plus the 4x4
 * occupancy-grid counts. The binding coverage value is the smaller hull area;
 * the binding cell count is the smaller occupied-cell count.
 */
data class SpatialCoverage(
    val queryHullAreaNormalized: Double,
    val referenceHullAreaNormalized: Double,
    val queryOccupiedCells: Int,
    val referenceOccupiedCells: Int,
) {
    val hullAreaNormalized: Double get() = minOf(queryHullAreaNormalized, referenceHullAreaNormalized)
    val occupiedGridCells: Int get() = minOf(queryOccupiedCells, referenceOccupiedCells)
}

/**
 * Computes spatial spread of RANSAC inliers: normalized convex-hull area per
 * side and occupancy over the configured grid. Prevents a stamp corner or a
 * short word from dominating an entire-card claim.
 */
class SpatialCoverageMeter(
    private val gridSize: Int,
) {

    init {
        require(gridSize >= 2) { "coverage grid must be at least 2x2" }
    }

    fun measure(
        queryInliers: List<Pair<Double, Double>>,
        referenceInliers: List<Pair<Double, Double>>,
    ): SpatialCoverage {
        require(queryInliers.isNotEmpty() && referenceInliers.isNotEmpty()) {
            "coverage requires inlier points on both sides"
        }
        val queryHull = ConvexHull.area(queryInliers)
        val referenceHull = ConvexHull.area(referenceInliers)
        return SpatialCoverage(
            queryHullAreaNormalized = queryHull,
            referenceHullAreaNormalized = referenceHull,
            queryOccupiedCells = occupiedCells(queryInliers),
            referenceOccupiedCells = occupiedCells(referenceInliers),
        )
    }

    private fun occupiedCells(points: List<Pair<Double, Double>>): Int {
        val cells = HashSet<Int>()
        points.forEach { (x, y) ->
            if (x.isFinite() && y.isFinite()) {
                var col = (x * gridSize).toInt()
                var row = (y * gridSize).toInt()
                if (col >= gridSize) col = gridSize - 1
                if (row >= gridSize) row = gridSize - 1
                if (col >= 0 && row >= 0) {
                    cells += row * gridSize + col
                }
            }
        }
        return cells.size
    }
}

/** Monotone-chain convex hull with shoelace area over normalized coordinates. */
internal object ConvexHull {

    fun area(points: List<Pair<Double, Double>>): Double {
        if (points.size < 3) return ZERO
        val hull = monotoneChain(points)
        if (hull.size < 3) return ZERO
        var twice = ZERO
        for (i in hull.indices) {
            val (ax, ay) = hull[i]
            val (bx, by) = hull[(i + 1) % hull.size]
            twice += ax * by - bx * ay
        }
        return abs(twice) / TWO
    }

    private fun monotoneChain(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val sorted = points.sortedWith(compareBy({ it.first }, { it.second })).toMutableList()
        // Deduplicate exact duplicates; repeated points break strict turns.
        val unique = ArrayList<Pair<Double, Double>>(sorted.size)
        sorted.forEach { point ->
            if (unique.isEmpty() || unique.last() != point) unique += point
        }
        if (unique.size < 3) return unique

        val lower = ArrayList<Pair<Double, Double>>(unique.size)
        unique.forEach { p ->
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= ZERO) {
                lower.removeAt(lower.size - 1)
            }
            lower += p
        }
        val upper = ArrayList<Pair<Double, Double>>(unique.size)
        unique.asReversed().forEach { p ->
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= ZERO) {
                upper.removeAt(upper.size - 1)
            }
            upper += p
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    private fun cross(o: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
        (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)

    private const val TWO = 2.0
    private const val ZERO = 0.0
}
