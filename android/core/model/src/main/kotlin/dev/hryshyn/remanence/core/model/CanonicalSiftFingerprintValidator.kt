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
        require(profileId in FrontFingerprintCodecs.SUPPORTED_PROFILES) {
            "unsupported fingerprint profile"
        }
        val parsed = FrontFingerprintCodecs.parse(bytes)
        try {
            require(parsed.profileId == profileId) {
                "fingerprint profile does not match bytes"
            }
        } finally {
            parsed.wipe()
        }
    }
}
