package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.protocol.v1.ChooserHint
import dev.hryshyn.remanence.protocol.v1.RecognitionManifest
import java.security.GeneralSecurityException
import java.security.MessageDigest

/**
 * T186 M14d-scan: FRONT fingerprint decode for the v2 recognition
 * manifest (kind 1, ord -1). The caller decrypts under the
 * delivery-target AAD first ([PendingArtifactCryptor]); this unit
 * applies the EXACT v1 framed-plaintext checks (frozen version,
 * capsule match, required canonical chooser hint, non-negative
 * created instant, bounded place label, non-empty size-capped
 * fingerprint, canonical re-encode equality — same guards as
 * [RecognitionManifestCodec] plaintext validation, inlined so the v1
 * bytes stay untouched) and returns a caller-owned fingerprint copy.
 * The input plaintext is caller-owned (never wiped here).
 */
object PendingRecognitionDecode {

    fun frontFingerprint(
        plaintext: ByteArray,
        expectedCapsuleId: ByteArray,
    ): ByteArray {
        if (plaintext.isEmpty() || plaintext.size +
            dev.hryshyn.remanence.core.model.ProtocolV1Limits.ARTIFACT_AEAD_OVERHEAD_BYTES >
            dev.hryshyn.remanence.core.model.ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES
        ) {
            throw GeneralSecurityException("pending recognition manifest plaintext is outside bounds")
        }
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
        if (!manifest.hasChooserHint()) {
            throw GeneralSecurityException("pending recognition manifest missing chooser hint")
        }
        val chooserHint = manifest.chooserHint
        val senderHandleSnapshot = chooserHint.senderHandleSnapshot
        val handleCanonical = try {
            NormalizedHandle.parse(senderHandleSnapshot).value == senderHandleSnapshot
        } catch (_: Exception) {
            false
        }
        if (!handleCanonical) {
            throw GeneralSecurityException("pending recognition manifest sender handle is not canonical")
        }
        if (chooserHint.createdAtEpochSeconds < 0L) {
            throw GeneralSecurityException("pending recognition manifest timestamp is invalid")
        }
        val placeLabel = if (chooserHint.hasPlaceLabel()) chooserHint.placeLabel else null
        if (placeLabel != null) {
            if (placeLabel.isEmpty()) {
                throw GeneralSecurityException("pending recognition manifest place label is empty")
            }
            if (placeLabel.toByteArray(Charsets.UTF_8).size >
                RecognitionManifestCodec.PLACE_LABEL_MAX_BYTES
            ) {
                throw GeneralSecurityException("pending recognition manifest place label exceeds byte limit")
            }
        }
        // Sole caller-owned copy (v1 codec pattern): transferred on
        // success, wiped on any failure below — no intermediary
        // survives either path.
        var returnedFront = false
        var front: ByteArray? = null
        try {
            val decoded = manifest.frontFingerprint.toByteArray()
            front = decoded
            if (decoded.isEmpty() || decoded.size > RecognitionManifestCodec.MAX_FINGERPRINT_BYTES) {
                throw GeneralSecurityException("pending recognition fingerprint out of bounds")
            }
            // Canonical re-encode equality: the framed bytes must be
            // exactly what the canonical builder emits (no unknown
            // fields, no reordered/duplicate fields) — same guard as v1.
            val canonicalHint = ChooserHint.newBuilder()
                .setSenderHandleSnapshot(senderHandleSnapshot)
                .setCreatedAtEpochSeconds(chooserHint.createdAtEpochSeconds)
                .apply { placeLabel?.let(::setPlaceLabel) }
                .build()
            val canonical = RecognitionManifest.newBuilder()
                .setManifestVersion(RecognitionManifestCodec.FORMAT_VERSION)
                .setCapsuleId(manifest.capsuleId)
                .setChooserHint(canonicalHint)
                .setFrontFingerprint(manifest.frontFingerprint)
                .build()
            val canonicalBytes = canonical.toByteArray()
            try {
                if (!canonicalBytes.contentEquals(plaintext)) {
                    throw GeneralSecurityException("pending recognition manifest encoding is not canonical")
                }
            } finally {
                canonicalBytes.fill(0)
            }
            returnedFront = true
            return decoded
        } finally {
            if (!returnedFront) front?.fill(0)
        }
    }
}
