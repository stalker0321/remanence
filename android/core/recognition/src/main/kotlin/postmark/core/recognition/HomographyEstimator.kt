package postmark.core.recognition

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/** One query-reference point correspondence in normalized card coordinates. */
data class MatchPoint(
    val queryX: Double,
    val queryY: Double,
    val referenceX: Double,
    val referenceY: Double,
)

/**
 * M1-M02 result: the RANSAC-estimated homography mapping REFERENCE card
 * coordinates onto QUERY coordinates, the surviving correspondence indices,
 * and the raw geometric signals consumed by scoring.
 */
data class HomographyReport(
    val success: Boolean,
    val matrix: DoubleArray?,
    val inlierIndices: List<Int>,
    val inlierCount: Int,
    val inlierRatio: Double,
    val medianInlierErrorNormalized: Double,
) {
    override fun equals(other: Any?): Boolean = other is HomographyReport && inlierIndices == other.inlierIndices

    override fun hashCode(): Int = inlierIndices.hashCode()
}

/**
 * Estimates the query-to-reference homography with Hartley-normalized
 * normalized-DLT, 4-point RANSAC, and an inlier refinement pass
 * (docs/recognition.md section 7 steps 5–6). The default tolerance is the
 * configured reprojection tolerance expressed on the canonical long edge.
 * All iteration is driven by a fixed seed so reports are reproducible.
 */
class HomographyEstimator(
    private val toleranceNormalized: Double = DEFAULT_TOLERANCE_NORMALIZED,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    private val minIterations: Int = DEFAULT_MIN_ITERATIONS,
    private val seed: Long = DEFAULT_SEED,
) {

    fun estimate(points: List<MatchPoint>): HomographyReport {
        if (points.size < MIN_POINTS) {
            return failure(points.size)
        }
        val random = Random(seed)
        var bestInliers: List<Int> = emptyList()
        var bestErrorSum = Double.MAX_VALUE

        for (iteration in 0 until maxIterations) {
            if (iteration >= minIterations &&
                bestInliers.size.toDouble() / points.size > EARLY_EXIT_RATIO
            ) {
                break
            }
            val sample = sampleFour(points, random) ?: continue
            val candidate = solveDlt(sample) ?: continue
            val inliers = points.indices.filter { i ->
                transferError(candidate, points[i]) <= toleranceNormalized
            }
            if (inliers.size < bestInliers.size) continue
            val errorSum = inliers.sumOf { transferError(candidate, points[it]) }
            if (inliers.size > bestInliers.size || errorSum < bestErrorSum) {
                bestInliers = inliers
                bestErrorSum = errorSum
            }
        }

        if (bestInliers.size < MIN_POINTS) {
            return failure(points.size)
        }

        // Refine on all consensus inliers, then reclassify once.
        var matrix = solveDlt(bestInliers.map { points[it] }) ?: return failure(points.size)
        var inliers = classify(matrix, points)
        if (inliers.size >= MIN_POINTS) {
            solveDlt(inliers.map { points[it] })?.let { refined ->
                matrix = refined
                inliers = classify(refined, points)
            }
        }
        if (inliers.size < MIN_POINTS) {
            return failure(points.size)
        }

        val errors = inliers.map { transferError(matrix, points[it]) }.sorted()
        return HomographyReport(
            success = true,
            matrix = matrix,
            inlierIndices = inliers,
            inlierCount = inliers.size,
            inlierRatio = inliers.size.toDouble() / points.size,
            medianInlierErrorNormalized = median(errors),
        )
    }

    /** Samples four correspondences whose reference points are not near-collinear. */
    private fun sampleFour(points: List<MatchPoint>, random: Random): List<MatchPoint>? {
        repeat(MAX_SAMPLE_ATTEMPTS) {
            val picked = sortedSetOf<Int>()
            while (picked.size < MIN_POINTS) {
                picked += random.nextInt(points.size)
            }
            val sample = picked.toList().map { points[it] }
            val triples = listOf(
                intArrayOf(0, 1, 2),
                intArrayOf(0, 1, 3),
                intArrayOf(0, 2, 3),
                intArrayOf(1, 2, 3),
            )
            val nonCollinear = triples.all { t ->
                triangleAreaTwice(
                    sample[t[0]].referenceX, sample[t[0]].referenceY,
                    sample[t[1]].referenceX, sample[t[1]].referenceY,
                    sample[t[2]].referenceX, sample[t[2]].referenceY,
                ) > MIN_AREA
            }
            if (nonCollinear) {
                return sample
            }
        }
        return null
    }

    private fun classify(matrix: DoubleArray, points: List<MatchPoint>): List<Int> =
        points.indices.filter { transferError(matrix, points[it]) <= toleranceNormalized }

    internal fun transferError(matrix: DoubleArray, point: MatchPoint): Double {
        val dx = matrix[0] * point.referenceX + matrix[1] * point.referenceY + matrix[2]
        val dy = matrix[3] * point.referenceX + matrix[4] * point.referenceY + matrix[5]
        val w = matrix[6] * point.referenceX + matrix[7] * point.referenceY + matrix[8]
        if (abs(w) < EPSILON) return Double.MAX_VALUE
        val ex = dx / w - point.queryX
        val ey = dy / w - point.queryY
        return sqrt(ex * ex + ey * ey)
    }

    /**
     * Normalized direct-linear-transform: solves A·h = 0 under ||h||=1 via
     * Gaussian elimination with partial pivoting; returns null when the
     * system is degenerate or rank-deficient.
     */
    internal fun solveDlt(correspondences: List<MatchPoint>): DoubleArray? {
        if (correspondences.size < MIN_POINTS) return null
        val srcNorm = SimilarityTransform.normalize(correspondences.map { it.referenceX to it.referenceY })
            ?: return null
        val dstNorm = SimilarityTransform.normalize(correspondences.map { it.queryX to it.queryY })
            ?: return null

        val rows = correspondences.size * ROWS_PER_POINT
        val a = Array(rows) { DoubleArray(NINE) }
        correspondences.forEachIndexed { index, point ->
            val sx = srcNorm.applyX(point.referenceX, point.referenceY)
            val sy = srcNorm.applyY(point.referenceX, point.referenceY)
            val dx = dstNorm.applyX(point.queryX, point.queryY)
            val dy = dstNorm.applyY(point.queryX, point.queryY)
            val r = index * ROWS_PER_POINT
            a[r][0] = sx
            a[r][1] = sy
            a[r][2] = ONE
            a[r][6] = -dx * sx
            a[r][7] = -dx * sy
            a[r][8] = -dx
            a[r + 1][3] = sx
            a[r + 1][4] = sy
            a[r + 1][5] = ONE
            a[r + 1][6] = -dy * sx
            a[r + 1][7] = -dy * sy
            a[r + 1][8] = -dy
        }

        val hNormalized = JacobiSmallestEigenvector.ofAtA(a, rows, NINE) ?: return null
        return denormalize(hNormalized, srcNorm, dstNorm)
    }

    /** H = T_dst^-1 · Hn · T_src with h33 fixed to 1. */
    private fun denormalize(
        h: DoubleArray,
        src: SimilarityTransform,
        dst: SimilarityTransform,
    ): DoubleArray? {
        val tSrcInv = src.inverse() ?: return null
        val tDstInv = dst.inverse() ?: return null
        val combined = mul(tDstInv.m, mul(h, src.m))
        val scale = combined[NINE - 1]
        if (abs(scale) < EPSILON) return null
        return DoubleArray(NINE) { combined[it] / scale }
    }

    private fun mul(p: DoubleArray, q: DoubleArray): DoubleArray {
        val out = DoubleArray(NINE)
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                var acc = ZERO
                for (k in 0 until 3) {
                    acc += p[i * 3 + k] * q[k * 3 + j]
                }
                out[i * 3 + j] = acc
            }
        }
        return out
    }

    /** 2D similarity transform as a 3x3 row-major matrix. */
    internal class SimilarityTransform(internal val m: DoubleArray) {

        fun applyX(x: Double, y: Double): Double = m[0] * x + m[1] * y + m[2]

        fun applyY(x: Double, y: Double): Double = m[3] * x + m[4] * y + m[5]

        fun inverse(): SimilarityTransform? {
            val det = m[0] * m[4] - m[1] * m[3]
            if (abs(det) < EPSILON) return null
            val inv00 = m[4] / det
            val inv01 = -m[1] / det
            val inv11 = m[0] / det
            val tx = -(inv00 * m[2] + inv01 * m[5])
            val ty = -(inv01 * m[2] + inv11 * m[5])
            return SimilarityTransform(doubleArrayOf(inv00, inv01, tx, inv01, inv11, ty, ZERO, ZERO, ONE))
        }

        companion object {
            /** Hartley normalization: centroid at origin, mean distance sqrt(2). */
            fun normalize(points: List<Pair<Double, Double>>): SimilarityTransform? {
                val n = points.size.toDouble()
                val cx = points.sumOf { it.first } / n
                val cy = points.sumOf { it.second } / n
                var meanDistance = ZERO
                points.forEach { (x, y) ->
                    val dx = x - cx
                    val dy = y - cy
                    meanDistance += sqrt(dx * dx + dy * dy)
                }
                meanDistance /= n
                if (meanDistance < EPSILON) return null
                val scale = SQRT_TWO / meanDistance
                return SimilarityTransform(
                    doubleArrayOf(scale, ZERO, -scale * cx, ZERO, scale, -scale * cy, ZERO, ZERO, ONE),
                )
            }
        }
    }

    private fun failure(count: Int): HomographyReport = HomographyReport(
        success = false,
        matrix = null,
        inlierIndices = emptyList(),
        inlierCount = 0,
        inlierRatio = ZERO,
        medianInlierErrorNormalized = ZERO,
    )

    internal companion object {
        /** docs/recognition.md section 7 step 6: 5 px on the 1600-px long edge. */
        const val DEFAULT_TOLERANCE_NORMALIZED: Double = 5.0 / 1600.0
        const val DEFAULT_MAX_ITERATIONS: Int = 512
        const val DEFAULT_MIN_ITERATIONS: Int = 64
        const val DEFAULT_SEED: Long = 0x504F53544D41524BL // "POSTMAR"

        const val MIN_POINTS: Int = 4
        const val NINE: Int = 9
        const val ROWS_PER_POINT: Int = 2
        const val MAX_SAMPLE_ATTEMPTS: Int = 64
        const val EARLY_EXIT_RATIO: Double = 0.95
        const val EPSILON: Double = 1e-12
        const val EPSILON_PIVOT: Double = 1e-9
        const val MIN_AREA: Double = 1e-10
        const val SQRT_TWO: Double = 1.4142135623730951
        const val ONE: Double = 1.0
        const val TWO: Double = 2.0
        const val ZERO: Double = 0.0

        fun triangleAreaTwice(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double =
            (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

        fun median(sorted: List<Double>): Double {
            if (sorted.isEmpty()) return ZERO
            val mid = sorted.size / TWO.toInt()
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / TWO
        }
    }
}

/** Reduces [a] (rows x cols) and extracts the unique null-space direction when rank == cols-1. */
internal object JacobiSmallestEigenvector {

    /**
     * Returns the eigenvector of AᵀA belonging to the SMALLEST eigenvalue,
     * i.e. the unit vector h minimizing ||A·h||. Uses classic cyclic Jacobi
     * rotations on the symmetric 9x9 Gram matrix — deterministic and
     * dependency-free, and equivalent to the null-space direction when the
     * correspondences are exact.
     */
    fun ofAtA(a: Array<DoubleArray>, rows: Int, cols: Int): DoubleArray? {
        val gram = Array(cols) { DoubleArray(cols) }
        for (r in 0 until rows) {
            for (i in 0 until cols) {
                val air = a[r][i]
                if (air == ZERO) continue
                for (j in 0 until cols) {
                    gram[i][j] += air * a[r][j]
                }
            }
        }

        val vectors = Array(cols) { i -> DoubleArray(cols).also { it[i] = ONE } }
        repeat(SWEEPS) {
            var offDiagonal = ZERO
            for (i in 0 until cols - 1) {
                for (j in i + 1 until cols) {
                    offDiagonal += gram[i][j] * gram[i][j]
                }
            }
            if (offDiagonal < CONVERGENCE) return@repeat
            for (p in 0 until cols - 1) {
                for (q in p + 1 until cols) {
                    rotate(gram, vectors, p, q)
                }
            }
        }

        var bestIndex = 0
        for (i in 1 until cols) {
            if (gram[i][i] < gram[bestIndex][bestIndex]) bestIndex = i
        }
        val solution = DoubleArray(cols) { vectors[it][bestIndex] }
        val norm = sqrt(solution.sumOf { it * it })
        if (norm < EPSILON) return null
        return DoubleArray(cols) { solution[it] / norm }
    }

    /** One Jacobi rotation zeroing gram[p][q]; updates the eigenvector basis. */
    private fun rotate(gram: Array<DoubleArray>, vectors: Array<DoubleArray>, p: Int, q: Int) {
        val apq = gram[p][q]
        if (abs(apq) < ROTATION_EPSILON) return
        val app = gram[p][p]
        val aqq = gram[q][q]
        val theta = (aqq - app) / (TWO * apq)
        val t = sign(theta) / (abs(theta) + sqrt(ONE + theta * theta))
        val c = ONE / sqrt(ONE + t * t)
        val s = t * c

        for (k in 0 until gram.size) {
            val akp = gram[k][p]
            val akq = gram[k][q]
            gram[k][p] = c * akp - s * akq
            gram[k][q] = s * akp + c * akq
        }
        for (k in 0 until gram.size) {
            val apk = gram[p][k]
            val aqk = gram[q][k]
            gram[p][k] = c * apk - s * aqk
            gram[q][k] = s * apk + c * aqk
        }
        for (k in 0 until vectors.size) {
            val vkp = vectors[k][p]
            val vkq = vectors[k][q]
            vectors[k][p] = c * vkp - s * vkq
            vectors[k][q] = s * vkp + c * vkq
        }
    }

    private fun sign(value: Double): Double = if (value >= ZERO) ONE else -ONE

    private const val SWEEPS: Int = 48
    private const val CONVERGENCE: Double = 1e-24
    private const val ROTATION_EPSILON: Double = 1e-30
    private const val EPSILON: Double = 1e-12
    private const val ONE: Double = 1.0
    private const val TWO: Double = 2.0
    private const val ZERO: Double = 0.0
}
