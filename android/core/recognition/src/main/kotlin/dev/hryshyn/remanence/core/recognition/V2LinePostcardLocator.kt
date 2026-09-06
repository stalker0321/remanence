package dev.hryshyn.remanence.core.recognition

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Production equivalent of the validated v2 proposal stage.
 *
 * It does not consume saved corners or benchmark metadata. It finds long
 * center-containing rectangular boundaries from multiscale luminance/chroma
 * line evidence, then requires independent edge support on all four sides.
 * The returned [QuadCandidate.edgeSupport] is measured from the current frame;
 * [QuadCandidate.rectangularity] is a geometry-derived score, not a constant.
 */
class V2LinePostcardLocator(
    private val profile: RecognitionProfile,
) {

    fun detect(argbPixels: IntArray, width: Int, height: Int): List<QuadCandidate> {
        require(width > 0 && height > 0)
        require(argbPixels.size == width * height)
        check(Core.getVersionMajor() > 0) { "OpenCV native library not initialized" }

        val rgba = Mat(height, width, CvType.CV_8UC4)
        val small = Mat()
        val gray = Mat()
        val bgr = Mat()
        val lab = Mat()
        val edgeUnion = Mat()
        val detector = Imgproc.createLineSegmentDetector()
        try {
            fillRgba(rgba, argbPixels, width)
            val scale = min(1.0, TARGET_LONG_EDGE_PX.toDouble() / max(width, height).toDouble())
            val smallWidth = max(1, round(width * scale).toInt())
            val smallHeight = max(1, round(height * scale).toInt())
            if (smallWidth == width && smallHeight == height) {
                rgba.copyTo(small)
            } else {
                Imgproc.resize(rgba, small, Size(smallWidth.toDouble(), smallHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            }
            val h = small.rows()
            val w = small.cols()
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(small, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
            edgeUnion.create(h, w, CvType.CV_8UC1)
            edgeUnion.setTo(org.opencv.core.Scalar(0.0))

            val groups = Array(GROUP_COUNT) { ArrayList<LineHypothesis>() }
            val sources = listOf(
                Source(gray, downsample = 1, sigma = 0.8, color = false),
                Source(gray, downsample = 2, sigma = 1.5, color = false),
                Source(gray, downsample = 4, sigma = 1.2, color = false),
                Source(channel(lab, 1), downsample = 2, sigma = 1.2, color = true),
                Source(channel(lab, 2), downsample = 2, sigma = 1.2, color = true),
            )
            try {
                for (source in sources) {
                    val sourceMat = source.channel
                    val downWidth = max(1, w / source.downsample)
                    val downHeight = max(1, h / source.downsample)
                    val scaled = Mat()
                    val blurred = Mat()
                    val lines = Mat()
                    val edges = Mat()
                    val resizedEdges = Mat()
                    try {
                        if (downWidth == sourceMat.cols() && downHeight == sourceMat.rows()) {
                            sourceMat.copyTo(scaled)
                        } else {
                            Imgproc.resize(
                                sourceMat,
                                scaled,
                                Size(downWidth.toDouble(), downHeight.toDouble()),
                                0.0,
                                0.0,
                                Imgproc.INTER_AREA,
                            )
                        }
                        Imgproc.GaussianBlur(scaled, blurred, Size(), source.sigma)
                        detector.detect(blurred, lines)
                        collectLines(lines, source.downsample, w, h, groups)

                        val mean = if (source.color) COLOR_LOW to COLOR_HIGH else GRAY_LOW to GRAY_HIGH
                        Imgproc.Canny(blurred, edges, mean.first, mean.second)
                        if (edges.cols() == w && edges.rows() == h) {
                            edges.copyTo(resizedEdges)
                        } else {
                            Imgproc.resize(edges, resizedEdges, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
                        }
                        Core.bitwise_or(edgeUnion, resizedEdges, edgeUnion)
                    } finally {
                        scaled.release()
                        blurred.release()
                        lines.release()
                        edges.release()
                        resizedEdges.release()
                    }
                }
            } finally {
                sources.forEach { it.releaseIfOwned(gray, lab) }
            }

            val keptGroups = groups.map { keepDistinct(it) }
            if (keptGroups.any { it.isEmpty() }) return emptyList()

            val inverseEdges = Mat()
            val distance = Mat()
            val smoothLab = Mat()
            try {
                Core.bitwise_not(edgeUnion, inverseEdges)
                Imgproc.distanceTransform(inverseEdges, distance, Imgproc.DIST_L2, 3)
                Imgproc.GaussianBlur(lab, smoothLab, Size(), 2.0)
                return enumerateCandidates(
                    keptGroups,
                    width = w,
                    height = h,
                    scale = scale,
                    distance = distance,
                    lab = smoothLab,
                )
            } finally {
                inverseEdges.release()
                distance.release()
                smoothLab.release()
            }
        } finally {
            detector.clear()
            rgba.release()
            small.release()
            gray.release()
            bgr.release()
            lab.release()
            edgeUnion.release()
        }
    }

    private fun enumerateCandidates(
        groups: List<List<LineHypothesis>>,
        width: Int,
        height: Int,
        scale: Double,
        distance: Mat,
        lab: Mat,
    ): List<QuadCandidate> {
        val proposals = ArrayList<Proposal>()
        val center = PointD(width / 2.0, height / 2.0)
        for (top in groups[0]) for (right in groups[1]) for (bottom in groups[2]) for (left in groups[3]) {
            val quad = listOf(
                intersect(left.line, top.line),
                intersect(top.line, right.line),
                intersect(right.line, bottom.line),
                intersect(bottom.line, left.line),
            )
            if (quad.any { it == null }) continue
            val corners = quad.map { it!! }
            if (!validGeometry(corners, width, height, center)) continue

            val support = edgeSupport(corners, distance, width, height)
            val minimumSupport = support.minOrNull() ?: 0.0
            if (minimumSupport < MIN_SIDE_SUPPORT) continue
            val contrast = sideContrast(corners, lab, width, height)
            val area = abs(CornerGeometry.signedArea(corners)) / (width.toDouble() * height)
            val shift = hypot(corners.sumOf { it.x } / 4.0 - center.x, corners.sumOf { it.y } / 4.0 - center.y) /
                hypot(width.toDouble(), height.toDouble())
            val score = 0.45 * minimumSupport +
                0.25 * support.average() +
                0.35 * area +
                0.15 * contrast +
                -0.20 * shift
            val scaledCorners = corners.map { PointD(it.x / scale, it.y / scale) }
            proposals += Proposal(
                corners = scaledCorners,
                areaRatio = abs(CornerGeometry.signedArea(scaledCorners)) /
                    (width.toDouble() / scale * (height.toDouble() / scale)),
                rectangularity = geometryRectangularity(corners),
                edgeSupport = minimumSupport,
                score = score,
            )
        }

        val selected = ArrayList<Proposal>(MAX_PROPOSALS)
        for (proposal in proposals.sortedByDescending { it.score }) {
            if (selected.none { meanCornerDistance(it.corners, proposal.corners) < DEDUPE_DISTANCE_PX }) {
                selected += proposal
            }
            if (selected.size == MAX_PROPOSALS) break
        }
        return selected.map {
            QuadCandidate(
                corners = it.corners,
                areaRatio = it.areaRatio,
                rectangularity = it.rectangularity,
                edgeSupport = it.edgeSupport,
            )
        }
    }

    private fun validGeometry(corners: List<PointD>, width: Int, height: Int, center: PointD): Boolean {
        if (corners.any { it.x !in MARGIN_PX.toDouble()..(width - MARGIN_PX).toDouble() || it.y !in MARGIN_PX.toDouble()..(height - MARGIN_PX).toDouble() }) return false
        if (corners.indices.any { index ->
                val a = corners[index]
                val b = corners[(index + 1) % corners.size]
                val c = corners[(index + 2) % corners.size]
                cross(b - a, c - b) <= 0.0 || cross(b - a, center - a) < 0.0
            }
        ) return false
        val area = abs(CornerGeometry.signedArea(corners)) / (width.toDouble() * height)
        if (area !in MIN_AREA_RATIO..MAX_AREA_RATIO) return false
        val lengths = corners.indices.map { edgeLength(corners[it], corners[(it + 1) % corners.size]) }
        if (lengths.minOrNull()!! <= min(width, height) * MIN_EDGE_FRACTION) return false
        for ((a, b) in listOf(0 to 2, 1 to 3)) {
            if (max(lengths[a], lengths[b]) / max(min(lengths[a], lengths[b]), 1.0) >= MAX_OPPOSITE_EDGE_RATIO) return false
        }
        return true
    }

    private fun edgeSupport(corners: List<PointD>, distance: Mat, width: Int, height: Int): List<Double> =
        corners.indices.map { index ->
            val a = corners[index]
            val b = corners[(index + 1) % corners.size]
            val hits = (1..SAMPLE_COUNT).count { sample ->
                val t = SAMPLE_START + (SAMPLE_END - SAMPLE_START) * (sample - 1) / (SAMPLE_COUNT - 1)
                val x = (a.x + (b.x - a.x) * t).roundToInt().coerceIn(0, width - 1)
                val y = (a.y + (b.y - a.y) * t).roundToInt().coerceIn(0, height - 1)
                distance.get(y, x)[0] <= DISTANCE_TOLERANCE_PX
            }
            hits.toDouble() / SAMPLE_COUNT
        }

    private fun sideContrast(corners: List<PointD>, lab: Mat, width: Int, height: Int): Double {
        var sum = 0.0
        var count = 0
        corners.indices.forEach { index ->
            val a = corners[index]
            val b = corners[(index + 1) % corners.size]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = hypot(dx, dy)
            val nx = -dy / length
            val ny = dx / length
            for (sample in 1..SAMPLE_COUNT) {
                val t = SAMPLE_START + (SAMPLE_END - SAMPLE_START) * (sample - 1) / (SAMPLE_COUNT - 1)
                val x = a.x + dx * t
                val y = a.y + dy * t
                val inside = labAt(lab, x + nx * SIDE_SAMPLE_OFFSET_PX, y + ny * SIDE_SAMPLE_OFFSET_PX, width, height)
                val outside = labAt(lab, x - nx * SIDE_SAMPLE_OFFSET_PX, y - ny * SIDE_SAMPLE_OFFSET_PX, width, height)
                sum += min(sqrt((0 until 3).sumOf { channel ->
                    val delta = inside[channel] - outside[channel]
                    delta * delta
                }) / LAB_CONTRAST_SCALE, 1.0)
                count++
            }
        }
        return if (count == 0) 0.0 else sum / count
    }

    private fun labAt(mat: Mat, x: Double, y: Double, width: Int, height: Int): DoubleArray =
        mat.get(y.roundToInt().coerceIn(0, height - 1), x.roundToInt().coerceIn(0, width - 1))

    private fun geometryRectangularity(corners: List<PointD>): Double {
        val lengths = corners.indices.map { edgeLength(corners[it], corners[(it + 1) % corners.size]) }
        val opposite = listOf(
            min(lengths[0], lengths[2]) / max(lengths[0], lengths[2]),
            min(lengths[1], lengths[3]) / max(lengths[1], lengths[3]),
        ).average()
        val orthogonality = corners.indices.map { index ->
            val first = corners[(index + 1) % corners.size] - corners[index]
            val second = corners[(index + 2) % corners.size] - corners[(index + 1) % corners.size]
            1.0 - abs(dot(first, second)) / max(hypot(first.x, first.y) * hypot(second.x, second.y), 1e-9)
        }.average()
        return ((opposite + orthogonality) / 2.0).coerceIn(0.0, 1.0)
    }

    private fun collectLines(lines: Mat, downsample: Int, width: Int, height: Int, groups: Array<ArrayList<LineHypothesis>>) {
        if (lines.empty()) return
        val values = FloatArray(4)
        for (row in 0 until lines.rows()) {
            lines.get(row, 0, values)
            val x1 = values[0].toDouble() * downsample
            val y1 = values[1].toDouble() * downsample
            val x2 = values[2].toDouble() * downsample
            val y2 = values[3].toDouble() * downsample
            val dx = x2 - x1
            val dy = y2 - y1
            val length = hypot(dx, dy)
            if (length < min(width, height) * MIN_LINE_FRACTION) continue
            val nx = -dy / length
            val ny = dx / length
            val c = -(nx * x1 + ny * y1)
            val (group, at) = if (abs(dx) >= abs(dy) && abs(ny) > 1e-9) {
                0 to (-(nx * width / 2.0 + c) / ny)
            } else if (abs(nx) > 1e-9) {
                3 to (-(ny * height / 2.0 + c) / nx)
            } else {
                continue
            }
            val resolvedGroup = if (group == 0) {
                if (at < height / 2.0) 0 else 2
            } else {
                if (at < width / 2.0) 3 else 1
            }
            groups[resolvedGroup] += LineHypothesis(Line(nx, ny, c), length, at)
        }
    }

    private fun keepDistinct(lines: List<LineHypothesis>): List<LineHypothesis> {
        val kept = ArrayList<LineHypothesis>(MAX_LINES_PER_SIDE)
        for (line in lines.sortedByDescending { it.length }) {
            if (kept.none {
                    abs(line.at - it.at) < LINE_DEDUPE_DISTANCE_PX &&
                        abs(line.line.nx * it.line.nx + line.line.ny * it.line.ny) > NORMAL_ALIGNMENT_THRESHOLD
                }
            ) {
                kept += line
            }
            if (kept.size == MAX_LINES_PER_SIDE) break
        }
        return kept
    }

    private fun intersect(first: Line, second: Line): PointD? {
        val denominator = first.nx * second.ny - first.ny * second.nx
        if (abs(denominator) < 1e-9) return null
        return PointD(
            (first.ny * second.c - first.c * second.ny) / denominator,
            (first.c * second.nx - first.nx * second.c) / denominator,
        )
    }

    private fun meanCornerDistance(first: List<PointD>, second: List<PointD>): Double =
        first.zip(second).map { (a, b) -> hypot(a.x - b.x, a.y - b.y) }.average()

    private fun edgeLength(a: PointD, b: PointD): Double = hypot(b.x - a.x, b.y - a.y)

    private fun cross(a: PointD, b: PointD): Double = a.x * b.y - a.y * b.x

    private fun dot(a: PointD, b: PointD): Double = a.x * b.x + a.y * b.y

    private operator fun PointD.minus(other: PointD): PointD = PointD(x - other.x, y - other.y)

    private fun fillRgba(target: Mat, pixels: IntArray, width: Int) {
        val row = ByteArray(width * 4)
        val height = pixels.size / width
        for (y in 0 until height) {
            var index = 0
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                row[index++] = ((pixel shr 16) and 0xFF).toByte()
                row[index++] = ((pixel shr 8) and 0xFF).toByte()
                row[index++] = (pixel and 0xFF).toByte()
                row[index++] = ((pixel ushr 24) and 0xFF).toByte()
            }
            target.put(y, 0, row)
        }
    }

    private fun channel(lab: Mat, index: Int): Mat = Mat().also { Core.extractChannel(lab, it, index) }

    private fun Source.releaseIfOwned(gray: Mat, lab: Mat) {
        if (channel !== gray && channel !== lab) channel.release()
    }

    private data class Source(
        val channel: Mat,
        val downsample: Int,
        val sigma: Double,
        val color: Boolean,
    )

    private data class Line(val nx: Double, val ny: Double, val c: Double)

    private data class LineHypothesis(val line: Line, val length: Double, val at: Double)

    private data class Proposal(
        val corners: List<PointD>,
        val areaRatio: Double,
        val rectangularity: Double,
        val edgeSupport: Double,
        val score: Double,
    )

    private companion object {
        const val TARGET_LONG_EDGE_PX = 800
        const val GROUP_COUNT = 4
        const val MAX_LINES_PER_SIDE = 10
        const val MAX_PROPOSALS = 10
        const val MIN_LINE_FRACTION = 0.10
        const val MIN_EDGE_FRACTION = 0.12
        const val MIN_AREA_RATIO = 0.08
        const val MAX_AREA_RATIO = 0.85
        const val MAX_OPPOSITE_EDGE_RATIO = 2.0
        const val MARGIN_PX = 3
        const val LINE_DEDUPE_DISTANCE_PX = 5.0
        const val NORMAL_ALIGNMENT_THRESHOLD = 0.995
        const val SAMPLE_COUNT = 64
        const val SAMPLE_START = 0.025
        const val SAMPLE_END = 0.975
        const val DISTANCE_TOLERANCE_PX = 2.5
        const val MIN_SIDE_SUPPORT = 0.70
        const val SIDE_SAMPLE_OFFSET_PX = 6.0
        const val LAB_CONTRAST_SCALE = 40.0
        const val GRAY_LOW = 25.0
        const val GRAY_HIGH = 70.0
        const val COLOR_LOW = 12.0
        const val COLOR_HIGH = 35.0
        const val DEDUPE_DISTANCE_PX = 8.0
    }
}
