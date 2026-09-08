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
class CaptureQualityMeter private constructor(
    private val matAllocator: NativeMatAllocator,
    private val operationFault: NativeOperationFault,
) {
    constructor() : this(NativeMatAllocator.DEFAULT, NativeOperationFault.NONE)

    internal constructor(
        matAllocator: NativeMatAllocator,
        operationFault: NativeOperationFault = NativeOperationFault.NONE,
        testOnly: Unit = Unit,
    ) : this(matAllocator, operationFault)

    fun measure(argbPixels: IntArray, width: Int, height: Int): CaptureQualitySignals {
        require(width > 0 && height > 0 && argbPixels.size == width * height)
        if (Core.getVersionMajor() <= 0) throw IllegalStateException("OpenCV native library not initialized")

        val owner = NativeMatOwner(matAllocator)
        try {
            val rgba = owner.allocate { Mat(height, width, CvType.CV_8UC4) }
            fill(rgba, argbPixels, width)
            val grayMat = owner.allocate { Mat() }
            Imgproc.cvtColor(rgba, grayMat, Imgproc.COLOR_RGBA2GRAY)

            // Blur: variance of the Laplacian response.
            val laplacianMat = owner.allocate { Mat() }
            Imgproc.Laplacian(grayMat, laplacianMat, CvType.CV_64F)
            val laplacianSquaredMat = owner.allocate { laplacianMat.mul(laplacianMat, 1.0) }
            operationFault.check("after-laplacian-mul")
            val mean = Core.mean(laplacianMat).`val`[0]
            val meanSq = Core.mean(laplacianSquaredMat).`val`[0]
            val laplacianVariance = meanSq - mean * mean

            // Exposure fractions from luminance cutoffs.
            var nearBlack = 0L
            var clippedWhite = 0L
            val pixels = ByteArray(width * height)
            grayMat.get(0, 0, pixels)
            for (luminanceByte in pixels) {
                val luminance = luminanceByte.toInt() and 0xFF
                if (luminance <= NEAR_BLACK_CUTOFF) nearBlack++
                if (luminance >= CLIPPED_WHITE_CUTOFF) clippedWhite++
            }
            val total = (width.toLong() * height).toDouble()

            // Glare: largest connected region of near-clipping luminance.
            val glareMaskMat = owner.allocate { Mat() }
            Imgproc.threshold(grayMat, glareMaskMat, GLARE_THRESHOLD.toDouble(), 255.0, Imgproc.THRESH_BINARY)
            val labelsMat = owner.allocate { Mat() }
            val statsMat = owner.allocate { Mat() }
            val centroidsMat = owner.allocate { Mat() }
            val labelCount = Imgproc.connectedComponentsWithStats(glareMaskMat, labelsMat, statsMat, centroidsMat)
            var largestGlare = 0L
            for (label in 1 until labelCount) { // label 0 is background
                val area = statsMat.get(label, Imgproc.CC_STAT_AREA)[0].toLong()
                if (area > largestGlare) largestGlare = area
            }

            return CaptureQualitySignals(
                laplacianVariance = laplacianVariance,
                nearBlackFraction = nearBlack / total,
                clippedWhiteFraction = clippedWhite / total,
                largestGlareFraction = largestGlare / total,
            )
        } finally {
            owner.releaseAll()
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
