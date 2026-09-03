package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.protocol.v1.ChooserHint
import dev.hryshyn.remanence.protocol.v1.RecognitionManifest
import com.google.crypto.tink.KeysetHandle
import java.security.GeneralSecurityException
import java.security.MessageDigest
import dev.hryshyn.remanence.core.model.ArtifactAadInput
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CryptoContextEncoder
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.ProtocolV1Limits

internal class RecognitionManifestPayloadException : GeneralSecurityException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

/** Locally decrypted view of one recognition manifest. */
data class RecognitionManifestContent(
    val protocolVersion: Int,
    val capsuleIdRaw: ByteArray,
    val senderHandleSnapshot: String,
    val createdAtEpochSeconds: Long,
    val placeLabel: String?,
    val frontFingerprint: ByteArray,
)

/**
 * Builds and encrypts the recognition manifest from staged sender
 * fingerprints plus the minimal chooser hint (docs/security.md section 6.2):
 * no note text or photo bytes ever enter this artifact.
 */
class RecognitionManifestCodec {

    fun buildAndEncrypt(
        capsuleKeyset: KeysetHandle,
        routingContext: RoutingContext,
        senderHandleSnapshot: String,
        createdAtEpochSeconds: Long,
        placeLabel: String?,
        frontFingerprint: ByteArray,
    ): ByteArray {
        require(NormalizedHandle.parse(senderHandleSnapshot).value == senderHandleSnapshot) {
            "invalid sender handle snapshot"
        }
        placeLabel?.let {
            require(it.isNotEmpty()) { "place label is empty" }
            require(it.toByteArray(Charsets.UTF_8).size <= PLACE_LABEL_MAX_BYTES) { "place label too long" }
        }
        require(createdAtEpochSeconds >= 0L) { "created timestamp is invalid" }
        require(frontFingerprint.isNotEmpty()) { "front fingerprint is required" }
        require(frontFingerprint.size <= MAX_FINGERPRINT_BYTES) { "front fingerprint is too large" }

        val hint = ChooserHint.newBuilder()
            .setSenderHandleSnapshot(senderHandleSnapshot)
            .setCreatedAtEpochSeconds(createdAtEpochSeconds)
            .apply { placeLabel?.let(::setPlaceLabel) }
            .build()
        val manifest = RecognitionManifest.newBuilder()
            .setProtocolVersion(RecognitionManifestFormat.VERSION)
            .setCapsuleId(routingContext.capsuleIdProto())
            .setChooserHint(hint)
            .setFrontFingerprint(com.google.protobuf.ByteString.copyFrom(frontFingerprint))
            .build()
        val plaintext = manifest.toByteArray()
        require(plaintext.size + ProtocolV1Limits.ARTIFACT_AEAD_OVERHEAD_BYTES <=
            ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES
        ) { "recognition manifest is too large" }
        return try {
            CapsuleArtifactCryptor().encrypt(
                capsuleKeyset = capsuleKeyset,
                context = manifestContext(routingContext),
                plaintext = plaintext,
            )
        } finally {
            plaintext.fill(0)
        }
    }

    fun decryptAndParse(
        capsuleKeyset: KeysetHandle,
        routingContext: RoutingContext,
        ciphertext: ByteArray,
    ): RecognitionManifestContent = try {
        val bytes = try {
            require(ciphertext.isNotEmpty() &&
                ciphertext.size <= ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES
            ) { "recognition manifest ciphertext is outside bounds" }
            CapsuleArtifactCryptor().decrypt(capsuleKeyset, manifestContext(routingContext), ciphertext)
        } catch (failure: GeneralSecurityException) {
            throw GeneralSecurityException("recognition manifest failed integrity verification", failure)
        }
        try {
            if (bytes.isEmpty() || bytes.size + ProtocolV1Limits.ARTIFACT_AEAD_OVERHEAD_BYTES >
                ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES
            ) {
                throw RecognitionManifestPayloadException("recognition manifest plaintext is outside bounds")
            }
            val manifest = RecognitionManifest.parseFrom(bytes)
            if (manifest.protocolVersion != RecognitionManifestFormat.VERSION) {
                throw RecognitionManifestPayloadException("unsupported manifest format version")
            }

            val expectedCapsuleId = routingContext.capsuleId.toProtoBytes().toByteArray()
            val actualCapsuleId = manifest.capsuleId.toByteArray()
            if (actualCapsuleId.size != expectedCapsuleId.size ||
                !MessageDigest.isEqual(actualCapsuleId, expectedCapsuleId)
            ) {
                throw RecognitionManifestPayloadException("recognition manifest capsule id does not match routing")
            }

            if (!manifest.hasChooserHint()) throw RecognitionManifestPayloadException("manifest missing chooser hint")
            val chooserHint = manifest.chooserHint
            val senderHandleSnapshot = chooserHint.senderHandleSnapshot
            if (NormalizedHandle.parse(senderHandleSnapshot).value != senderHandleSnapshot) {
                throw RecognitionManifestPayloadException("recognition manifest sender handle is not canonical")
            }
            if (chooserHint.createdAtEpochSeconds < 0L) {
                throw RecognitionManifestPayloadException("recognition manifest timestamp is invalid")
            }
            val placeLabel = if (chooserHint.hasPlaceLabel()) chooserHint.placeLabel else null
            if (placeLabel != null) {
                if (placeLabel.isEmpty()) {
                    throw RecognitionManifestPayloadException("recognition manifest place label is empty")
                }
                if (placeLabel.toByteArray(Charsets.UTF_8).size > PLACE_LABEL_MAX_BYTES) {
                    throw RecognitionManifestPayloadException("recognition manifest place label exceeds byte limit")
                }
            }

            val frontFingerprint = manifest.frontFingerprint.toByteArray()
            if (frontFingerprint.isEmpty() || frontFingerprint.size > MAX_FINGERPRINT_BYTES) {
                throw RecognitionManifestPayloadException("recognition manifest front fingerprint is invalid")
            }
            val canonical = RecognitionManifest.newBuilder()
                .setProtocolVersion(RecognitionManifestFormat.VERSION)
                .setCapsuleId(manifest.capsuleId)
                .setChooserHint(manifest.chooserHint)
                .setFrontFingerprint(manifest.frontFingerprint)
                .build()
            if (!canonical.toByteArray().contentEquals(bytes)) {
                throw RecognitionManifestPayloadException("recognition manifest encoding is not canonical")
            }

            return RecognitionManifestContent(
                protocolVersion = manifest.protocolVersion,
                capsuleIdRaw = actualCapsuleId,
                senderHandleSnapshot = senderHandleSnapshot,
                createdAtEpochSeconds = chooserHint.createdAtEpochSeconds,
                placeLabel = placeLabel,
                frontFingerprint = frontFingerprint,
            )
        } finally {
            bytes.fill(0)
        }
    } catch (failure: RecognitionManifestPayloadException) {
        throw failure
    } catch (failure: GeneralSecurityException) {
        throw failure
    } catch (failure: Exception) {
        throw RecognitionManifestPayloadException("recognition manifest failed structural validation", failure)
    }

    private fun manifestContext(routing: RoutingContext): ArtifactAadInput =
        ArtifactAadInput(
            capsuleId = routing.capsuleId,
            blobId = routing.blobId,
            artifactKind = CapsuleArtifactKind.RECOGNITION_MANIFEST,
            ordinal = RecognitionManifestFormat.NON_PHOTO_ORDINAL,
            senderUserId = routing.senderUserId,
            recipientUserId = routing.recipientUserId,
        )

    /** Typed routing identity shared by every artifact of one capsule draft. */
    data class RoutingContext(
        val capsuleId: dev.hryshyn.remanence.core.model.CapsuleId,
        val blobId: dev.hryshyn.remanence.core.model.BlobId,
        val senderUserId: dev.hryshyn.remanence.core.model.UserId,
        val recipientUserId: dev.hryshyn.remanence.core.model.UserId,
    ) {
        internal fun capsuleIdProto() = capsuleId.toProtoBytes()
    }

    private object RecognitionManifestFormat {
        const val VERSION = 2
        const val NON_PHOTO_ORDINAL = -1
    }

    companion object {
        const val FORMAT_VERSION = RecognitionManifestFormat.VERSION
        const val PLACE_LABEL_MAX_BYTES = 120
        const val MAX_FINGERPRINT_BYTES = 1_024 * 1_024
    }
}
