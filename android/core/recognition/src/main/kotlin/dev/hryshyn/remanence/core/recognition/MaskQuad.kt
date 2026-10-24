package dev.hryshyn.remanence.core.recognition

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc

/**
 * Card outline from a visible-card mask, ported from the reference
 * `mask_geometry.mask_to_quad`: the four longest edges of the mask's convex
 * hull are intersected, so a card photographed at an angle comes out as its
 * true quadrilateral instead of a bounding rectangle. Falls back to the
 * largest 4-vertex hull approximation.
 */
object MaskQuad {

    /** Ordered TL, TR, BR, BL corners in mask pixels, or null for an empty mask. */
    fun fromMask(mask: ByteArray, width: Int, height: Int): List<Point>? {
        val binary = Mat(height, width, CvType.CV_8UC1).apply { put(0, 0, mask) }
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(binary, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        binary.release()
        val contour = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
        val hullIdx = MatOfInt()
        Imgproc.convexHull(contour, hullIdx)
        val points = contour.toArray()
        val hull = hullIdx.toArray().map { Point(points[it].x, points[it].y) }
        if (hull.size < 4) return null
        val hull2f = MatOfPoint2f(*hull.toTypedArray())
        val perimeter = Imgproc.arcLength(hull2f, true)
        if (perimeter <= 0) return null
        val hullArea = abs(Imgproc.contourArea(hull2f))
        val detailed = approx(hull2f, 0.004 * perimeter)
        if (detailed.size >= 4) {
            val lengths = detailed.indices.map { i -> dist(detailed[(i + 1) % detailed.size], detailed[i]) }
            val selected = lengths.indices.sortedByDescending { lengths[it] }.take(4).sorted()
            val lines = selected.map { i -> detailed[i] to detailed[(i + 1) % detailed.size] }
            val inferred = (0 until 4).mapNotNull { i ->
                val a = lines[(i + 3) % 4]
                val b = lines[i]
                intersection(a.first, a.second, b.first, b.second)
            }
            if (inferred.size == 4) {
                val quad = ordered(inferred)
                val minX = hull.minOf { it.x }; val maxX = hull.maxOf { it.x }
                val minY = hull.minOf { it.y }; val maxY = hull.maxOf { it.y }
                val margin = maxOf(maxX - minX, maxY - minY) * 0.75
                val sane = quad.all { it.x.isFinite() && it.y.isFinite() } && convex(quad) &&
                    abs(shoelace(quad)) >= hullArea * 0.85 &&
                    quad.all { it.x >= minX - margin && it.x <= maxX + margin && it.y >= minY - margin && it.y <= maxY + margin }
                if (sane) return quad
            }
        }
        var best: List<Point>? = null
        for (step in 0 until 48) {
            val epsilon = 0.005 + (0.12 - 0.005) * step / 47
            val approx = approx(hull2f, epsilon * perimeter)
            if (approx.size == 4 && (best == null || abs(shoelace(approx)) > abs(shoelace(best)))) best = approx
        }
        if (best != null) return ordered(best)
        val box = Array(4) { Point() }
        Imgproc.minAreaRect(hull2f).points(box)
        return ordered(box.toList())
    }

    /** Pulls every corner toward the centroid by [fraction] of its distance. */
    fun inset(quad: List<Point>, fraction: Double): List<Point> {
        val cx = quad.sumOf { it.x } / quad.size
        val cy = quad.sumOf { it.y } / quad.size
        return quad.map { Point(it.x + (cx - it.x) * fraction, it.y + (cy - it.y) * fraction) }
    }

    private fun approx(curve: MatOfPoint2f, epsilon: Double): List<Point> {
        val out = MatOfPoint2f()
        Imgproc.approxPolyDP(curve, out, epsilon, true)
        return out.toArray().toList().also { out.release() }
    }

    private fun intersection(a: Point, b: Point, c: Point, d: Point): Point? {
        val denominator = (a.x - b.x) * (c.y - d.y) - (a.y - b.y) * (c.x - d.x)
        if (abs(denominator) < 1e-6) return null
        val d1 = a.x * b.y - a.y * b.x
        val d2 = c.x * d.y - c.y * d.x
        return Point(
            (d1 * (c.x - d.x) - (a.x - b.x) * d2) / denominator,
            (d1 * (c.y - d.y) - (a.y - b.y) * d2) / denominator,
        )
    }

    private fun ordered(points: List<Point>): List<Point> {
        val cx = points.sumOf { it.x } / points.size
        val cy = points.sumOf { it.y } / points.size
        val byAngle = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = byAngle.indices.minBy { byAngle[it].x + byAngle[it].y }
        return List(byAngle.size) { byAngle[(start + it) % byAngle.size] }
    }

    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    private fun cross(o: Point, a: Point, b: Point) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    private fun convex(points: List<Point>): Boolean {
        var sign = 0
        for (i in points.indices) {
            val c = cross(points[i], points[(i + 1) % points.size], points[(i + 2) % points.size])
            val s = if (c > 0) 1 else if (c < 0) -1 else 0
            if (s != 0) {
                if (sign != 0 && s != sign) return false
                sign = s
            }
        }
        return sign != 0
    }

    private fun shoelace(points: List<Point>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2.0
    }
}
