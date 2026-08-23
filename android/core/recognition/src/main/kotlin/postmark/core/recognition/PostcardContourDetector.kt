package postmark.core.recognition

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * One detected quadrilateral candidate before ranking/gating.
 * [corners] are canonically ordered clockwise from top-left.
 */
data class QuadCandidate(
    val corners: List<PointD>,
    val areaRatio: Double,
    val rectangularity: Double,
)

/**
 * Postcard contour candidate detector implementing the documented pipeline
 * (docs/recognition.md section 4): grayscale, light denoise, automatic edge
 * detection, morphological close, contour enumeration, convex four-point
 * approximation. The caller must have initialized the OpenCV native runtime.
 */
class PostcardContourDetector(private val profile: RecognitionProfile) {

    init {
        require(profile.capture.minCardAreaRatio > 0.0)
    }

    fun detect(argbPixels: IntArray, width: Int, height: Int): List<QuadCandidate> {
        require(width > 0 && height > 0) { "invalid frame dimensions" }
        require(argbPixels.size == width * height) { "pixel buffer does not match dimensions" }
        if (Core.getVersionMajor() <= 0) {
            throw IllegalStateException("OpenCV native library not initialized")
        }

        val rgba = Mat(height, width, CvType.CV_8UC4)
        try {
            fillRgba(rgba, argbPixels, width, height)
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)

            val denoised = Mat()
            Imgproc.GaussianBlur(gray, denoised, Size(5.0, 5.0), 0.0)

            // Automatic Canny thresholds from mean luminance (adaptive/automatic per profile).
            val mean = Core.mean(denoised).`val`[0]
            val low = COEFF_LOW * mean
            val high = COEFF_HIGH * mean
            val edges = Mat()
            Imgproc.Canny(denoised, edges, low, high)

            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(CLOSE_KERNEL_PX.toDouble(), CLOSE_KERNEL_PX.toDouble()),
            )
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            try {
                Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            } finally {
                hierarchy.release()
            }

            val frameArea = width.toDouble() * height
            val candidates = ArrayList<QuadCandidate>()
            for (contour in contours) {
                val candidate = toQuadCandidate(contour, frameArea) ?: continue
                candidates += candidate
                contour.release()
            }
            // Release any contour we skipped.
            contours.forEach { if (!it.empty()) it.release() }
            gray.release()
            denoised.release()
            edges.release()
            kernel.release()
            return candidates.sortedByDescending { it.areaRatio }
        } finally {
            rgba.release()
        }
    }

    private fun toQuadCandidate(contour: MatOfPoint, frameArea: Double): QuadCandidate? {
        val perimeter = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
        val approx = MatOfPoint2f()
        try {
            Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, APPROX_EPSILON_RATIO * perimeter, true)
            val points = approx.toArray()
            if (points.size != 4) return null
            if (!Imgproc.isContourConvex(MatOfPoint(*points))) return null

            val quadArea = abs(Imgproc.contourArea(approx))
            if (quadArea <= 0.0) return null
            val areaRatio = quadArea / frameArea
            if (areaRatio < MIN_CANDIDATE_AREA_RATIO) return null

            val contourArea = abs(Imgproc.contourArea(contour))
            val corners = CornerGeometry.orderClockwiseFromTopLeft(
                points.map { PointD(it.x, it.y) },
            )
            return QuadCandidate(
                corners = corners,
                areaRatio = areaRatio,
                rectangularity = if (quadArea > 0) contourArea / quadArea else 0.0,
            )
        } finally {
            approx.release()
        }
    }

    private fun fillRgba(target: Mat, pixels: IntArray, width: Int, height: Int) {
        val row = ByteArray(width * 4)
        for (y in 0 until height) {
            var index = 0
            val rowStart = y * width
            for (x in 0 until width) {
                val pixel = pixels[rowStart + x]
                row[index++] = ((pixel shr 16) and 0xFF).toByte() // R -> R channel slot
                row[index++] = ((pixel shr 8) and 0xFF).toByte()
                row[index++] = (pixel and 0xFF).toByte()
                row[index++] = ((pixel ushr 24) and 0xFF).toByte()
            }
            target.put(y, 0, row)
        }
    }

    private fun abs(value: Double): Double = kotlin.math.abs(value)

    internal companion object {
        const val COEFF_LOW = 0.66
        const val COEFF_HIGH = 1.33
        const val CLOSE_KERNEL_PX = 7
        const val APPROX_EPSILON_RATIO = 0.02

        /** Loose pre-filter only; the profile capture gate is applied later during quality scoring. */
        internal const val MIN_CANDIDATE_AREA_RATIO = 0.01
    }
}
