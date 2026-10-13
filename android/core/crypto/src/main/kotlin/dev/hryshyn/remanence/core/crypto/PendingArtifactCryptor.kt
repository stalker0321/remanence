package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle

/**
 * T186 M11: v2 artifact AEAD with the delivery-target-bound AAD
 * (`PendingPublishCrypto.artifactAadInput`). The binding carries
 * (`sender_user_id`, `delivery_target_id`) — never the final recipient
 * user, so carried-R trust and `UserId(target)` fakery cannot be
 * expressed: the only target input is the frozen pending target string.
 *
 * Inner payloads are unchanged: callers supply already-framed plaintext
 * (manifest/photo bytes) and exact ciphertext flows to the R4 blob
 * transport with the declared size/SHA-256.
 */
object PendingArtifactCryptor {

    fun encrypt(
        capsuleKeyset: KeysetHandle,
        capsuleId: String,
        blobId: String,
        artifactKind: Int,
        ordinal: Int,
        senderUserId: String,
        deliveryTargetId: String,
        plaintext: ByteArray,
    ): ByteArray {
        val aad = PendingPublishCrypto.artifactAadInput(
            capsuleId, blobId, artifactKind, ordinal, senderUserId, deliveryTargetId,
        )
        return capsuleKeyset.getPrimitive(Aead::class.java).encrypt(plaintext, aad)
    }

    fun decrypt(
        capsuleKeyset: KeysetHandle,
        capsuleId: String,
        blobId: String,
        artifactKind: Int,
        ordinal: Int,
        senderUserId: String,
        deliveryTargetId: String,
        ciphertext: ByteArray,
    ): ByteArray {
        val aad = PendingPublishCrypto.artifactAadInput(
            capsuleId, blobId, artifactKind, ordinal, senderUserId, deliveryTargetId,
        )
        return capsuleKeyset.getPrimitive(Aead::class.java).decrypt(ciphertext, aad)
    }
}
