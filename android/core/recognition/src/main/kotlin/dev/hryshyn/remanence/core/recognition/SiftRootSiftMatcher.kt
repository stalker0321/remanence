package dev.hryshyn.remanence.core.recognition

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.features2d.BFMatcher

/** Technical no-evidence reason; none of these values grants or rejects a scan. */
enum class SiftRootSiftMatchFailure {
    NONE,
    NO_USABLE_ROWS,
    NO_RATIO_MATCHES,
    NO_RECIPROCAL_MATCHES,
    INSUFFICIENT_UNIQUE_PAIRS,
    HOMOGRAPHY_EMPTY,
    INVALID_HOMOGRAPHY,
    INVALID_MASK,
    INSUFFICIENT_INLIERS,
    INVALID_PROJECTED_SUPPORT,
    SUPPORT_TOO_SMALL,
    SUPPORT_EDGE_RATIO,
}

/** A copied match using original fingerprint row indices, never native objects. */
data class SiftRootSiftMatchPair(
    val queryIndex: Int,
    val referenceIndex: Int,
    val distance: Double,
)

/** Scalar-only diagnostics for the experimental matcher. */
data class SiftRootSiftMatchDiagnostics(
    val rawQueryRows: Int,
    val rawReferenceRows: Int,
    val usableQueryRows: Int,
    val usableReferenceRows: Int,
    val forwardRatioMatches: Int,
    val reverseRatioMatches: Int,
    val reciprocalMatches: Int,
    val uniqueMatches: Int,
    val geometryAttempted: Boolean,
    val geometryFound: Boolean,
    val geometryAccepted: Boolean,
    val inliers: Int,
    val inlierRatio: Double,
    /** Median error, or -1.0 when no valid inlier projection was observable. */
    val medianInlierReprojectionErrorPx: Double,
    /** Reference hull coverage, or -1.0 when no valid inlier was observable. */
    val referenceConvexHullCoverage: Double,
    /** Observed projected inlier-support area; zero means it was not observable. */
    val supportAreaPx2: Double,
    /** Observed projected support edge ratio; zero means it was not observable. */
    val supportEdgeRatio: Double,
    val failure: SiftRootSiftMatchFailure,
)

/**
 * Technical SIFT/RootSIFT evidence only. This class deliberately contains no
 * score, threshold, grant, crypto, index, or acceptance decision.
 *
 * Stored P0 rows are unsigned raw SIFT bytes. Each non-zero row is converted
 * once to RootSIFT by L1 normalization followed by elementwise square root;
 * the stored fingerprint is never mutated and no already-rooted input is
 * accepted as a separate representation.
 */
data class SiftRootSiftMatchResult(
    val matches: List<SiftRootSiftMatchPair>,
    val inlierMatchIndices: List<Int>,
    val homographyRowMajor: DoubleArray?,
    val diagnostics: SiftRootSiftMatchDiagnostics,
)

/**
 * Bounded production-quality P2 matcher for the P0/P1 SIFT profile.
 * Production Create/Scan wiring remains intentionally absent.
 */
class SiftRootSiftMatcher {

    fun match(
        query: SiftRootSiftFingerprint,
        reference: SiftRootSiftFingerprint,
    ): SiftRootSiftMatchResult {
        validateFingerprint(query)
        validateFingerprint(reference)

        val queryRows = rootSiftRows(query)
        val referenceRows = rootSiftRows(reference)
        val rawQueryRows = query.quantizedSiftDescriptors.size
        val rawReferenceRows = reference.quantizedSiftDescriptors.size
        var queryDescriptors: Mat? = null
        var referenceDescriptors: Mat? = null
        var matcher: BFMatcher? = null
        var forwardKnn: MutableList<MatOfDMatch>? = null
        var reverseKnn: MutableList<MatOfDMatch>? = null
        var referencePoints: MatOfPoint2f? = null
        var queryPoints: MatOfPoint2f? = null
        var geometryMask: Mat? = null
        var homography: Mat? = null
        var primaryFailure: Throwable? = null
        try {
            if (queryRows.isEmpty() || referenceRows.isEmpty()) {
                return emptyResult(
                    rawQueryRows = rawQueryRows,
                    rawReferenceRows = rawReferenceRows,
                    usableQueryRows = queryRows.size,
                    usableReferenceRows = referenceRows.size,
                    failure = SiftRootSiftMatchFailure.NO_USABLE_ROWS,
                )
            }
            requireOpenCv()

            val queryMat = Mat(queryRows.size, DESCRIPTOR_BYTES, CvType.CV_32F)
            queryDescriptors = queryMat
            val referenceMat = Mat(referenceRows.size, DESCRIPTOR_BYTES, CvType.CV_32F)
            referenceDescriptors = referenceMat
            fillRootSiftMat(queryMat, queryRows)
            fillRootSiftMat(referenceMat, referenceRows)

            val bfMatcher = BFMatcher.create(Core.NORM_L2, false)
            matcher = bfMatcher
            val forward = ArrayList<MatOfDMatch>(queryRows.size)
            forwardKnn = forward
            bfMatcher.knnMatch(queryMat, referenceMat, forward, KNN)
            val reverse = ArrayList<MatOfDMatch>(referenceRows.size)
            reverseKnn = reverse
            bfMatcher.knnMatch(referenceMat, queryMat, reverse, KNN)

            val forwardRatio = ratioMatches(
                rows = copyKnn(forward, queryRows.size, referenceRows.size),
                anchorCount = queryRows.size,
                trainCount = referenceRows.size,
                reverseDirection = false,
            )
            val reverseRatio = ratioMatches(
                rows = copyKnn(reverse, referenceRows.size, queryRows.size),
                anchorCount = referenceRows.size,
                trainCount = queryRows.size,
                reverseDirection = true,
            )
            val reduced = reduceMatches(query, reference, queryRows, referenceRows, forwardRatio, reverseRatio)
            val noGeometryFailure = when {
                reduced.matches.isEmpty() && forwardRatio.isEmpty() && reverseRatio.isEmpty() ->
                    SiftRootSiftMatchFailure.NO_RATIO_MATCHES
                reduced.matches.isEmpty() -> SiftRootSiftMatchFailure.NO_RECIPROCAL_MATCHES
                reduced.matches.size < MIN_GEOMETRY_PAIRS ->
                    SiftRootSiftMatchFailure.INSUFFICIENT_UNIQUE_PAIRS
                else -> null
            }
            if (noGeometryFailure != null) {
                return resultWithoutGeometry(
                    matches = reduced.matches,
                    rawQueryRows = rawQueryRows,
                    rawReferenceRows = rawReferenceRows,
                    usableQueryRows = queryRows.size,
                    usableReferenceRows = referenceRows.size,
                    forwardRatioMatches = forwardRatio.size,
                    reverseRatioMatches = reverseRatio.size,
                    reciprocalMatches = reduced.reciprocalCount,
                    failure = noGeometryFailure,
                )
            }

            referencePoints = MatOfPoint2f(*reduced.matches.map {
                val point = pointFor(reference, reference.keypoints[it.referenceIndex])
                Point(point.x, point.y)
            }.toTypedArray())
            queryPoints = MatOfPoint2f(*reduced.matches.map {
                val point = pointFor(query, query.keypoints[it.queryIndex])
                Point(point.x, point.y)
            }.toTypedArray())
            val mask = Mat()
            geometryMask = mask
            synchronized(RNG_LOCK) {
                // OpenCV's RNG is process-global; seed immediately before the
                // call while holding the shared lock used by this matcher.
                Core.setRNGSeed(RNG_SEED)
                homography = Calib3d.findHomography(
                    referencePoints,
                    queryPoints,
                    Calib3d.USAC_MAGSAC,
                    RANSAC_REPROJECTION_THRESHOLD_PX,
                    mask,
                    MAX_HOMOGRAPHY_ITERATIONS,
                    HOMOGRAPHY_CONFIDENCE,
                )
            }
            val geometry = evaluateGeometry(
                pairs = reduced.matches,
                query = query,
                reference = reference,
                homography = homography,
                mask = mask,
            )
            return SiftRootSiftMatchResult(
                matches = reduced.matches,
                inlierMatchIndices = geometry.inlierIndices,
                homographyRowMajor = geometry.matrix,
                diagnostics = SiftRootSiftMatchDiagnostics(
                    rawQueryRows = rawQueryRows,
                    rawReferenceRows = rawReferenceRows,
                    usableQueryRows = queryRows.size,
                    usableReferenceRows = referenceRows.size,
                    forwardRatioMatches = forwardRatio.size,
                    reverseRatioMatches = reverseRatio.size,
                    reciprocalMatches = reduced.reciprocalCount,
                    uniqueMatches = reduced.matches.size,
                    geometryAttempted = true,
                    geometryFound = geometry.found,
                    geometryAccepted = geometry.accepted,
                    inliers = geometry.inlierIndices.size,
                    inlierRatio = geometry.inlierRatio,
                    medianInlierReprojectionErrorPx = geometry.medianErrorPx,
                    referenceConvexHullCoverage = geometry.referenceCoverage,
                    supportAreaPx2 = geometry.supportAreaPx2,
                    supportEdgeRatio = geometry.supportEdgeRatio,
                    failure = geometry.failure,
                ),
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            finishCleanup(
                primaryFailure,
                listOf(
                    { matcher?.clear() },
                    { releaseKnn(forwardKnn) },
                    { releaseKnn(reverseKnn) },
                    { homography?.release() },
                    { geometryMask?.release() },
                    { queryPoints?.release() },
                    { referencePoints?.release() },
                    { referenceDescriptors?.release() },
                    { queryDescriptors?.release() },
                    { queryRows.forEach { it.values.fill(0f) } },
                    { referenceRows.forEach { it.values.fill(0f) } },
                ),
            )
        }
    }

    /** One local RootSIFT row with its original P0 fingerprint index. */
    internal data class RootSiftRow(
        val originalIndex: Int,
        val values: FloatArray,
    )

    /** One copied local-index match after strict ratio filtering. */
    internal data class LocalMatch(
        val queryLocalIndex: Int,
        val referenceLocalIndex: Int,
        val distance: Double,
    )

    /** Pure conversion seam used by tests and by the native matcher. */
    internal fun rootSiftRowsForTesting(
        fingerprint: SiftRootSiftFingerprint,
    ): List<RootSiftRow> {
        validateFingerprint(fingerprint)
        return rootSiftRows(fingerprint)
    }

    /** Strict ratio predicate; zero/invalid distances fail closed. */
    internal fun passesStrictRatioForTesting(best: Double, second: Double): Boolean =
        passesStrictRatio(best, second)

    /** Pure reciprocal/original-index/pixel-dedup seam used by tests. */
    internal fun reduceMatchesForTesting(
        query: SiftRootSiftFingerprint,
        reference: SiftRootSiftFingerprint,
        forward: List<LocalMatch>,
        reverse: List<LocalMatch>,
    ): List<SiftRootSiftMatchPair> {
        validateFingerprint(query)
        validateFingerprint(reference)
        val queryRows = rootSiftRows(query)
        val referenceRows = rootSiftRows(reference)
        return reduceMatches(query, reference, queryRows, referenceRows, forward, reverse).matches
    }

    /** Pure metadata-to-pixel conversion for geometry test fixtures. */
    internal fun pointForTesting(
        fingerprint: SiftRootSiftFingerprint,
        index: Int,
    ): Pair<Double, Double> {
        validateFingerprint(fingerprint)
        require(index in fingerprint.keypoints.indices)
        val point = pointFor(fingerprint, fingerprint.keypoints[index])
        return point.x to point.y
    }

    /** Canonical TL,TR,BR,BL support winding; reflection is no evidence. */
    internal fun supportOrientationForTesting(
        points: List<Pair<Double, Double>>,
    ): Boolean = isCanonicalSupportOrientation(
        signedPolygonArea(points.map { (x, y) -> PixelPoint(x, y) }),
    )

    /** Tests cleanup ordering without requiring a fake OpenCV subclass. */
    internal fun cleanupForTesting(
        primaryFailure: Throwable?,
        actions: List<() -> Unit>,
    ) {
        finishCleanup(primaryFailure, actions)
    }

    private fun validateFingerprint(fingerprint: SiftRootSiftFingerprint) {
        val encoded = SiftRootSiftFingerprintCodec.serialize(fingerprint)
        encoded.fill(0)
    }

    private fun rootSiftRows(fingerprint: SiftRootSiftFingerprint): List<RootSiftRow> =
        fingerprint.quantizedSiftDescriptors.mapIndexedNotNull { index, row ->
            val values = rootSiftRow(row)
            values?.let { RootSiftRow(index, it) }
        }

    private fun rootSiftRow(row: ByteArray): FloatArray? {
        require(row.size == DESCRIPTOR_BYTES) { "descriptor row must contain 128 bytes" }
        var sum = 0.0f
        row.forEach { sum += (it.toInt() and 0xFF).toFloat() }
        if (sum <= 0.0f) return null
        val rooted = FloatArray(DESCRIPTOR_BYTES)
        row.forEachIndexed { index, value ->
            val normalized = (value.toInt() and 0xFF).toFloat() / sum
            val root = sqrt(normalized)
            require(root.isFinite()) { "RootSIFT value must be finite" }
            rooted[index] = root
        }
        return rooted
    }

    private fun fillRootSiftMat(target: Mat, rows: List<RootSiftRow>) {
        rows.forEachIndexed { index, row -> target.put(index, 0, row.values) }
    }

    private fun copyKnn(
        rows: List<MatOfDMatch>,
        anchorCount: Int,
        trainCount: Int,
    ): List<List<LocalKnnMatch>> {
        if (rows.size != anchorCount) {
            throw IllegalStateException("OpenCV KNN row count mismatch")
        }
        return rows.mapIndexed { anchorIndex, row ->
            val values = row.toArray()
            if (values.size > KNN) throw IllegalStateException("OpenCV KNN returned too many matches")
            values.map { match ->
                if (match.queryIdx != anchorIndex || match.trainIdx !in 0 until trainCount) {
                    throw IllegalStateException("OpenCV KNN indices are invalid")
                }
                LocalKnnMatch(
                    anchorIndex = anchorIndex,
                    trainIndex = match.trainIdx,
                    distance = match.distance.toDouble(),
                )
            }
        }
    }

    private fun ratioMatches(
        rows: List<List<LocalKnnMatch>>,
        anchorCount: Int,
        trainCount: Int,
        reverseDirection: Boolean,
    ): List<LocalMatch> {
        require(rows.size == anchorCount)
        return rows.mapIndexedNotNull { anchorIndex, candidates ->
            if (candidates.size < KNN) return@mapIndexedNotNull null
            val best = candidates[0]
            val second = candidates[1]
            if (best.anchorIndex != anchorIndex || best.trainIndex !in 0 until trainCount ||
                second.trainIndex !in 0 until trainCount
            ) {
                throw IllegalStateException("OpenCV KNN contract is invalid")
            }
            if (!passesStrictRatio(best.distance, second.distance)) return@mapIndexedNotNull null
            if (reverseDirection) {
                LocalMatch(best.trainIndex, anchorIndex, best.distance)
            } else {
                LocalMatch(anchorIndex, best.trainIndex, best.distance)
            }
        }
    }

    private fun passesStrictRatio(best: Double, second: Double): Boolean {
        if (!best.isFinite() || !second.isFinite() || best < 0.0 || second < 0.0 || second == 0.0) {
            return false
        }
        return best < RATIO_THRESHOLD * second
    }

    private data class ReducedMatches(
        val matches: List<SiftRootSiftMatchPair>,
        val reciprocalCount: Int,
    )

    private fun reduceMatches(
        query: SiftRootSiftFingerprint,
        reference: SiftRootSiftFingerprint,
        queryRows: List<RootSiftRow>,
        referenceRows: List<RootSiftRow>,
        forward: List<LocalMatch>,
        reverse: List<LocalMatch>,
    ): ReducedMatches {
        val reverseByReference = reverse.associateBy { it.referenceLocalIndex }
        val reciprocal = forward.filter { match ->
            reverseByReference[match.referenceLocalIndex]?.queryLocalIndex == match.queryLocalIndex
        }
        val sorted = reciprocal.map { match ->
            require(match.queryLocalIndex in queryRows.indices)
            require(match.referenceLocalIndex in referenceRows.indices)
            SiftRootSiftMatchPair(
                queryIndex = queryRows[match.queryLocalIndex].originalIndex,
                referenceIndex = referenceRows[match.referenceLocalIndex].originalIndex,
                distance = match.distance,
            )
        }.sortedWith(
            compareBy<SiftRootSiftMatchPair> { it.distance }
                .thenBy { it.queryIndex }
                .thenBy { it.referenceIndex },
        )

        // Deliberately deduplicate only rounded query pixels. Reference-side
        // repeated pixels remain separate, matching the P2 contract/Python
        // parity and avoiding an unapproved second-side suppression rule.
        val seenQueryPixels = HashSet<Pair<Int, Int>>()
        val unique = sorted.filter { match ->
            val keypoint = query.keypoints[match.queryIndex]
            val pixel = roundedPixel(query, keypoint)
            seenQueryPixels.add(pixel)
        }
        return ReducedMatches(unique, reciprocal.size)
    }

    private fun resultWithoutGeometry(
        matches: List<SiftRootSiftMatchPair>,
        rawQueryRows: Int,
        rawReferenceRows: Int,
        usableQueryRows: Int,
        usableReferenceRows: Int,
        forwardRatioMatches: Int,
        reverseRatioMatches: Int,
        reciprocalMatches: Int,
        failure: SiftRootSiftMatchFailure,
    ): SiftRootSiftMatchResult = SiftRootSiftMatchResult(
        matches = matches,
        inlierMatchIndices = emptyList(),
        homographyRowMajor = null,
        diagnostics = SiftRootSiftMatchDiagnostics(
            rawQueryRows = rawQueryRows,
            rawReferenceRows = rawReferenceRows,
            usableQueryRows = usableQueryRows,
            usableReferenceRows = usableReferenceRows,
            forwardRatioMatches = forwardRatioMatches,
            reverseRatioMatches = reverseRatioMatches,
            reciprocalMatches = reciprocalMatches,
            uniqueMatches = matches.size,
            geometryAttempted = false,
            geometryFound = false,
            geometryAccepted = false,
            inliers = 0,
            inlierRatio = 0.0,
            medianInlierReprojectionErrorPx = NOT_OBSERVABLE,
            referenceConvexHullCoverage = NOT_OBSERVABLE,
            supportAreaPx2 = 0.0,
            supportEdgeRatio = 0.0,
            failure = failure,
        ),
    )

    private fun emptyResult(
        rawQueryRows: Int,
        rawReferenceRows: Int,
        usableQueryRows: Int,
        usableReferenceRows: Int,
        failure: SiftRootSiftMatchFailure,
    ): SiftRootSiftMatchResult = resultWithoutGeometry(
        matches = emptyList(),
        rawQueryRows = rawQueryRows,
        rawReferenceRows = rawReferenceRows,
        usableQueryRows = usableQueryRows,
        usableReferenceRows = usableReferenceRows,
        forwardRatioMatches = 0,
        reverseRatioMatches = 0,
        reciprocalMatches = 0,
        failure = failure,
    )

    private data class GeometryEvaluation(
        val found: Boolean,
        val accepted: Boolean,
        val matrix: DoubleArray?,
        val inlierIndices: List<Int>,
        val inlierRatio: Double,
        val medianErrorPx: Double,
        val referenceCoverage: Double,
        val supportAreaPx2: Double,
        val supportEdgeRatio: Double,
        val failure: SiftRootSiftMatchFailure,
    )

    private fun evaluateGeometry(
        pairs: List<SiftRootSiftMatchPair>,
        query: SiftRootSiftFingerprint,
        reference: SiftRootSiftFingerprint,
        homography: Mat?,
        mask: Mat,
    ): GeometryEvaluation {
        if (homography == null || homography.empty()) {
            return geometryFailure(SiftRootSiftMatchFailure.HOMOGRAPHY_EMPTY)
        }
        if (homography.rows() != HOMOGRAPHY_DIMENSION || homography.cols() != HOMOGRAPHY_DIMENSION) {
            return geometryFailure(SiftRootSiftMatchFailure.INVALID_HOMOGRAPHY, found = true)
        }
        val matrix = DoubleArray(HOMOGRAPHY_VALUES)
        if (homography.type() != CvType.CV_64F) {
            return geometryFailure(SiftRootSiftMatchFailure.INVALID_HOMOGRAPHY, found = true)
        }
        repeat(HOMOGRAPHY_DIMENSION) { rowIndex ->
            repeat(HOMOGRAPHY_DIMENSION) { columnIndex ->
                val cell = homography.get(rowIndex, columnIndex)
                if (cell.size != 1) {
                    return geometryFailure(SiftRootSiftMatchFailure.INVALID_HOMOGRAPHY, found = true)
                }
                matrix[rowIndex * HOMOGRAPHY_DIMENSION + columnIndex] = cell[0]
            }
        }
        if (matrix.any { !it.isFinite() }) {
            return geometryFailure(SiftRootSiftMatchFailure.INVALID_HOMOGRAPHY, found = true)
        }
        if (mask.empty() || mask.type() != CvType.CV_8U ||
            mask.total() != pairs.size.toLong() ||
            !((mask.rows() == pairs.size && mask.cols() == 1) ||
                (mask.rows() == 1 && mask.cols() == pairs.size))
        ) {
            return geometryFailure(SiftRootSiftMatchFailure.INVALID_MASK, found = true, matrix = matrix)
        }
        val maskBytes = ByteArray(pairs.size)
        if (mask.rows() == pairs.size) {
            maskBytes.indices.forEach { index ->
                val value = ByteArray(1)
                if (mask.get(index, 0, value) != 1) {
                    return geometryFailure(SiftRootSiftMatchFailure.INVALID_MASK, found = true, matrix = matrix)
                }
                maskBytes[index] = value[0]
            }
        } else if (mask.get(0, 0, maskBytes) != maskBytes.size) {
            return geometryFailure(SiftRootSiftMatchFailure.INVALID_MASK, found = true, matrix = matrix)
        }
        val inlierIndices = ArrayList<Int>(pairs.size)
        maskBytes.forEachIndexed { index, value ->
            if (value.toInt() != 0) inlierIndices += index
        }
        val inlierRatio = inlierIndices.size.toDouble() / pairs.size.toDouble()
        val referenceInliers = inlierIndices.map { pairIndex ->
            pointFor(reference, reference.keypoints[pairs[pairIndex].referenceIndex])
        }
        val queryInliers = inlierIndices.map { pairIndex ->
            pointFor(query, query.keypoints[pairs[pairIndex].queryIndex])
        }
        val validReferenceInliers = ArrayList<PixelPoint>(inlierIndices.size)
        val errors = ArrayList<Double>(inlierIndices.size)
        referenceInliers.indices.forEach { index ->
            val projected = project(matrix, referenceInliers[index])
            if (projected == null) return@forEach
            val queryPoint = queryInliers[index]
            val error = hypot(projected.x - queryPoint.x, projected.y - queryPoint.y)
            if (error.isFinite()) {
                validReferenceInliers += referenceInliers[index]
                errors += error
            }
        }
        val referenceCoverage = if (validReferenceInliers.isNotEmpty()) {
            (convexHullArea(validReferenceInliers) /
                (reference.canonicalWidthPx.toDouble() * reference.canonicalHeightPx.toDouble()))
                .takeIf { it.isFinite() } ?: NOT_OBSERVABLE
        } else {
            NOT_OBSERVABLE
        }
        val medianErrorPx = errors.takeIf { it.isNotEmpty() }?.let(::median) ?: NOT_OBSERVABLE
        if (inlierIndices.size < MIN_INLIERS) {
            return GeometryEvaluation(
                found = true,
                accepted = false,
                matrix = matrix,
                inlierIndices = inlierIndices,
                inlierRatio = inlierRatio,
                medianErrorPx = medianErrorPx,
                referenceCoverage = referenceCoverage,
                supportAreaPx2 = 0.0,
                supportEdgeRatio = 0.0,
                failure = SiftRootSiftMatchFailure.INSUFFICIENT_INLIERS,
            )
        }
        if (errors.size != inlierIndices.size) {
            return geometryFailure(
                failure = SiftRootSiftMatchFailure.INVALID_PROJECTED_SUPPORT,
                found = true,
                matrix = matrix,
                inlierIndices = inlierIndices,
                inlierRatio = inlierRatio,
                medianErrorPx = medianErrorPx,
                referenceCoverage = referenceCoverage,
            )
        }
        val minX = referenceInliers.minOf { it.x }
        val maxX = referenceInliers.maxOf { it.x }
        val minY = referenceInliers.minOf { it.y }
        val maxY = referenceInliers.maxOf { it.y }
        val support = listOf(
            PixelPoint(minX, minY),
            PixelPoint(maxX, minY),
            PixelPoint(maxX, maxY),
            PixelPoint(minX, maxY),
        ).map { project(matrix, it) }
        if (support.any { point ->
                point?.let { !it.x.isFinite() || !it.y.isFinite() } ?: true
            }
        ) {
            return geometryFailure(
                failure = SiftRootSiftMatchFailure.INVALID_PROJECTED_SUPPORT,
                found = true,
                matrix = matrix,
                inlierIndices = inlierIndices,
                inlierRatio = inlierRatio,
                medianErrorPx = medianErrorPx,
                referenceCoverage = referenceCoverage,
            )
        }
        val projectedSupport = support.map { requireNotNull(it) }
        // The reference implementation orders TL,TR,BR,BL (vision.py:138),
        // asks OpenCV for oriented contour area (vision.py:143), and rejects
        // negative area through its `< 300` check (vision.py:145). Preserve
        // that reflected/clockwise-input behavior as geometry validity, never
        // as a grant threshold; diagnostics expose absolute magnitude only.
        val signedSupportArea = signedPolygonArea(projectedSupport)
        val supportArea = abs(signedSupportArea)
        val edgeLengths = projectedSupport.indices.map { index ->
            val next = projectedSupport[(index + 1) % projectedSupport.size]
            hypot(next.x - projectedSupport[index].x, next.y - projectedSupport[index].y)
        }
        val shortestEdge = edgeLengths.minOrNull() ?: 0.0
        val longestEdge = edgeLengths.maxOrNull() ?: 0.0
        val supportEdgeRatio = if (shortestEdge > 0.0 && shortestEdge.isFinite() && longestEdge.isFinite()) {
            longestEdge / shortestEdge
        } else {
            0.0
        }
        val reportedSupportArea = supportArea.takeIf { it.isFinite() } ?: 0.0
        val reportedSupportEdgeRatio = supportEdgeRatio.takeIf { it.isFinite() } ?: 0.0
        if (!isStrictlyConvex(projectedSupport)) {
            return geometryFailure(
                failure = SiftRootSiftMatchFailure.INVALID_PROJECTED_SUPPORT,
                found = true,
                matrix = matrix,
                inlierIndices = inlierIndices,
                inlierRatio = inlierRatio,
                medianErrorPx = medianErrorPx,
                referenceCoverage = referenceCoverage,
                supportAreaPx2 = reportedSupportArea,
                supportEdgeRatio = reportedSupportEdgeRatio,
            )
        }
        if (!isCanonicalSupportOrientation(signedSupportArea)) {
            return geometryFailure(
                failure = SiftRootSiftMatchFailure.INVALID_PROJECTED_SUPPORT,
                found = true,
                matrix = matrix,
                inlierIndices = inlierIndices,
                inlierRatio = inlierRatio,
                medianErrorPx = medianErrorPx,
                referenceCoverage = referenceCoverage,
                supportAreaPx2 = reportedSupportArea,
                supportEdgeRatio = reportedSupportEdgeRatio,
            )
        }
        if (supportArea < MIN_SUPPORT_AREA_PX2) {
            return GeometryEvaluation(
                found = true,
                accepted = false,
                matrix = matrix,
                inlierIndices = inlierIndices,
                inlierRatio = inlierRatio,
                medianErrorPx = medianErrorPx,
                referenceCoverage = referenceCoverage,
                supportAreaPx2 = supportArea,
                supportEdgeRatio = reportedSupportEdgeRatio,
                failure = SiftRootSiftMatchFailure.SUPPORT_TOO_SMALL,
            )
        }
        if (shortestEdge <= 0.0 || !shortestEdge.isFinite() ||
            !longestEdge.isFinite() || longestEdge / shortestEdge > MAX_SUPPORT_EDGE_RATIO
        ) {
            return GeometryEvaluation(
                found = true,
                accepted = false,
                matrix = matrix,
                inlierIndices = inlierIndices,
                inlierRatio = inlierRatio,
                medianErrorPx = medianErrorPx,
                referenceCoverage = referenceCoverage,
                supportAreaPx2 = supportArea,
                supportEdgeRatio = reportedSupportEdgeRatio,
                failure = SiftRootSiftMatchFailure.SUPPORT_EDGE_RATIO,
            )
        }
        return GeometryEvaluation(
            found = true,
            accepted = true,
            matrix = matrix,
            inlierIndices = inlierIndices,
            inlierRatio = inlierRatio,
            medianErrorPx = medianErrorPx,
            referenceCoverage = referenceCoverage,
            supportAreaPx2 = supportArea,
            supportEdgeRatio = reportedSupportEdgeRatio,
            failure = SiftRootSiftMatchFailure.NONE,
        )
    }

    private fun geometryFailure(
        failure: SiftRootSiftMatchFailure,
        found: Boolean = false,
        matrix: DoubleArray? = null,
        inlierIndices: List<Int> = emptyList(),
        inlierRatio: Double = 0.0,
        medianErrorPx: Double = NOT_OBSERVABLE,
        referenceCoverage: Double = NOT_OBSERVABLE,
        supportAreaPx2: Double = 0.0,
        supportEdgeRatio: Double = 0.0,
    ) = GeometryEvaluation(
        found = found,
        accepted = false,
        matrix = matrix,
        inlierIndices = inlierIndices,
        inlierRatio = inlierRatio,
        medianErrorPx = medianErrorPx,
        referenceCoverage = referenceCoverage,
        supportAreaPx2 = supportAreaPx2,
        supportEdgeRatio = supportEdgeRatio,
        failure = failure,
    )

    private data class PixelPoint(val x: Double, val y: Double)

    private fun pointFor(
        fingerprint: SiftRootSiftFingerprint,
        keypoint: SiftRootSiftKeypoint,
    ): PixelPoint = PixelPoint(
        x = keypoint.xMicro.toDouble() / SiftRootSiftFingerprintCodec.MICRO_UNITS * fingerprint.canonicalWidthPx,
        y = keypoint.yMicro.toDouble() / SiftRootSiftFingerprintCodec.MICRO_UNITS * fingerprint.canonicalHeightPx,
    )

    private fun roundedPixel(
        fingerprint: SiftRootSiftFingerprint,
        keypoint: SiftRootSiftKeypoint,
    ): Pair<Int, Int> =
        Math.rint(pointFor(fingerprint, keypoint).x).toInt() to
            Math.rint(pointFor(fingerprint, keypoint).y).toInt()

    private fun project(matrix: DoubleArray, point: PixelPoint): PixelPoint? {
        val x = matrix[0] * point.x + matrix[1] * point.y + matrix[2]
        val y = matrix[3] * point.x + matrix[4] * point.y + matrix[5]
        val w = matrix[6] * point.x + matrix[7] * point.y + matrix[8]
        if (!x.isFinite() || !y.isFinite() || !w.isFinite() || w == 0.0) return null
        val projectedX = x / w
        val projectedY = y / w
        return if (projectedX.isFinite() && projectedY.isFinite()) {
            PixelPoint(projectedX, projectedY)
        } else {
            null
        }
    }

    private fun isStrictlyConvex(points: List<PixelPoint>): Boolean {
        if (points.size < 3) return false
        var sign = 0
        points.indices.forEach { index ->
            val first = points[index]
            val second = points[(index + 1) % points.size]
            val third = points[(index + 2) % points.size]
            val cross = (second.x - first.x) * (third.y - second.y) -
                (second.y - first.y) * (third.x - second.x)
            if (!cross.isFinite() || cross == 0.0) return false
            val currentSign = if (cross > 0.0) 1 else -1
            if (sign != 0 && sign != currentSign) return false
            sign = currentSign
        }
        return true
    }

    private fun signedPolygonArea(points: List<PixelPoint>): Double {
        var twice = 0.0
        points.indices.forEach { index ->
            val first = points[index]
            val second = points[(index + 1) % points.size]
            twice += first.x * second.y - second.x * first.y
        }
        return twice / 2.0
    }

    private fun isCanonicalSupportOrientation(signedArea: Double): Boolean =
        signedArea.isFinite() && signedArea > 0.0

    private fun polygonArea(points: List<PixelPoint>): Double = abs(signedPolygonArea(points))

    private fun convexHullArea(points: List<PixelPoint>): Double {
        val sorted = points.sortedWith(compareBy<PixelPoint> { it.x }.thenBy { it.y })
        val unique = sorted.distinct()
        if (unique.size < 3) return 0.0
        fun cross(o: PixelPoint, a: PixelPoint, b: PixelPoint): Double =
            (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<PixelPoint>()
        unique.forEach { point ->
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), point) <= 0.0) {
                lower.removeAt(lower.lastIndex)
            }
            lower += point
        }
        val upper = ArrayList<PixelPoint>()
        unique.asReversed().forEach { point ->
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), point) <= 0.0) {
                upper.removeAt(upper.lastIndex)
            }
            upper += point
        }
        lower.removeAt(lower.lastIndex)
        upper.removeAt(upper.lastIndex)
        return polygonArea(lower + upper)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    private fun requireOpenCv() {
        try {
            if (Core.getVersionMajor() <= 0) {
                throw IllegalStateException("OpenCV native library not initialized")
            }
        } catch (failure: LinkageError) {
            throw IllegalStateException("OpenCV native library not initialized", failure)
        }
    }

    private fun releaseKnn(rows: MutableList<MatOfDMatch>?) {
        var releaseFailure: Throwable? = null
        rows?.forEach { value ->
            try {
                value.release()
            } catch (failure: Throwable) {
                val previousFailure = releaseFailure
                if (previousFailure == null) releaseFailure = failure else previousFailure.addSuppressed(failure)
            }
        }
        releaseFailure?.let { throw it }
    }

    private fun finishCleanup(
        primaryFailure: Throwable?,
        actions: List<() -> Unit>,
    ) {
        var cleanupFailure: Throwable? = null
        actions.forEach { action ->
            try {
                action()
            } catch (failure: Throwable) {
                val previousFailure = cleanupFailure
                if (previousFailure == null) cleanupFailure = failure else previousFailure.addSuppressed(failure)
            }
        }
        if (primaryFailure != null) {
            cleanupFailure?.let(primaryFailure::addSuppressed)
        } else {
            cleanupFailure?.let { throw it }
        }
    }

    private data class LocalKnnMatch(
        val anchorIndex: Int,
        val trainIndex: Int,
        val distance: Double,
    )

    private companion object {
        const val DESCRIPTOR_BYTES = SiftRootSiftFingerprintCodec.DESCRIPTOR_BYTES
        const val KNN = 2
        const val RATIO_THRESHOLD = 0.75
        const val MIN_GEOMETRY_PAIRS = 6
        const val MIN_INLIERS = 4
        const val RANSAC_REPROJECTION_THRESHOLD_PX = 4.0
        const val MAX_HOMOGRAPHY_ITERATIONS = 3_000
        const val HOMOGRAPHY_CONFIDENCE = 0.999
        const val HOMOGRAPHY_DIMENSION = 3
        const val HOMOGRAPHY_VALUES = 9
        const val MIN_SUPPORT_AREA_PX2 = 300.0
        const val MAX_SUPPORT_EDGE_RATIO = 8.0
        const val NOT_OBSERVABLE = -1.0
        const val RNG_SEED = 42
        val RNG_LOCK = Any()
    }
}
