package dev.hryshyn.remanence.core.recognition

import dev.hryshyn.remanence.recognition.v1.PostcardFingerprint as FingerprintWire

/**
 * Local side of a postcard fingerprint — FRONT-only production contract
 * (ADR-012). BACK is retained only as a legacy wire symbol that the parser
 * rejects fail-closed; it is not a production input (docs/recognition.md
 * section 6). The codec below enforces exactly one required FRONT record.
 */
enum class FingerprintSide { FRONT, BACK }

/** One ORB keypoint in normalized coordinates with quantized extras. */
data class FingerprintKeypoint(
    val xNormalized: Double,
    val yNormalized: Double,
    val scaleNormalized: Double,
    val angleCentiDegrees: Int,
    val responseQuantized: Int,
    val octave: Int,
)

data class ExtractionQuality(
    val blurScore: Double,
    val exposureScore: Double,
    val glareFraction: Double,
    val detectedAreaRatio: Double,
)

/**
 * Versioned local postcard fingerprint (docs/recognition.md section 6).
 * Contains no pixels; descriptors are aligned one-to-one with keypoints.
 */
class PostcardFingerprint(
    val profileId: String,
    val side: FingerprintSide,
    val canonicalWidthPx: Int,
    val canonicalHeightPx: Int,
    val coarseHash64: Long,
    val keypoints: List<FingerprintKeypoint>,
    val descriptors: List<ByteArray>,
    val quality: ExtractionQuality,
)

/**
 * Strict binary codec over the normative recognition protobuf schema
 * (`dev.hryshyn.remanence.recognition.v1`). Every count/length/range is bounded BEFORE
 * allocation or acceptance; anything unexpected fails closed.
 */
object FingerprintCodec {

    const val FORMAT_VERSION: Int = 1
    const val DESCRIPTOR_BYTES: Int = 32
    const val MAX_KEYPOINTS: Int = 1500
    const val MAX_CANONICAL_DIMENSION_PX: Int = 100_000
    const val MICRO_UNITS: Int = 1_000_000
    internal const val MAX_ANGLE_CENTI_DEGREES: Int = 35_999
    internal const val MAX_OCTAVE: Int = 8
    internal const val MIN_OCTAVE: Int = -8
    internal const val MAX_PROFILE_ID_CHARS: Int = 64

    private val PROFILE_ID = Regex("[A-Za-z0-9._\\-]+")

    fun serialize(fingerprint: PostcardFingerprint): ByteArray {
        validate(fingerprint)
        val wire = FingerprintWire.newBuilder()
            .setFormatVersion(FORMAT_VERSION)
            .setRecognitionProfileId(fingerprint.profileId)
            .setSide(toWireSide(fingerprint.side))
            .setCanonicalWidthPx(fingerprint.canonicalWidthPx)
            .setCanonicalHeightPx(fingerprint.canonicalHeightPx)
            .setCoarseHash64(fingerprint.coarseHash64)
            .setExtractionQuality(qualityToWire(fingerprint.quality))
        var descriptorsByteString = com.google.protobuf.ByteString.EMPTY
        fingerprint.keypoints.forEachIndexed { index, keypoint ->
            val descriptor = fingerprint.descriptors[index]
            wire.addKeypoints(
                FingerprintWire.Keypoint.newBuilder()
                    .setXMicro(toMicro(keypoint.xNormalized))
                    .setYMicro(toMicro(keypoint.yNormalized))
                    .setScaleMicro(toMicro(keypoint.scaleNormalized))
                    .setAngleCentiDegrees(keypoint.angleCentiDegrees)
                    .setResponseQuantized(keypoint.responseQuantized)
                    .setOctave(keypoint.octave)
                    .build(),
            )
            descriptorsByteString = descriptorsByteString.concat(com.google.protobuf.ByteString.copyFrom(descriptor))
        }
        wire.setOrbDescriptors(descriptorsByteString)
        return wire.build().toByteArray()
    }

    fun parse(bytes: ByteArray): PostcardFingerprint {
        val wire = try {
            FingerprintWire.parseFrom(bytes)
        } catch (_: com.google.protobuf.InvalidProtocolBufferException) {
            throw IllegalArgumentException("fingerprint payload is not decodable")
        }
        if (wire.formatVersion != FORMAT_VERSION) {
            throw IllegalArgumentException("unsupported fingerprint format version ${wire.formatVersion}")
        }
        val profileId = wire.recognitionProfileId
        if (profileId.isEmpty() || profileId.length > MAX_PROFILE_ID_CHARS || !PROFILE_ID.matches(profileId)) {
            throw IllegalArgumentException("invalid fingerprint profile id")
        }
        val side = when (wire.side) {
            FingerprintWire.Side.FRONT -> FingerprintSide.FRONT
            FingerprintWire.Side.BACK ->
                throw IllegalArgumentException("BACK fingerprint is not supported in FRONT-only contract")
            FingerprintWire.Side.SIDE_UNSPECIFIED, FingerprintWire.Side.UNRECOGNIZED ->
                throw IllegalArgumentException("fingerprint side is unspecified")
        }
        if (side != FingerprintSide.FRONT) {
            throw IllegalArgumentException("only FRONT fingerprint is supported")
        }
        if (wire.canonicalWidthPx !in 1..MAX_CANONICAL_DIMENSION_PX ||
            wire.canonicalHeightPx !in 1..MAX_CANONICAL_DIMENSION_PX
        ) {
            throw IllegalArgumentException("canonical dimensions out of range")
        }
        if (wire.keypointsCount !in 1..MAX_KEYPOINTS) {
            throw IllegalArgumentException("keypoint count out of bounds")
        }

        if (wire.orbDescriptors.size() != wire.keypointsCount * DESCRIPTOR_BYTES) {
            throw IllegalArgumentException("descriptor length does not align with keypoints")
        }
        val keypoints = wire.keypointsList.map { keypoint ->
            if (keypoint.angleCentiDegrees !in 0..MAX_ANGLE_CENTI_DEGREES) {
                throw IllegalArgumentException("keypoint angle out of range")
            }
            if (keypoint.octave !in MIN_OCTAVE..MAX_OCTAVE) {
                throw IllegalArgumentException("keypoint octave out of range")
            }
            FingerprintKeypoint(
                xNormalized = fromMicroChecked(keypoint.xMicro),
                yNormalized = fromMicroChecked(keypoint.yMicro),
                scaleNormalized = fromMicroUnchecked(keypoint.scaleMicro),
                angleCentiDegrees = keypoint.angleCentiDegrees,
                responseQuantized = keypoint.responseQuantized,
                octave = keypoint.octave,
            )
        }
        val rawDescriptors = wire.orbDescriptors.toByteArray()
        val descriptors = List(wire.keypointsCount) { row ->
            rawDescriptors.copyOfRange(row * DESCRIPTOR_BYTES, (row + 1) * DESCRIPTOR_BYTES)
        }
        val q = wire.extractionQuality
            ?: throw IllegalArgumentException("extraction quality missing")
        return PostcardFingerprint(
            profileId = profileId,
            side = side,
            canonicalWidthPx = wire.canonicalWidthPx,
            canonicalHeightPx = wire.canonicalHeightPx,
            coarseHash64 = wire.coarseHash64,
            keypoints = keypoints,
            descriptors = descriptors,
            quality = ExtractionQuality(
                blurScore = fromMicroUnchecked(q.blurScoreMicro),
                exposureScore = fromMicroUnchecked(q.exposureScoreMicro),
                glareFraction = fromMicroChecked(q.glareFractionMicro),
                detectedAreaRatio = fromMicroChecked(q.detectedAreaRatioMicro),
            ),
        )
    }

    private fun validate(fingerprint: PostcardFingerprint) {
        if (fingerprint.side != FingerprintSide.FRONT) {
            throw IllegalArgumentException("only FRONT fingerprint is supported in FRONT-only contract")
        }
        if (fingerprint.profileId.isEmpty() ||
            fingerprint.profileId.length > MAX_PROFILE_ID_CHARS ||
            !PROFILE_ID.matches(fingerprint.profileId)
        ) {
            throw IllegalArgumentException("invalid profile id")
        }
        if (fingerprint.canonicalWidthPx !in 1..MAX_CANONICAL_DIMENSION_PX ||
            fingerprint.canonicalHeightPx !in 1..MAX_CANONICAL_DIMENSION_PX
        ) {
            throw IllegalArgumentException("canonical dimensions out of range")
        }
        if (fingerprint.keypoints.isEmpty() || fingerprint.keypoints.size > MAX_KEYPOINTS) {
            throw IllegalArgumentException("keypoint count out of bounds")
        }
        if (fingerprint.descriptors.size != fingerprint.keypoints.size) {
            throw IllegalArgumentException("descriptors do not align with keypoints")
        }
        fingerprint.descriptors.forEach { if (it.size != DESCRIPTOR_BYTES) throw IllegalArgumentException("descriptor must be $DESCRIPTOR_BYTES bytes") }
        fingerprint.keypoints.forEach { kp ->
            require(kp.xNormalized in 0.0..1.0) { "x out of range" }
            require(kp.yNormalized in 0.0..1.0) { "y out of range" }
            require(kp.scaleNormalized >= 0.0) { "negative scale" }
            require(kp.angleCentiDegrees in 0..MAX_ANGLE_CENTI_DEGREES) { "angle out of range" }
            require(kp.octave in MIN_OCTAVE..MAX_OCTAVE) { "octave out of range" }
        }
        require(fingerprint.quality.glareFraction in 0.0..1.0) { "glare fraction out of range" }
        require(fingerprint.quality.detectedAreaRatio in 0.0..1.0) { "area ratio out of range" }
        require(fingerprint.quality.blurScore >= 0.0 && fingerprint.quality.exposureScore >= 0.0)
    }

    private fun qualityToWire(quality: ExtractionQuality): FingerprintWire.ExtractionQuality =
        FingerprintWire.ExtractionQuality.newBuilder()
            .setBlurScoreMicro(toMicro(quality.blurScore))
            .setExposureScoreMicro(toMicro(quality.exposureScore))
            .setGlareFractionMicro(toMicro(quality.glareFraction))
            .setDetectedAreaRatioMicro(toMicro(quality.detectedAreaRatio))
            .build()

    private fun toWireSide(side: FingerprintSide): FingerprintWire.Side = when (side) {
        FingerprintSide.FRONT -> FingerprintWire.Side.FRONT
        FingerprintSide.BACK ->
            throw IllegalArgumentException("BACK fingerprint cannot be serialized in FRONT-only contract")
    }

    private fun toMicro(value: Double): Int {
        require(value >= 0.0) { "negative value cannot be quantized" }
        val micro = Math.round(value * MICRO_UNITS)
        require(micro <= MICRO_UNITS.toLong() * Int.MAX_VALUE / MICRO_UNITS) { "value overflow" }
        require(micro <= Int.MAX_VALUE.toLong()) { "value overflow" }
        return micro.toInt()
    }

    private fun fromMicroChecked(micro: Int): Double {
        if (micro > MICRO_UNITS) throw IllegalArgumentException("normalized value exceeds 1.0")
        return micro / MICRO_UNITS.toDouble()
    }

    private fun fromMicroUnchecked(micro: Int): Double = micro / MICRO_UNITS.toDouble()

}
