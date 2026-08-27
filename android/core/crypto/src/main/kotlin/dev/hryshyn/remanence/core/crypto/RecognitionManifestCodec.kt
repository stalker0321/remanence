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
    val backFingerprint: ByteArray,
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
        backFingerprint: ByteArray,
    ): ByteArray {
        require(NormalizedHandle.parse(senderHandleSnapshot).value == senderHandleSnapshot) {
            "invalid sender handle snapshot"
        }
        placeLabel?.let {
            require(it.toByteArray(Charsets.UTF_8).size <= PLACE_LABEL_MAX_BYTES) { "place label too long" }
        }
        require(frontFingerprint.isNotEmpty() && backFingerprint.isNotEmpty()) { "both side fingerprints required" }

        val hint = ChooserHint.newBuilder()
            .setSenderHandleSnapshot(senderHandleSnapshot)
            .setCreatedAtEpochSeconds(createdAtEpochSeconds)
            .apply { placeLabel?.let(::setPlaceLabel) }
            .build()
        val manifest = RecognitionManifest.newBuilder()
            .setProtocolVersion(ProtocolV1.PROTOCOL_VERSION)
            .setCapsuleId(routingContext.capsuleIdProto())
            .setChooserHint(hint)
            .setFrontFingerprint(com.google.protobuf.ByteString.copyFrom(frontFingerprint))
            .setBackFingerprint(com.google.protobuf.ByteString.copyFrom(backFingerprint))
            .build()

        return CapsuleArtifactCryptor().encrypt(
            capsuleKeyset = capsuleKeyset,
            context = manifestContext(routingContext),
            plaintext = manifest.toByteArray(),
        )
    }

    fun decryptAndParse(
        capsuleKeyset: KeysetHandle,
        routingContext: RoutingContext,
        ciphertext: ByteArray,
    ): RecognitionManifestContent = try {
        val bytes = try {
            CapsuleArtifactCryptor().decrypt(capsuleKeyset, manifestContext(routingContext), ciphertext)
        } catch (failure: GeneralSecurityException) {
            throw GeneralSecurityException("recognition manifest failed integrity verification", failure)
        }
        val manifest = RecognitionManifest.parseFrom(bytes)
        if (manifest.protocolVersion != ProtocolV1.PROTOCOL_VERSION) {
            throw RecognitionManifestPayloadException("unsupported manifest protocol version")
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
        val placeLabel = if (chooserHint.hasPlaceLabel()) chooserHint.placeLabel else null
        if (placeLabel != null && placeLabel.toByteArray(Charsets.UTF_8).size > PLACE_LABEL_MAX_BYTES) {
            throw RecognitionManifestPayloadException("recognition manifest place label exceeds byte limit")
        }

        val frontFingerprint = manifest.frontFingerprint.toByteArray()
        val backFingerprint = manifest.backFingerprint.toByteArray()
        if (frontFingerprint.isEmpty() || backFingerprint.isEmpty()) {
            throw RecognitionManifestPayloadException("recognition manifest fingerprints are incomplete")
        }

        RecognitionManifestContent(
            protocolVersion = manifest.protocolVersion,
            capsuleIdRaw = actualCapsuleId,
            senderHandleSnapshot = senderHandleSnapshot,
            createdAtEpochSeconds = chooserHint.createdAtEpochSeconds,
            placeLabel = placeLabel,
            frontFingerprint = frontFingerprint,
            backFingerprint = backFingerprint,
        )
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
            ordinal = ProtocolV1.NON_PHOTO_ORDINAL,
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

    private object ProtocolV1 {
        const val PROTOCOL_VERSION = 1
        const val NON_PHOTO_ORDINAL = -1
    }

    private companion object {
        const val PLACE_LABEL_MAX_BYTES = 120
    }
}
