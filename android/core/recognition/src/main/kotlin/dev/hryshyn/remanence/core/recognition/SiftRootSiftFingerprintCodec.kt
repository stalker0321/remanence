package dev.hryshyn.remanence.core.recognition

import com.google.protobuf.ByteString
import dev.hryshyn.remanence.recognition.v2.SiftRootSiftFingerprint as FingerprintWire

/** Strict P0 codec for the independent SIFT/RootSIFT format-3 contract. */
object SiftRootSiftFingerprintCodec {
    const val FORMAT_VERSION: Int = 3
    const val PROFILE_ID: String = "postcard-sift-rootsift-v1"
    const val MAX_SERIALIZED_BYTES: Int = 1 shl 20
    const val MIN_KEYPOINTS: Int = 1
    const val MAX_KEYPOINTS: Int = 1_500
    const val DESCRIPTOR_BYTES: Int = 128
    const val MICRO_UNITS: Int = 1_000_000
    const val MAX_CANONICAL_DIMENSION_PX: Int = 100_000
    const val MAX_SCALE_MICRO: Int = MICRO_UNITS
    const val MAX_RESPONSE_QUANTIZED: Int = MICRO_UNITS
    internal const val MAX_ANGLE_CENTI_DEGREES: Int = 35_999
    internal const val MIN_OCTAVE: Int = -8
    internal const val MAX_OCTAVE: Int = 8

    fun serialize(fingerprint: SiftRootSiftFingerprint): ByteArray {
        validate(fingerprint)
        val builder = FingerprintWire.newBuilder()
            .setFormatVersion(FORMAT_VERSION)
            .setRecognitionProfileId(PROFILE_ID)
            .setCanonicalWidthPx(fingerprint.canonicalWidthPx)
            .setCanonicalHeightPx(fingerprint.canonicalHeightPx)
            .setCoarseHash64(fingerprint.coarseHash64)

        fingerprint.keypoints.forEach { keypoint ->
            builder.addKeypoints(
                FingerprintWire.Keypoint.newBuilder()
                    .setXMicro(keypoint.xMicro)
                    .setYMicro(keypoint.yMicro)
                    .setScaleMicro(keypoint.scaleMicro)
                    .setAngleCentiDegrees(keypoint.angleCentiDegrees)
                    .setResponseQuantized(keypoint.responseQuantized)
                    .setOctave(keypoint.octave)
                    .build(),
            )
        }
        val flattened = ByteArray(fingerprint.keypoints.size * DESCRIPTOR_BYTES)
        try {
            fingerprint.quantizedSiftDescriptors.forEachIndexed { index, row ->
                row.copyInto(flattened, index * DESCRIPTOR_BYTES)
            }
            builder.setQuantizedSiftDescriptors(ByteString.copyFrom(flattened))
            val encoded = builder.build().toByteArray()
            require(encoded.size <= MAX_SERIALIZED_BYTES) { "fingerprint exceeds serialized size cap" }
            return encoded
        } finally {
            flattened.fill(0)
        }
    }

    fun parse(bytes: ByteArray): SiftRootSiftFingerprint {
        require(bytes.isNotEmpty() && bytes.size <= MAX_SERIALIZED_BYTES) {
            "fingerprint serialized size is out of bounds"
        }
        val wire = try {
            FingerprintWire.parseFrom(bytes)
        } catch (_: com.google.protobuf.InvalidProtocolBufferException) {
            throw IllegalArgumentException("fingerprint payload is not decodable")
        }
        if (wire.formatVersion != FORMAT_VERSION) {
            throw IllegalArgumentException("unsupported fingerprint format version")
        }
        if (wire.recognitionProfileId != PROFILE_ID) {
            throw IllegalArgumentException("invalid fingerprint profile id")
        }
        if (wire.canonicalWidthPx !in 1..MAX_CANONICAL_DIMENSION_PX ||
            wire.canonicalHeightPx !in 1..MAX_CANONICAL_DIMENSION_PX
        ) {
            throw IllegalArgumentException("canonical dimensions out of range")
        }
        if (wire.keypointsCount !in MIN_KEYPOINTS..MAX_KEYPOINTS) {
            throw IllegalArgumentException("keypoint count out of bounds")
        }
        val expectedDescriptorBytes = wire.keypointsCount * DESCRIPTOR_BYTES
        if (wire.quantizedSiftDescriptors.size() != expectedDescriptorBytes) {
            throw IllegalArgumentException("descriptor length does not align with keypoints")
        }

        val keypoints = wire.keypointsList.map(::keypointFromWire)
        val rawDescriptors = wire.quantizedSiftDescriptors.toByteArray()
        val descriptors = try {
            List(wire.keypointsCount) { row ->
                rawDescriptors.copyOfRange(row * DESCRIPTOR_BYTES, (row + 1) * DESCRIPTOR_BYTES)
            }
        } finally {
            rawDescriptors.fill(0)
        }

        var returned = false
        try {
            val parsed = SiftRootSiftFingerprint(
                profileId = wire.recognitionProfileId,
                canonicalWidthPx = wire.canonicalWidthPx,
                canonicalHeightPx = wire.canonicalHeightPx,
                coarseHash64 = wire.coarseHash64,
                keypoints = keypoints,
                quantizedSiftDescriptors = descriptors,
            )
            // Rebuild from the domain value. This drops unknown fields and
            // alternate protobuf encodings; exact byte equality is required.
            val canonical = serialize(parsed)
            try {
                require(canonical.contentEquals(bytes)) { "fingerprint encoding is not canonical" }
            } finally {
                canonical.fill(0)
            }
            returned = true
            return parsed
        } finally {
            if (!returned) descriptors.forEach { it.fill(0) }
        }
    }

    /**
     * Quantizes finite raw OpenCV SIFT detector values into raw bytes. Values
     * are rounded with ties-to-even and then clipped to [0, 255]. RootSIFT
     * formation is a P2 matching operation and must not be applied here.
     */
    fun quantizeRawSift(values: DoubleArray): ByteArray {
        require(values.size == DESCRIPTOR_BYTES) { "raw SIFT row must contain 128 values" }
        return ByteArray(DESCRIPTOR_BYTES) { index ->
            val value = values[index]
            require(value.isFinite()) { "raw SIFT value must be finite" }
            Math.rint(value).toInt().coerceIn(0, 255).toByte()
        }
    }

    private fun keypointFromWire(keypoint: FingerprintWire.Keypoint): SiftRootSiftKeypoint {
        val parsed = SiftRootSiftKeypoint(
            xMicro = keypoint.xMicro,
            yMicro = keypoint.yMicro,
            scaleMicro = keypoint.scaleMicro,
            angleCentiDegrees = keypoint.angleCentiDegrees,
            responseQuantized = keypoint.responseQuantized,
            octave = keypoint.octave,
        )
        validate(parsed)
        return parsed
    }

    private fun validate(fingerprint: SiftRootSiftFingerprint) {
        require(fingerprint.profileId == PROFILE_ID) { "invalid fingerprint profile id" }
        require(fingerprint.canonicalWidthPx in 1..MAX_CANONICAL_DIMENSION_PX) {
            "canonical width out of range"
        }
        require(fingerprint.canonicalHeightPx in 1..MAX_CANONICAL_DIMENSION_PX) {
            "canonical height out of range"
        }
        require(fingerprint.keypoints.size in MIN_KEYPOINTS..MAX_KEYPOINTS) {
            "keypoint count out of bounds"
        }
        require(fingerprint.quantizedSiftDescriptors.size == fingerprint.keypoints.size) {
            "descriptor rows do not align with keypoints"
        }
        fingerprint.keypoints.forEach(::validate)
        fingerprint.quantizedSiftDescriptors.forEach { row ->
            require(row.size == DESCRIPTOR_BYTES) { "descriptor row must contain 128 bytes" }
        }
    }

    private fun validate(keypoint: SiftRootSiftKeypoint) {
        require(keypoint.xMicro in 0..MICRO_UNITS) { "x coordinate out of range" }
        require(keypoint.yMicro in 0..MICRO_UNITS) { "y coordinate out of range" }
        require(keypoint.scaleMicro in 0..MAX_SCALE_MICRO) { "scale out of range" }
        require(keypoint.angleCentiDegrees in 0..MAX_ANGLE_CENTI_DEGREES) {
            "angle out of range"
        }
        require(keypoint.responseQuantized in 0..MAX_RESPONSE_QUANTIZED) {
            "response out of range"
        }
        require(keypoint.octave in MIN_OCTAVE..MAX_OCTAVE) { "octave out of range" }
    }
}
