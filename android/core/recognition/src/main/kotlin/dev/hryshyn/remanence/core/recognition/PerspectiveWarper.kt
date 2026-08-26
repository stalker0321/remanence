package dev.hryshyn.remanence.core.recognition

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc

/** Upright, aspect-preserving normalized capture in ARGB int packing. */
class WarpedCapture(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
)

/**
 * Perspective-normalizes an ordered postcard quadrilateral so the long edge
 * lands exactly on the profile's canonical long edge while the detected
 * aspect ratio is preserved (docs/recognition.md section 4).
 */
class PerspectiveWarper(private val profile: RecognitionProfile) {

    fun warp(
        argbPixels: IntArray,
        width: Int,
        height: Int,
        orderedCorners: List<PointD>,
    ): WarpedCapture {
        require(width > 0 && height > 0 && argbPixels.size == width * height)
        when (val validation = CornerGeometry.validateQuad(orderedCorners)) {
            is CornerGeometry.QuadValidation.Invalid ->
                throw IllegalArgumentException("cannot warp invalid quad: ${validation.reason}")
            is CornerGeometry.QuadValidation.Valid -> Unit
        }
        if (Core.getVersionMajor() <= 0) {
            throw IllegalStateException("OpenCV native library not initialized")
        }

        val longEdgePx = maxEdgeLength(orderedCorners)
        val shortEdgePx = minEdgeLength(orderedCorners)
        val scale = profile.capture.canonicalLongEdgePx.toDouble() / longEdgePx
        val targetLong = profile.capture.canonicalLongEdgePx
        val targetShort = kotlin.math.round(shortEdgePx * scale).toInt().coerceIn(1, MAX_SHORT_EDGE_PX)
        if (targetLong * targetShort.toLong() > MAX_WARP_PIXELS) {
            throw IllegalArgumentException("warped output exceeds pixel budget")
        }

        val src = MatOfPoint2f(
            Point(orderedCorners[0].x, orderedCorners[0].y),
            Point(orderedCorners[1].x, orderedCorners[1].y),
            Point(orderedCorners[2].x, orderedCorners[2].y),
            Point(orderedCorners[3].x, orderedCorners[3].y),
        )
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point((targetLong - 1).toDouble(), 0.0),
            Point((targetLong - 1).toDouble(), (targetShort - 1).toDouble()),
            Point(0.0, (targetShort - 1).toDouble()),
        )
        val transform = Imgproc.getPerspectiveTransform(src, dst)

        val source = Mat(height, width, CvType.CV_8UC4)
        try {
            fillMat(source, argbPixels, width)
            val output = Mat()
            try {
                Imgproc.warpPerspective(source, output, transform, org.opencv.core.Size(targetLong.toDouble(), targetShort.toDouble()))
                return WarpedCapture(readPixels(output), targetLong, targetShort)
            } finally {
                output.release()
            }
        } finally {
            source.release()
            src.release()
            dst.release()
            transform.release()
        }
    }

    private fun maxEdgeLength(corners: List<PointD>): Double =
        edges(corners).max()

    private fun minEdgeLength(corners: List<PointD>): Double =
        edges(corners).min()

    private fun edges(corners: List<PointD>): List<Double> = buildList {
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            add(kotlin.math.hypot(b.x - a.x, b.y - a.y))
        }
    }

    private fun fillMat(target: Mat, pixels: IntArray, width: Int) {
        val row = ByteArray(width * 4)
        val height = pixels.size / width
        for (y in 0 until height) {
            var index = 0
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                row[index++] = ((p shr 16) and 0xFF).toByte()
                row[index++] = ((p shr 8) and 0xFF).toByte()
                row[index++] = (p and 0xFF).toByte()
                row[index++] = ((p ushr 24) and 0xFF).toByte()
            }
            target.put(y, 0, row)
        }
    }

    private fun readPixels(source: Mat): IntArray {
        val width = source.cols()
        val height = source.rows()
        val out = IntArray(width * height)
        val row = ByteArray(width * 4)
        for (y in 0 until height) {
            source.get(y, 0, row)
            for (x in 0 until width) {
                val i = x * 4
                val r = row[i].toInt() and 0xFF
                val g = row[i + 1].toInt() and 0xFF
                val b = row[i + 2].toInt() and 0xFF
                val a = row[i + 3].toInt() and 0xFF
                out[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    private companion object {
        const val MAX_SHORT_EDGE_PX = 4000
        const val MAX_WARP_PIXELS = 16_000_000L
    }
}
