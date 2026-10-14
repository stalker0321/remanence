package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.protocol.v1.RecognitionManifest
import java.security.GeneralSecurityException
import java.security.MessageDigest

/**
 * T186 M14d-scan: FRONT fingerprint decode for the v2 recognition
 * manifest (kind 1, ord -1). The caller decrypts under the
 * delivery-target AAD first ([PendingArtifactCryptor]); this unit
 * validates the framed plaintext with the same bounds as the v1
 * codec (frozen version, capsule match, non-empty size-capped
 * fingerprint) and returns a caller-owned copy. v1 bytes and limits
 * are referenced, never redefined.
 */
object PendingRecognitionDecode {

    fun frontFingerprint(
        plaintext: ByteArray,
        expectedCapsuleId: ByteArray,
    ): ByteArray {
        val manifest = try {
            RecognitionManifest.parseFrom(plaintext)
        } catch (_: Exception) {
            throw GeneralSecurityException("pending recognition manifest malformed")
        }
        if (manifest.manifestVersion != RecognitionManifestCodec.FORMAT_VERSION) {
            throw GeneralSecurityException("pending recognition manifest version unsupported")
        }
        if (manifest.capsuleId.size() != expectedCapsuleId.size ||
            !MessageDigest.isEqual(manifest.capsuleId.toByteArray(), expectedCapsuleId)
        ) {
            throw GeneralSecurityException("pending recognition manifest capsule id mismatch")
        }
        val front = manifest.frontFingerprint.toByteArray()
        if (front.isEmpty() || front.size > RecognitionManifestCodec.MAX_FINGERPRINT_BYTES) {
            throw GeneralSecurityException("pending recognition fingerprint out of bounds")
        }
        return front.copyOf()
    }
}
