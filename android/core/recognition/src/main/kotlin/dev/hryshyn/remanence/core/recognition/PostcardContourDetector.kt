package dev.hryshyn.remanence.core.recognition

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
    /** Optional independent side-support evidence from a line locator. */
    val edgeSupport: Double? = null,
)

/**
 * Postcard contour candidate detector implementing the documented pipeline
 * (docs/recognition.md section 4): grayscale, light denoise, automatic edge
 * detection, morphological close, contour enumeration, convex four-point
 * approximation. The caller must have initialized the OpenCV native runtime.
 */
class PostcardContourDetector private constructor(
    private val profile: RecognitionProfile,
    private val matAllocator: NativeMatAllocator,
    private val operationFault: NativeOperationFault,
    private val contourObserver: ((List<MatOfPoint>) -> Unit)?,
) {
    constructor(profile: RecognitionProfile) : this(
        profile,
        NativeMatAllocator.DEFAULT,
        NativeOperationFault.NONE,
        null,
    )

    internal constructor(
        profile: RecognitionProfile,
        matAllocator: NativeMatAllocator,
        operationFault: NativeOperationFault = NativeOperationFault.NONE,
        contourObserver: ((List<MatOfPoint>) -> Unit)? = null,
        testOnly: Unit = Unit,
    ) : this(profile, matAllocator, operationFault, contourObserver)

    init {
        require(profile.capture.minCardAreaRatio > 0.0)
    }

    fun detect(argbPixels: IntArray, width: Int, height: Int): List<QuadCandidate> {
        require(width > 0 && height > 0) { "invalid frame dimensions" }
        require(argbPixels.size == width * height) { "pixel buffer does not match dimensions" }
        if (Core.getVersionMajor() <= 0) {
            throw IllegalStateException("OpenCV native library not initialized")
        }

        val owner = NativeMatOwner(matAllocator)
        var contours: ArrayList<MatOfPoint>? = null
        try {
            val rgba = owner.allocate { Mat(height, width, CvType.CV_8UC4) }
            fillRgba(rgba, argbPixels, width, height)
            val grayMat = owner.allocate { Mat() }
            Imgproc.cvtColor(rgba, grayMat, Imgproc.COLOR_RGBA2GRAY)

            val denoisedMat = owner.allocate { Mat() }
            Imgproc.GaussianBlur(grayMat, denoisedMat, Size(5.0, 5.0), 0.0)

            // Automatic Canny thresholds from mean luminance (adaptive/automatic per profile).
            val mean = Core.mean(denoisedMat).`val`[0]
            val low = COEFF_LOW * mean
            val high = COEFF_HIGH * mean
            val edgesMat = owner.allocate { Mat() }
            Imgproc.Canny(denoisedMat, edgesMat, low, high)

            val kernelMat = owner.allocate {
                Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(CLOSE_KERNEL_PX.toDouble(), CLOSE_KERNEL_PX.toDouble()),
                )
            }
            Imgproc.morphologyEx(edgesMat, edgesMat, Imgproc.MORPH_CLOSE, kernelMat)

            val contourList = ArrayList<MatOfPoint>().also { contours = it }
            val hierarchyMat = owner.allocate { Mat() }
            Imgproc.findContours(edgesMat, contourList, hierarchyMat, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            contourObserver?.invoke(contourList)

            val frameArea = width.toDouble() * height
            val candidates = ArrayList<QuadCandidate>()
            for (contour in contourList) {
                val candidate = toQuadCandidate(contour, frameArea) ?: continue
                candidates += candidate
            }
            return candidates.sortedByDescending { it.areaRatio }
        } finally {
            contours?.forEach { it.release() }
            owner.releaseAll()
        }
    }

    private fun toQuadCandidate(contour: MatOfPoint, frameArea: Double): QuadCandidate? {
        val owner = NativeMatOwner(matAllocator)
        try {
            val contourPoints = owner.allocate { MatOfPoint2f(*contour.toArray()) }
            operationFault.check("after-contour-points")
            val perimeter = Imgproc.arcLength(contourPoints, true)
            val approx = owner.allocate { MatOfPoint2f() }
            Imgproc.approxPolyDP(contourPoints, approx, APPROX_EPSILON_RATIO * perimeter, true)
            operationFault.check("after-contour-approximation")
            val points = approx.toArray()
            if (points.size != 4) return null
            val convexInput = owner.allocate { MatOfPoint(*points) }
            if (!Imgproc.isContourConvex(convexInput)) return null

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
            owner.releaseAll()
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
