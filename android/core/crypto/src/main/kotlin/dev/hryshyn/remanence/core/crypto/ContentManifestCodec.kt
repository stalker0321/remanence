package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.protocol.v1.ContentManifest
import dev.hryshyn.remanence.protocol.v1.PhotoEntry
import com.google.crypto.tink.KeysetHandle
import java.security.GeneralSecurityException
import java.util.UUID
import dev.hryshyn.remanence.core.model.ArtifactAadInput
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CryptoContextEncoder

/** One decrypted photo descriptor in manifest order (sorted ordinal). */
data class ManifestPhoto(
    val blobId: UUID,
    val ordinal: Int,
    val width: Int,
    val height: Int,
)

/** Locally decrypted view of the content manifest. */
data class ContentManifestContent(
    val protocolVersion: Int,
    val photos: List<ManifestPhoto>,
    val note: String?,
)

/**
 * Builds and encrypts the content manifest from photo descriptors and the
 * optional note (docs/security.md section 6.2). `TrackAttachment` is never
 * populated in MVP and a present one fails closed on decryption.
 */
class ContentManifestCodec {

    fun buildAndEncrypt(
        capsuleKeyset: KeysetHandle,
        routingContext: RecognitionManifestCodec.RoutingContext,
        photos: List<ManifestPhoto>,
        note: String?,
    ): ByteArray {
        validatePhotos(photos)
        validateNote(note)

        val builder = ContentManifest.newBuilder()
            .setProtocolVersion(PROTOCOL_VERSION)
            .setCapsuleId(routingContext.capsuleId.toProtoBytes())
        photos.sortedBy { it.ordinal }.forEach { photo ->
            builder.addPhotos(
                PhotoEntry.newBuilder()
                    .setBlobId(com.google.protobuf.ByteString.copyFrom(longToBytes(photo.blobId)))
                    .setOrdinal(photo.ordinal)
                    .setMediaType(MEDIA_TYPE_JPEG)
                    .setWidth(photo.width)
                    .setHeight(photo.height),
            )
        }
        note?.takeIf { it.isNotEmpty() }?.let(builder::setNote)
        // TrackAttachment is deliberately left unset in MVP.

        return CapsuleArtifactCryptor().encrypt(
            capsuleKeyset = capsuleKeyset,
            context = contentContext(routingContext),
            plaintext = builder.build().toByteArray(),
        )
    }

    fun decryptAndParse(
        capsuleKeyset: KeysetHandle,
        routingContext: RecognitionManifestCodec.RoutingContext,
        ciphertext: ByteArray,
    ): ContentManifestContent {
        val bytes = try {
            CapsuleArtifactCryptor().decrypt(capsuleKeyset, contentContext(routingContext), ciphertext)
        } catch (failure: GeneralSecurityException) {
            throw GeneralSecurityException("content manifest failed integrity verification")
        }
        val manifest = ContentManifest.parseFrom(bytes)
        if (manifest.protocolVersion != PROTOCOL_VERSION) {
            throw GeneralSecurityException("unsupported content manifest version")
        }
        // MVP fails closed on any future track attachment.
        if (manifest.hasTrack()) throw GeneralSecurityException("track attachment forbidden in v1")

        val parsed = manifest.photosList.map { entry ->
            ManifestPhoto(
                blobId = uuidFromBytes(entry.blobId.toByteArray()),
                ordinal = entry.ordinal,
                width = entry.width,
                height = entry.height,
            )
        }
        validatePhotos(parsed)
        return ContentManifestContent(
            protocolVersion = manifest.protocolVersion,
            photos = parsed,
            note = if (manifest.hasNote()) manifest.note else null,
        )
    }

    private fun validatePhotos(photos: List<ManifestPhoto>) {
        if (photos.size !in MIN_PHOTOS..MAX_PHOTOS) {
            throw IllegalArgumentException("exactly $MIN_PHOTOS..$MAX_PHOTOS photos required")
        }
        val ordinals = photos.map { it.ordinal }.sorted()
        if (ordinals != (0 until photos.size).toList()) {
            throw IllegalArgumentException("photo ordinals must be unique and sequential from 0")
        }
        photos.forEach {
            require(it.width in 1..MAX_DIMENSION_PX && it.height in 1..MAX_DIMENSION_PX) {
                "photo dimensions out of range"
            }
        }
    }

    private fun validateNote(note: String?) {
        note?.let {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_NOTE_BYTES) { "note exceeds byte limit" }
        }
    }

    private fun contentContext(routing: RecognitionManifestCodec.RoutingContext): ArtifactAadInput =
        ArtifactAadInput(
            capsuleId = routing.capsuleId,
            blobId = routing.blobId,
            artifactKind = CapsuleArtifactKind.CONTENT_MANIFEST,
            ordinal = NON_PHOTO_ORDINAL,
            senderUserId = routing.senderUserId,
            recipientUserId = routing.recipientUserId,
        )

    private fun longToBytes(uuid: UUID): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    private fun uuidFromBytes(bytes: ByteArray): UUID {
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }

    internal companion object {
        const val PROTOCOL_VERSION = 1
        const val MEDIA_TYPE_JPEG = "image/jpeg"
        const val MIN_PHOTOS = 3
        const val MAX_PHOTOS = 5
        const val MAX_DIMENSION_PX = 10_000
        const val MAX_NOTE_BYTES = 1000
        const val NON_PHOTO_ORDINAL = -1
    }
}
