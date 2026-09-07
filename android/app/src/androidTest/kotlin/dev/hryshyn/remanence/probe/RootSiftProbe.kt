package dev.hryshyn.remanence.probe

import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * Probe-local RootSIFT normalization (capability probe only).
 *
 * L1-normalizes every non-negative SIFT descriptor row and applies the
 * element-wise square root, mirroring the frozen postcard-matcher oracle.
 * Implemented here — inside the probe — so the evaluation seam never depends
 * on a shared production helper. Zero or empty rows yield zero rows rather
 * than NaN/Infinity (fail closed). Pure [Mat] arithmetic; requires the
 * OpenCV native library at *runtime*, hence androidTest-only.
 *
 * The returned [Mat] is caller-owned: the caller must release it (as with
 * every locally created [Mat] in the probe tests) once assertions complete,
 * on both success and failure paths.
 */
object RootSiftProbe {

    private const val EPSILON = 1e-12f

    fun normalize(descriptors: Mat): Mat {
        // Empty first: an empty Mat carries the default header type, so the
        // CV_32F contract below must not reject blank-extraction output.
        if (descriptors.rows() == 0 || descriptors.cols() == 0) return Mat()
        require(descriptors.type() == CvType.CV_32F) {
            "RootSIFT probe expects CV_32F descriptors"
        }
        // Allocated before the fallible loop: released below on any throw so
        // only a fully normalized Mat transfers to the caller.
        val out = Mat(descriptors.rows(), descriptors.cols(), CvType.CV_32F)
        try {
            normalizeRows(descriptors, out)
        } catch (thrown: Throwable) {
            out.release()
            throw thrown
        }
        return out
    }

    private fun normalizeRows(descriptors: Mat, out: Mat) {
        val cols = descriptors.cols()
        val row = FloatArray(cols)
        val normalized = FloatArray(cols)
        for (r in 0 until descriptors.rows()) {
            descriptors.get(r, 0, row)
            var sum = 0f
            for (value in row) sum += value
            if (sum <= EPSILON) {
                out.put(r, 0, FloatArray(cols))
            } else {
                for (c in 0 until cols) {
                    val scaled = row[c] / sum
                    normalized[c] = kotlin.math.sqrt(scaled.coerceAtLeast(0f))
                }
                out.put(r, 0, normalized)
            }
        }
    }
}
