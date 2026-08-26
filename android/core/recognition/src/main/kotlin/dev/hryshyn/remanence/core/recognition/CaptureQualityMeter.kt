package dev.hryshyn.remanence.core.recognition

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** Raw measured capture-quality signals before any gating decision. */
data class CaptureQualitySignals(
    val laplacianVariance: Double,
    val nearBlackFraction: Double,
    val clippedWhiteFraction: Double,
    val largestGlareFraction: Double,
)

/**
 * Measures blur/exposure/glare signals on an ARGB frame
 * (docs/recognition.md section 4 initial gates). Measurement constants are
 * fixed here; the ACCEPTANCE thresholds they feed all live in the profile.
 */
class CaptureQualityMeter {

    fun measure(argbPixels: IntArray, width: Int, height: Int): CaptureQualitySignals {
        require(width > 0 && height > 0 && argbPixels.size == width * height)
        if (Core.getVersionMajor() <= 0) throw IllegalStateException("OpenCV native library not initialized")

        val rgba = Mat(height, width, CvType.CV_8UC4)
        try {
            fill(rgba, argbPixels, width)
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)

            // Blur: variance of the Laplacian response.
            val laplacian = Mat()
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            val mean = Core.mean(laplacian).`val`[0]
            val meanSq = Core.mean(laplacian.mul(laplacian, 1.0)).`val`[0]
            val laplacianVariance = meanSq - mean * mean

            // Exposure fractions from luminance cutoffs.
            var nearBlack = 0L
            var clippedWhite = 0L
            val pixels = ByteArray(width * height)
            gray.get(0, 0, pixels)
            for (luminanceByte in pixels) {
                val luminance = luminanceByte.toInt() and 0xFF
                if (luminance <= NEAR_BLACK_CUTOFF) nearBlack++
                if (luminance >= CLIPPED_WHITE_CUTOFF) clippedWhite++
            }
            val total = (width.toLong() * height).toDouble()

            // Glare: largest connected region of near-clipping luminance.
            val glareMask = Mat()
            Imgproc.threshold(gray, glareMask, GLARE_THRESHOLD.toDouble(), 255.0, Imgproc.THRESH_BINARY)
            val labels = Mat()
            val stats = Mat()
            val centroids = Mat()
            val labelCount = Imgproc.connectedComponentsWithStats(glareMask, labels, stats, centroids)
            var largestGlare = 0L
            for (label in 1 until labelCount) { // label 0 is background
                val area = stats.get(label, Imgproc.CC_STAT_AREA)[0].toLong()
                if (area > largestGlare) largestGlare = area
            }
            labels.release()
            stats.release()
            centroids.release()

            gray.release()
            laplacian.release()
            glareMask.release()

            return CaptureQualitySignals(
                laplacianVariance = laplacianVariance,
                nearBlackFraction = nearBlack / total,
                clippedWhiteFraction = clippedWhite / total,
                largestGlareFraction = largestGlare / total,
            )
        } finally {
            rgba.release()
        }
    }

    private fun fill(target: Mat, pixels: IntArray, width: Int) {
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

    private companion object {
        const val NEAR_BLACK_CUTOFF = 40
        const val CLIPPED_WHITE_CUTOFF = 252
        const val GLARE_THRESHOLD = 245
    }
}
