package dev.hryshyn.remanence.core.model

import dev.hryshyn.remanence.recognition.v2.SiftRootSiftFingerprint as FingerprintWire

/**
 * Dispatches a FRONT fingerprint to its profile's strict codec: the frozen
 * matcher S3 profile or the legacy `postcard-sift-rootsift-v1` profile.
 */
object FrontFingerprintCodecs {
    val SUPPORTED_PROFILES: Set<String> = setOf(
        SiftRootSiftFingerprintCodec.PROFILE_ID,
        FrozenMatcherS3FingerprintCodec.PROFILE_ID,
    )

    /** The profile id declared by serialized fingerprint bytes (not validated). */
    fun profileOf(bytes: ByteArray): String = try {
        FingerprintWire.parseFrom(bytes).recognitionProfileId
    } catch (_: com.google.protobuf.InvalidProtocolBufferException) {
        throw IllegalArgumentException("fingerprint payload is not decodable")
    }

    fun parse(bytes: ByteArray): SiftRootSiftFingerprint {
        return when (profileOf(bytes)) {
            FrozenMatcherS3FingerprintCodec.PROFILE_ID -> FrozenMatcherS3FingerprintCodec.parse(bytes)
            else -> SiftRootSiftFingerprintCodec.parse(bytes)
        }
    }

    fun serialize(fingerprint: SiftRootSiftFingerprint): ByteArray = when (fingerprint.profileId) {
        FrozenMatcherS3FingerprintCodec.PROFILE_ID -> FrozenMatcherS3FingerprintCodec.serialize(fingerprint)
        else -> SiftRootSiftFingerprintCodec.serialize(fingerprint)
    }
}
