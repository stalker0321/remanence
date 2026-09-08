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
class PerspectiveWarper private constructor(
    private val profile: RecognitionProfile,
    private val matAllocator: NativeMatAllocator,
    private val operationFault: NativeOperationFault,
) {
    constructor(profile: RecognitionProfile) : this(
        profile,
        NativeMatAllocator.DEFAULT,
        NativeOperationFault.NONE,
    )

    internal constructor(
        profile: RecognitionProfile,
        matAllocator: NativeMatAllocator,
        operationFault: NativeOperationFault = NativeOperationFault.NONE,
        testOnly: Unit = Unit,
    ) : this(profile, matAllocator, operationFault)

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

        val edgesPx = orderedCorners.mapIndexed { index, corner ->
            edgeLength(corner, orderedCorners[(index + 1) % orderedCorners.size])
        }
        // Use the same opposite-edge averages as PostcardCropSelector so a
        // perspective-skewed quad cannot change orientation based on one edge.
        val horizontalEdgePx = (edgesPx[0] + edgesPx[2]) / 2.0
        val verticalEdgePx = (edgesPx[1] + edgesPx[3]) / 2.0
        val landscape = horizontalEdgePx >= verticalEdgePx
        val canonicalCorners = if (landscape) {
            orderedCorners
        } else {
            // A portrait quad is rotated into the canonical landscape frame.
            // This preserves clockwise winding and restores the source's
            // right edge as the canonical top edge deterministically.
            listOf(orderedCorners[1], orderedCorners[2], orderedCorners[3], orderedCorners[0])
        }
        val longEdgePx = maxOf(horizontalEdgePx, verticalEdgePx)
        val shortEdgePx = minOf(horizontalEdgePx, verticalEdgePx)
        val scale = profile.capture.canonicalLongEdgePx.toDouble() / longEdgePx
        val targetLong = profile.capture.canonicalLongEdgePx
        val targetShort = kotlin.math.round(shortEdgePx * scale).toInt().coerceIn(1, MAX_SHORT_EDGE_PX)
        if (targetLong * targetShort.toLong() > MAX_WARP_PIXELS) {
            throw IllegalArgumentException("warped output exceeds pixel budget")
        }

        val targetWidth = targetLong
        val targetHeight = targetShort
        val owner = NativeMatOwner(matAllocator)
        try {
            val srcMat = owner.allocate {
                MatOfPoint2f(
                Point(canonicalCorners[0].x, canonicalCorners[0].y),
                Point(canonicalCorners[1].x, canonicalCorners[1].y),
                Point(canonicalCorners[2].x, canonicalCorners[2].y),
                Point(canonicalCorners[3].x, canonicalCorners[3].y),
                )
            }
            val dstMat = owner.allocate {
                MatOfPoint2f(
                Point(0.0, 0.0),
                Point((targetWidth - 1).toDouble(), 0.0),
                Point((targetWidth - 1).toDouble(), (targetHeight - 1).toDouble()),
                Point(0.0, (targetHeight - 1).toDouble()),
                )
            }
            val transformMat = owner.allocate { Imgproc.getPerspectiveTransform(srcMat, dstMat) }
            val sourceMat = owner.allocate { Mat(height, width, CvType.CV_8UC4) }
            fillMat(sourceMat, argbPixels, width)
            val outputMat = owner.allocate { Mat() }
            operationFault.check("before-warp")
            Imgproc.warpPerspective(
                sourceMat,
                outputMat,
                transformMat,
                org.opencv.core.Size(targetWidth.toDouble(), targetHeight.toDouble()),
            )
            operationFault.check("before-read")
            return WarpedCapture(readPixels(outputMat), targetWidth, targetHeight)
        } finally {
            owner.releaseAll()
        }
    }

    private fun edgeLength(a: PointD, b: PointD): Double =
        kotlin.math.hypot(b.x - a.x, b.y - a.y)

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
