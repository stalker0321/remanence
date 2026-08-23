package postmark.core.recognition

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Extracts the versioned `mvp-orb-v1` postcard fingerprint from a canonical
 * warped capture: ORB features over BOTH the plain grayscale and an explicitly
 * versioned CLAHE branch, nearby-keypoint deduplication, response-ranked cap,
 * and a DCT perceptual coarse hash used only for diagnostics/tie context
 * (docs/recognition.md sections 4 and 6).
 */
class FingerprintExtractor(private val profile: RecognitionProfile) {

    fun extract(
        warpedArgb: IntArray,
        width: Int,
        height: Int,
        side: FingerprintSide,
    ): PostcardFingerprint {
        require(width > 0 && height > 0 && warpedArgb.size == width * height)
        if (Core.getVersionMajor() <= 0) throw IllegalStateException("OpenCV native library not initialized")

        val gray = Mat(height, width, CvType.CV_8UC1)
        try {
            fillGray(gray, warpedArgb, width)

            val merged = ArrayList<WeightedKeypoint>()
            merged += extractBranch(gray)
            val claheSource = Mat()
            val clahe = Imgproc.createCLAHE(CLAHE_CLIP_LIMIT, Size(CLAHE_TILE_PX.toDouble(), CLAHE_TILE_PX.toDouble()))
            try {
                clahe.apply(gray, claheSource)
                merged += extractBranch(claheSource)
                clahe.collectGarbage()
            } finally {
                claheSource.release()
            }

            val kept = deduplicateAndCap(merged)
            val descriptors = ByteArray(kept.size * DESCRIPTOR_BYTES)
            var outIndex = 0
            for (keypoint in kept) {
                System.arraycopy(keypoint.descriptor, 0, descriptors, outIndex, DESCRIPTOR_BYTES)
                outIndex += DESCRIPTOR_BYTES
            }

            return PostcardFingerprint(
                profileId = profile.profileId,
                side = side,
                canonicalWidthPx = width,
                canonicalHeightPx = height,
                coarseHash64 = coarseHash(gray),
                keypoints = kept.map { it.toDomain(width, height) },
                descriptors = List(kept.size) { i ->
                    descriptors.copyOfRange(i * DESCRIPTOR_BYTES, (i + 1) * DESCRIPTOR_BYTES)
                },
                quality = ExtractionQuality(
                    blurScore = 0.0,
                    exposureScore = 0.0,
                    glareFraction = 0.0,
                    detectedAreaRatio = 1.0,
                ),
            )
        } finally {
            gray.release()
        }
    }

    internal class WeightedKeypoint(
        val x: Float,
        val y: Float,
        val response: Float,
        val descriptor: ByteArray,
        val angleDegrees: Double,
        val scaleNormalized: Double,
        val octave: Int,
    ) {
        fun toDomain(frameWidth: Int, frameHeight: Int): FingerprintKeypoint =
            FingerprintKeypoint(
                xNormalized = x / frameWidth.toDouble(),
                yNormalized = y / frameHeight.toDouble(),
                scaleNormalized = scaleNormalized.coerceAtLeast(0.0),
                angleCentiDegrees = ((Math.round(angleDegrees * 100.0)) % 36000L).toInt(),
                responseQuantized = response.toRawBits() and 0x7FFFFFFF,
                octave = octave,
            )
    }

    private fun extractBranch(gray: Mat): List<WeightedKeypoint> {
        val orb = org.opencv.features2d.ORB.create(
            profile.orb.nfeatures,
            profile.orb.scaleFactor.toFloat(),
            profile.orb.nlevels,
            profile.orb.edgeThreshold,
            profile.orb.firstLevel,
            profile.orb.wtaK,
            if (profile.orb.scoreTypeHarris) {
                org.opencv.features2d.ORB.HARRIS_SCORE
            } else {
                org.opencv.features2d.ORB.FAST_SCORE
            },
            profile.orb.patchSize,
            profile.orb.fastThreshold,
        )
        try {
            val keypoints = MatOfKeyPoint()
            val descriptors = Mat()
            try {
                orb.detectAndCompute(gray, Mat(), keypoints, descriptors)
                val points = keypoints.toArray()
                if (points.isEmpty()) return emptyList()
                val rows = points.size
                val buffer = ByteArray(rows * DESCRIPTOR_BYTES)
                // ORB descriptors are CV_8U rows of 32 bytes.
                val rowBytes = ByteArray(descriptors.cols().coerceAtLeast(0))
                for (row in 0 until rows) {
                    descriptors.get(row, 0, rowBytes)
                    System.arraycopy(rowBytes, 0, buffer, row * DESCRIPTOR_BYTES, DESCRIPTOR_BYTES)
                }
                return points.mapIndexed { index, kp ->
                    WeightedKeypoint(
                        x = kp.pt.x.toFloat(),
                        y = kp.pt.y.toFloat(),
                        response = kp.response.toFloat(),
                        descriptor = buffer.copyOfRange(index * DESCRIPTOR_BYTES, (index + 1) * DESCRIPTOR_BYTES),
                        angleDegrees = kp.angle.toDouble(),
                        scaleNormalized = kp.size / gray.cols().toDouble(),
                        octave = kp.octave,
                    )
                }
            } finally {
                keypoints.release()
                descriptors.release()
            }
        } finally {
            orb.clear()
        }
    }

    internal fun probe(x: Float, y: Float, response: Float): WeightedKeypoint =
        WeightedKeypoint(x, y, response, ByteArray(DESCRIPTOR_BYTES), 0.0, 0.0, 0)

    /** Keeps the highest-response point of any cluster within [DEDUP_RADIUS_PX], capped at nfeatures. */
    internal fun deduplicateAndCap(points: List<WeightedKeypoint>): List<WeightedKeypoint> {
        val sorted = points.sortedByDescending { it.response }
        val kept = ArrayList<WeightedKeypoint>(profile.orb.nfeatures)
        val radiusSq = DEDUP_RADIUS_PX * DEDUP_RADIUS_PX
        for (candidate in sorted) {
            if (kept.size >= profile.orb.nfeatures) break
            var tooClose = false
            for (existing in kept) {
                val dx = existing.x - candidate.x
                val dy = existing.y - candidate.y
                if (dx * dx + dy * dy < radiusSq) {
                    tooClose = true
                    break
                }
            }
            if (!tooClose) kept += candidate
        }
        return kept
    }

    private fun coarseHash(gray: Mat): Long {
        val small = Mat()
        val floatSmall = Mat()
        val dct = Mat()
        return try {
            Imgproc.resize(gray, small, Size(HASH_SIZE.toDouble(), HASH_SIZE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            small.convertTo(floatSmall, CvType.CV_32F)
            Core.dct(floatSmall, dct)
            val values = FloatArray(HASH_SIZE * HASH_SIZE)
            dct.get(0, 0, values)
            // Exclude the DC term when computing the median.
            val ac = values.sliceArray(1 until values.size)
            val median = ac.sorted()[ac.size / 2].toDouble()
            var packed = 0L
            for (i in 1 until values.size) {
                val bit = if (values[i] > median) 1L else 0L
                packed = (packed shl 1) or bit
            }
            packed
        } finally {
            small.release()
            floatSmall.release()
            dct.release()
        }
    }

    private fun fillGray(target: Mat, argbPixels: IntArray, width: Int) {
        val height = argbPixels.size / width
        val row = ByteArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = argbPixels[y * width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // Same weights as OpenCV RGBA2GRAY for consistency across branches.
                val luminance = (299 * r + 587 * g + 114 * b) / 1000
                row[x] = luminance.coerceIn(0, 255).toByte()
            }
            target.put(y, 0, row)
        }
    }

    internal companion object {
        const val DESCRIPTOR_BYTES = 32
        const val DEDUP_RADIUS_PX = 2.0
        const val CLAHE_CLIP_LIMIT = 2.0
        const val CLAHE_TILE_PX = 8
        const val HASH_SIZE = 8
        const val ORB_CLASS = "Feature2D.ORB"
    }
}
