package dev.hryshyn.remanence.core.recognition

import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprint
import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec
import dev.hryshyn.remanence.core.model.SiftRootSiftKeypoint
import kotlin.math.floor
import kotlin.math.min
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Size
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc

/**
 * P1's standalone native OpenCV SIFT extractor.
 *
 * This class deliberately returns raw quantized SIFT rows.  It never performs
 * RootSIFT; the one RootSIFT derivation belongs to the future P2 matcher.
 * Quality measurements remain advisory telemetry outside this extractor and
 * therefore cannot turn a valid feature extraction into a quality rejection.
 *
 * Candidate selection follows the reference implementation's 6x6 spatial
 * distribution and 45-point cell limit, with a deterministic global 1,500
 * point cap added by the P1 contract.  A candidate's response is the primary
 * ranking key.  Equal responses are ordered by cell row, cell column, pixel
 * position, size, angle, decoded octave, and finally the native detection
 * index.  The final native index is the deterministic last tie-break for
 * genuinely identical detections and keeps descriptor/keypoint alignment.
 */
class SiftRootSiftFingerprintExtractor(
    private val settings: RecognitionProfile.SiftExtraction =
        RecognitionProfile.postcardSiftRootSiftV1().sift,
) {

    fun extract(
        warpedArgb: IntArray,
        width: Int,
        height: Int,
    ): SiftRootSiftFingerprint = try {
        extractInternal(warpedArgb, width, height)
    } catch (failure: UnsatisfiedLinkError) {
        // The native operation includes its finally cleanup; translate a
        // linkage failure from either the body or cleanup at this boundary.
        throw OpenCvUnavailableException(failure)
    }

    private fun extractInternal(
        warpedArgb: IntArray,
        width: Int,
        height: Int,
    ): SiftRootSiftFingerprint {
        val pixelCount = checkedPixelCount(width, height)
        require(pixelCount == warpedArgb.size.toLong()) {
            "frame pixels do not match dimensions"
        }
        requireOpenCv()

        var gray: Mat? = null
        var mask: Mat? = null
        var detectedKeypoints: MatOfKeyPoint? = null
        var selectedKeypoints: MatOfKeyPoint? = null
        var descriptors: Mat? = null
        var sift: SIFT? = null
        var outputDescriptorsOwned: List<ByteArray>? = null
        var returnedFingerprint = false
        var cleanupAttempted = false
        var primaryFailure: Throwable? = null
        val cleanupActions = listOf<() -> Unit>(
            { sift?.clear() },
            { descriptors?.release() },
            { selectedKeypoints?.release() },
            { detectedKeypoints?.release() },
            { mask?.release() },
            { gray?.release() },
        )
        try {
            val grayMat = Mat(height, width, CvType.CV_8UC1)
            gray = grayMat
            val maskMat = Mat()
            mask = maskMat
            val detectedMat = MatOfKeyPoint()
            detectedKeypoints = detectedMat
            val selectedMat = MatOfKeyPoint()
            selectedKeypoints = selectedMat
            val descriptorsMat = Mat()
            descriptors = descriptorsMat
            val detector = SIFT.create(
                settings.nfeatures,
                settings.octaveLayers,
                settings.contrastThreshold,
                settings.edgeThreshold,
                settings.sigma,
            )
            sift = detector
            fillGray(grayMat, warpedArgb, width)
            detector.detect(grayMat, detectedMat, maskMat)
            val detected = detectedMat.toArray()
            require(detected.size <= MAX_DETECTED_KEYPOINTS) {
                "detected keypoint count exceeds bounded extraction input"
            }
            val candidates = detected.mapIndexed { index, point ->
                candidateFromKeyPoint(point, index)
            }
            val selected = selectCandidates(width, height, candidates)
            require(selected.isNotEmpty()) { "SIFT produced no usable keypoints" }

            // The reference selects points before computing descriptors.  This
            // also bounds descriptor allocation to the selected 1,500 rows.
            selectedMat.fromArray(*selected.map { detected[it.candidate.sourceIndex] }.toTypedArray())
            detector.compute(grayMat, selectedMat, descriptorsMat)
            val computed = selectedMat.toArray()
            require(computed.size == selected.size && descriptorsMat.rows() == selected.size) {
                "SIFT descriptor/keypoint count mismatch"
            }
            require(descriptorsMat.cols() == DESCRIPTOR_BYTES && descriptorsMat.type() == CvType.CV_32F) {
                "SIFT descriptor shape or type is invalid"
            }

            val outputKeypoints = ArrayList<SiftRootSiftKeypoint>(selected.size)
            val outputDescriptors = ArrayList<ByteArray>(selected.size)
            outputDescriptorsOwned = outputDescriptors
            val rawRow = FloatArray(DESCRIPTOR_BYTES)
            try {
                for (row in computed.indices) {
                    val point = computed[row]
                    val outputCandidate = candidateFromKeyPoint(
                        point,
                        selected[row].candidate.sourceIndex,
                    )
                    val descriptor = readRawDescriptor(descriptorsMat, row, rawRow)
                    try {
                        outputKeypoints += outputCandidate.toDomain(width, height)
                        outputDescriptors += descriptor
                    } catch (failure: Throwable) {
                        // The current descriptor has not necessarily entered
                        // the owned list when collection insertion fails.
                        descriptor.fill(0)
                        throw failure
                    }
                }
            } finally {
                rawRow.fill(0f)
            }
            require(outputKeypoints.isNotEmpty()) { "SIFT produced no usable features" }
            require(hasMatcherUsableDescriptorRows(outputDescriptors)) {
                "SIFT produced no matcher-usable descriptor rows"
            }

            val fingerprint = SiftRootSiftFingerprint(
                profileId = SiftRootSiftFingerprintCodec.PROFILE_ID,
                canonicalWidthPx = width,
                canonicalHeightPx = height,
                coarseHash64 = coarseHash(grayMat),
                keypoints = outputKeypoints,
                quantizedSiftDescriptors = outputDescriptors,
            )
            // Native cleanup must complete before ownership of the descriptor
            // rows can transfer to the returned domain object. If cleanup
            // fails, the normal failure path below wipes those rows.
            cleanupAttempted = true
            try {
                cleanupBeforeTransfer(outputDescriptors, cleanupActions)
            } catch (failure: Throwable) {
                // cleanupBeforeTransfer has already wiped the rows it still
                // owns; clear the outer owner so that path is not wiped twice.
                outputDescriptorsOwned = null
                throw failure
            }
            outputDescriptorsOwned = null
            returnedFingerprint = true
            return fingerprint
        } catch (failure: UnsatisfiedLinkError) {
            val unavailable = OpenCvUnavailableException(failure)
            primaryFailure = unavailable
            throw unavailable
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (!returnedFingerprint) {
                outputDescriptorsOwned?.forEach { it.fill(0) }
            }
            if (!cleanupAttempted) {
                cleanupAttempted = true
                // OpenCV Java objects own native allocations even when an
                // exception occurs during detection, conversion, or validation.
                finishCleanup(primaryFailure, cleanupActions)
            }
        }
    }

    /** A copied native detection used by tests to exercise selection policy. */
    internal data class SiftCandidate(
        val xPx: Double,
        val yPx: Double,
        val sizePx: Double,
        val angleDegrees: Double,
        val response: Double,
        val encodedOctave: Int,
        val sourceIndex: Int,
    )

    internal data class SelectedSiftCandidate(
        val candidate: SiftCandidate,
        val cellX: Int,
        val cellY: Int,
    )

    /**
     * Deterministically validates, distributes, and caps copied detections.
     * This is intentionally an internal seam: it contains no production
     * alternative extractor or matcher behavior.
     */
    internal fun selectCandidates(
        width: Int,
        height: Int,
        candidates: List<SiftCandidate>,
    ): List<SelectedSiftCandidate> {
        require(width in 1..MAX_CANONICAL_DIMENSION_PX) { "frame width out of bounds" }
        require(height in 1..MAX_CANONICAL_DIMENSION_PX) { "frame height out of bounds" }
        checkedPixelCount(width, height)
        require(candidates.size <= MAX_DETECTED_KEYPOINTS) {
            "detected keypoint count exceeds bounded extraction input"
        }
        require(candidates.isNotEmpty()) { "SIFT produced no usable keypoints" }

        val prepared = candidates.map { candidate ->
            val normalizedX = candidate.xPx / width.toDouble()
            val normalizedY = candidate.yPx / height.toDouble()
            require(candidate.sourceIndex >= 0) { "negative source index" }
            require(candidate.xPx.isFinite() && candidate.yPx.isFinite()) {
                "SIFT coordinates must be finite"
            }
            require(candidate.xPx in 0.0..width.toDouble() && candidate.yPx in 0.0..height.toDouble()) {
                "SIFT coordinates out of frame bounds"
            }
            require(candidate.sizePx.isFinite() && candidate.sizePx > 0.0) {
                "SIFT size must be finite and positive"
            }
            val scale = candidate.sizePx / width.toDouble()
            require(scale.isFinite() && scale > 0.0) { "SIFT scale is invalid" }
            require(candidate.angleDegrees.isFinite() &&
                candidate.angleDegrees >= 0.0 && candidate.angleDegrees < 360.0
            ) { "SIFT angle is invalid" }
            require(candidate.response.isFinite() && candidate.response >= 0.0) {
                "SIFT response must be finite and non-negative"
            }
            require(candidate.encodedOctave in MIN_OCTAVE..MAX_OCTAVE) {
                "SIFT octave exceeds bounded metadata"
            }
            PreparedCandidate(
                candidate = candidate,
                cellX = cell(normalizedX, settings.gridSize),
                cellY = cell(normalizedY, settings.gridSize),
            )
        }

        val cells = Array(settings.gridSize * settings.gridSize) { ArrayList<PreparedCandidate>() }
        prepared.forEach { item ->
            cells[item.cellY * settings.gridSize + item.cellX] += item
        }
        val perCell = ArrayList<PreparedCandidate>(settings.maxKeypoints + settings.gridSize * settings.gridSize)
        cells.forEach { cellCandidates ->
            cellCandidates.sortWith(candidateComparator)
            perCell += cellCandidates.take(settings.maxPerCell)
        }
        perCell.sortWith(candidateComparator)
        return perCell.take(settings.maxKeypoints).map {
            SelectedSiftCandidate(it.candidate, it.cellX, it.cellY)
        }
    }

    private data class PreparedCandidate(
        val candidate: SiftCandidate,
        val cellX: Int,
        val cellY: Int,
    )

    private fun candidateFromKeyPoint(point: KeyPoint, sourceIndex: Int): SiftCandidate =
        SiftCandidate(
            xPx = point.pt.x,
            yPx = point.pt.y,
            sizePx = point.size.toDouble(),
            angleDegrees = point.angle.toDouble(),
            response = point.response.toDouble(),
            encodedOctave = decodeOctave(point.octave),
            sourceIndex = sourceIndex,
        )

    private fun SiftCandidate.toDomain(width: Int, height: Int): SiftRootSiftKeypoint {
        val xMicro = quantizeUnit(xPx / width.toDouble(), "x")
        val yMicro = quantizeUnit(yPx / height.toDouble(), "y")
        val scaleMicro = quantizeClampedUnit(sizePx / width.toDouble(), "scale")
        val angle = Math.rint(angleDegrees * CENTIDEGREES_PER_DEGREE).toInt().coerceIn(0, MAX_ANGLE_CENTI_DEGREES)
        val responseScaled = response * RESPONSE_SCALE
        require(responseScaled.isFinite()) { "SIFT response metadata overflow" }
        val responseQuantized = Math.rint(responseScaled)
            .toLong()
            .coerceIn(0L, SiftRootSiftFingerprintCodec.MAX_RESPONSE_QUANTIZED.toLong())
            .toInt()
        require(encodedOctave in MIN_OCTAVE..MAX_OCTAVE) { "SIFT octave out of bounds" }
        return SiftRootSiftKeypoint(
            xMicro = xMicro,
            yMicro = yMicro,
            scaleMicro = scaleMicro,
            angleCentiDegrees = angle,
            responseQuantized = responseQuantized,
            octave = encodedOctave,
        )
    }

    private fun readRawDescriptor(
        descriptors: Mat,
        row: Int,
        reusableRawRow: FloatArray,
    ): ByteArray {
        val values = DoubleArray(DESCRIPTOR_BYTES)
        try {
            descriptors.get(row, 0, reusableRawRow)
            reusableRawRow.forEachIndexed { index, value ->
                require(value.isFinite()) { "SIFT descriptor value must be finite" }
                values[index] = value.toDouble()
            }
            return SiftRootSiftFingerprintCodec.quantizeRawSift(values)
        } finally {
            values.fill(0.0)
            reusableRawRow.fill(0f)
        }
    }

    private fun quantizeUnit(value: Double, label: String): Int {
        require(value.isFinite() && value in 0.0..1.0) {
            "SIFT $label metadata is out of bounds"
        }
        return Math.rint(value * SiftRootSiftFingerprintCodec.MICRO_UNITS)
            .toInt()
            .coerceIn(0, SiftRootSiftFingerprintCodec.MICRO_UNITS)
    }

    private fun quantizeClampedUnit(value: Double, label: String): Int {
        require(value.isFinite() && value >= 0.0) {
            "SIFT $label metadata is invalid"
        }
        if (value >= 1.0) return SiftRootSiftFingerprintCodec.MICRO_UNITS
        return Math.rint(value * SiftRootSiftFingerprintCodec.MICRO_UNITS)
            .toInt()
            .coerceIn(0, SiftRootSiftFingerprintCodec.MICRO_UNITS)
    }

    private fun checkedPixelCount(width: Int, height: Int): Long {
        require(width in 1..MAX_CANONICAL_DIMENSION_PX) { "frame width out of bounds" }
        require(height in 1..MAX_CANONICAL_DIMENSION_PX) { "frame height out of bounds" }
        val pixels = try {
            Math.multiplyExact(width.toLong(), height.toLong())
        } catch (overflow: ArithmeticException) {
            throw IllegalArgumentException("frame pixel count overflow", overflow)
        }
        require(pixels <= MAX_CANONICAL_WARP_PIXELS) {
            "frame exceeds canonical warp pixel budget"
        }
        return pixels
    }

    internal fun domainKeypointForTesting(
        candidate: SiftCandidate,
        width: Int,
        height: Int,
    ): SiftRootSiftKeypoint = candidate.toDomain(width, height)

    internal fun hasMatcherUsableDescriptorRows(rows: List<ByteArray>): Boolean =
        rows.any { row -> row.any { it.toInt() != 0 } }

    internal fun cleanupForTesting(
        primaryFailure: Throwable?,
        actions: List<() -> Unit>,
    ) {
        finishCleanup(primaryFailure, actions)
    }

    /** Exercises the same pre-transfer cleanup boundary without native setup. */
    internal fun cleanupBeforeTransferForTesting(
        outputDescriptors: List<ByteArray>,
        actions: List<() -> Unit>,
    ) {
        cleanupBeforeTransfer(outputDescriptors, actions)
    }

    private fun requireOpenCv() {
        try {
            if (Core.getVersionMajor() <= 0) throw OpenCvUnavailableException()
        } catch (failure: OpenCvUnavailableException) {
            throw failure
        } catch (failure: UnsatisfiedLinkError) {
            throw OpenCvUnavailableException(failure)
        }
    }

    private fun finishCleanup(
        primaryFailure: Throwable?,
        actions: List<() -> Unit>,
    ) {
        var cleanupFailure: Throwable? = null
        actions.forEach { action ->
            try {
                action()
            } catch (failure: Throwable) {
                val previousFailure = cleanupFailure
                if (previousFailure == null) {
                    cleanupFailure = failure
                } else {
                    previousFailure.addSuppressed(failure)
                }
            }
        }
        if (primaryFailure != null) {
            cleanupFailure?.let(primaryFailure::addSuppressed)
        } else {
            cleanupFailure?.let { throw it }
        }
    }

    /**
     * Native cleanup is a prerequisite for transferring descriptor ownership to
     * a returned fingerprint. A cleanup failure therefore scrubs the still
     * caller-owned rows before propagating the failure.
     */
    private fun cleanupBeforeTransfer(
        outputDescriptors: List<ByteArray>,
        actions: List<() -> Unit>,
    ) {
        try {
            finishCleanup(primaryFailure = null, actions = actions)
        } catch (failure: Throwable) {
            outputDescriptors.forEach { it.fill(0) }
            throw failure
        }
    }

    private fun cell(normalized: Double, count: Int): Int =
        min(count - 1, floor(normalized * count).toInt())

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
            val ac = values.sliceArray(1 until values.size)
            val median = ac.sorted()[ac.size / 2].toDouble()
            var packed = 0L
            for (index in 1 until values.size) {
                packed = (packed shl 1) or if (values[index] > median) 1L else 0L
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
        try {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = argbPixels[y * width + x]
                    val red = (pixel shr 16) and 0xFF
                    val green = (pixel shr 8) and 0xFF
                    val blue = pixel and 0xFF
                    row[x] = ((299 * red + 587 * green + 114 * blue) / 1000)
                        .coerceIn(0, 255)
                        .toByte()
                }
                target.put(y, 0, row)
            }
        } finally {
            row.fill(0)
        }
    }

    private fun decodeOctave(encoded: Int): Int {
        val lowByte = encoded and 0xFF
        return if (lowByte >= 128) lowByte - 256 else lowByte
    }

    private companion object {
        const val MAX_KEYPOINTS = SiftRootSiftFingerprintCodec.MAX_KEYPOINTS
        const val MAX_DETECTED_KEYPOINTS = 100_000
        // Matches PerspectiveWarper's bounded canonical output budget.  The
        // profile's 1,600 px canonical long edge and the reference's 960 px
        // descriptor resize are not interchangeable image-allocation caps.
        const val MAX_CANONICAL_WARP_PIXELS = 16_000_000L
        const val DESCRIPTOR_BYTES = SiftRootSiftFingerprintCodec.DESCRIPTOR_BYTES
        const val MAX_CANONICAL_DIMENSION_PX = SiftRootSiftFingerprintCodec.MAX_CANONICAL_DIMENSION_PX
        const val CENTIDEGREES_PER_DEGREE = 100.0
        const val RESPONSE_SCALE = SiftRootSiftFingerprintCodec.MAX_RESPONSE_QUANTIZED.toDouble()
        const val MAX_ANGLE_CENTI_DEGREES = 35_999
        const val MIN_OCTAVE = -8
        const val MAX_OCTAVE = 8
        const val HASH_SIZE = 8

        val candidateComparator = Comparator<PreparedCandidate> { left, right ->
            compareDescending(left.candidate.response, right.candidate.response)
                .takeIf { it != 0 }
                ?: compareValues(left.cellY, right.cellY)
                    .takeIf { it != 0 }
                ?: compareValues(left.cellX, right.cellX)
                    .takeIf { it != 0 }
                ?: compareValues(left.candidate.xPx, right.candidate.xPx)
                    .takeIf { it != 0 }
                ?: compareValues(left.candidate.yPx, right.candidate.yPx)
                    .takeIf { it != 0 }
                ?: compareValues(left.candidate.sizePx, right.candidate.sizePx)
                    .takeIf { it != 0 }
                ?: compareValues(left.candidate.angleDegrees, right.candidate.angleDegrees)
                    .takeIf { it != 0 }
                ?: compareValues(left.candidate.encodedOctave, right.candidate.encodedOctave)
                    .takeIf { it != 0 }
                ?: compareValues(left.candidate.sourceIndex, right.candidate.sourceIndex)
        }

        private fun compareDescending(left: Double, right: Double): Int =
            compareValues(right, left)
    }
}
