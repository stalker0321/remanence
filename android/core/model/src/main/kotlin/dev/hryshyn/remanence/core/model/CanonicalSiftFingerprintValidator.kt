package dev.hryshyn.remanence.core.model

/**
 * Shared boundary check for every serialized FRONT SIFT fingerprint entering
 * durable storage or publication.
 *
 * Parsing performs the strict canonical-byte check. It only reads [bytes], so
 * callers retain the exact original array for hashing and encryption.
 */
object CanonicalSiftFingerprintValidator {
    fun requireCanonical(profileId: String, bytes: ByteArray) {
        require(profileId == SiftRootSiftFingerprintCodec.PROFILE_ID) {
            "unsupported fingerprint profile"
        }
        val parsed = SiftRootSiftFingerprintCodec.parse(bytes)
        try {
            require(parsed.profileId == profileId) {
                "fingerprint profile does not match bytes"
            }
        } finally {
            parsed.wipe()
        }
    }
}
