package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.core.crypto.PendingArtifactCryptor
import dev.hryshyn.remanence.core.crypto.PendingPublishVerifier
import dev.hryshyn.remanence.core.data.network.PendingBlobBytesResult
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/**
 * T186 M14c: admitted-gift photo opening for the scan/display path.
 *
 * Opens one durable admitted row with caller-supplied live keys:
 * re-verifies the cached sender signature over the cached S (defense
 * in depth — admission already verified once), re-opens the sealed
 * envelope bytes via the provided opener, downloads every PHOTO
 * binding declared in S (size + SHA-256 checked against the
 * declaration before decrypt), and decrypts each under the
 * delivery-target AAD. Manifest/note decoding is the next slice;
 * photos render the gift. No secrets are persisted here — the
 * caller-owned key copy is zeroized in `finally`.
 *
 * Runs under the scan grant lifecycle (the grant is the fence);
 * additionally requires the row owner to match the caller.
 */
class PendingAdmittedContent(
    private val blobPort: BlobPort,
    private val senderVerifyPort: SenderVerifyPort,
) {

    fun interface BlobPort {
        suspend fun download(
            deliveryId: String,
            blobId: String,
            token: String,
        ): PendingBlobBytesResult
    }

    /** Directory sender public keyset bytes for one bundle id. */
    fun interface SenderVerifyPort {
        suspend fun senderPublicKeyset(senderKeyBundleId: String): ByteArray?
    }

    /** Capsule key bytes opened by the caller (R12 + live keys). */
    fun interface EnvelopeOpener {
        suspend fun openCapsuleKey(): ByteArray?
    }

    data class OpenedPhotos(
        val photosByOrdinal: Map<Int, ByteArray>,
    )

    suspend fun openPhotos(
        row: PendingAdmittedGiftStore.AdmittedGift,
        ownerUserId: String,
        token: String,
        envelopeOpener: EnvelopeOpener,
    ): OpenedPhotos {
        require(row.ownerUserId.isNotBlank() && row.ownerUserId == ownerUserId) {
            "pending admitted row owner mismatch"
        }
        require(token.isNotBlank()) { "pending open bearer required" }
        val statementBytes = try {
            Base64.getDecoder().decode(row.statementB64)
        } catch (_: Exception) {
            throw IllegalStateException("pending cached statement malformed")
        }
        val statement = try {
            PendingPublishVerifier.verifyStructure(statementBytes)
        } catch (_: Exception) {
            throw IllegalStateException("pending cached statement rejected")
        }
        val senderBundleId = uuidString(
            statement.senderKeyBundleId.toByteArray(),
            "pending sender bundle id malformed",
        )
        val senderKeyBytes = try {
            senderVerifyPort.senderPublicKeyset(senderBundleId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: throw IllegalStateException("pending sender key unavailable")
        val senderSig = try {
            Base64.getDecoder().decode(row.publishSignature69B64)
        } catch (_: Exception) {
            throw IllegalStateException("pending cached signature malformed")
        }
        try {
            PendingPublishVerifier.verifyPublish(senderKeyBytes, statementBytes, senderSig)
        } catch (_: Exception) {
            throw IllegalStateException("pending cached statement signature rejected")
        }
        // Row/S agreement: the cached bytes must describe this row.
        if (uuidString(statement.capsuleId.toByteArray(), "pending capsule id malformed") != row.capsuleId ||
            uuidString(statement.deliveryTargetId.toByteArray(), "pending target id malformed") != row.deliveryTargetId
        ) {
            throw IllegalStateException("pending row binding mismatch")
        }
        val capsuleKey = try {
            envelopeOpener.openCapsuleKey()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (capsuleKey == null || capsuleKey.isEmpty()) {
            throw IllegalStateException("pending capsule key unavailable")
        }
        try {
            val photos = LinkedHashMap<Int, ByteArray>()
            for (binding in statement.artifactsList) {
                if (binding.kind != 3) continue
                val blobId = uuidString(binding.blobId.toByteArray(), "pending blob id malformed")
                val ciphertext = when (
                    val downloaded = try {
                        blobPort.download(row.deliveryId, blobId, token)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        throw IllegalStateException("pending blob download failed")
                    }
                ) {
                    is PendingBlobBytesResult.Bytes -> downloaded.bytes
                    is PendingBlobBytesResult.Failure ->
                        throw IllegalStateException("pending blob download failed")
                }
                if (ciphertext.size.toLong() != binding.ciphertextSize) {
                    throw IllegalStateException("pending blob size mismatch")
                }
                val actualSha = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(ciphertext).joinToString("") { "%02x".format(it) }
                val expectedSha = binding.ciphertextSha256.toByteArray()
                    .joinToString("") { "%02x".format(it) }
                if (!actualSha.equals(expectedSha, ignoreCase = false)) {
                    throw IllegalStateException("pending blob hash mismatch")
                }
                val senderId = uuidString(
                    statement.senderUserId.toByteArray(), "pending sender id malformed",
                )
                val targetId = uuidString(
                    statement.deliveryTargetId.toByteArray(), "pending target id malformed",
                )
                val opened = try {
                    PendingArtifactCryptor.decrypt(
                        capsuleKeysetFrom(capsuleKey), row.capsuleId, blobId,
                        binding.kind, binding.ordinal, senderId, targetId, ciphertext,
                    )
                } catch (_: Exception) {
                    throw IllegalStateException("pending photo open failed")
                }
                if (photos.put(binding.ordinal, opened) != null) {
                    throw IllegalStateException("pending photo ordinal duplicated")
                }
            }
            if (photos.isEmpty()) throw IllegalStateException("pending gift has no photos")
            return OpenedPhotos(photos.toMap())
        } finally {
            capsuleKey.fill(0)
        }
    }

    private fun uuidString(raw16: ByteArray, what: String): String {
        require(raw16.size == 16) { what }
        val buffer = java.nio.ByteBuffer.wrap(raw16)
        return java.util.UUID(buffer.long, buffer.long).toString()
    }

    private fun capsuleKeysetFrom(keysetBytes: ByteArray): com.google.crypto.tink.KeysetHandle {
        // Exact-K shape is enforced by admission; re-parse defensively.
        return try {
            com.google.crypto.tink.TinkProtoKeysetFormat.parseKeysetWithoutSecret(keysetBytes)
        } catch (_: Exception) {
            // Private handle bytes (exact K as sealed) parse with secret.
            try {
                com.google.crypto.tink.TinkProtoKeysetFormat.parseKeyset(
                    keysetBytes,
                    com.google.crypto.tink.InsecureSecretKeyAccess.get(),
                )
            } catch (_: Exception) {
                throw IllegalStateException("pending capsule key malformed")
            }
        }
    }
}
