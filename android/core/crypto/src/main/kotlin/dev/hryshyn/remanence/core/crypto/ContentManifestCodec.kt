package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.protocol.v1.ContentManifest
import dev.hryshyn.remanence.protocol.v1.PhotoEntry
import com.google.crypto.tink.KeysetHandle
import java.security.GeneralSecurityException
import java.security.MessageDigest
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
    ): ContentManifestContent = try {
        val bytes = CapsuleArtifactCryptor().decrypt(
            capsuleKeyset,
            contentContext(routingContext),
            ciphertext,
        )
        val manifest = ContentManifest.parseFrom(bytes)
        if (manifest.protocolVersion != PROTOCOL_VERSION) {
            throw GeneralSecurityException("unsupported content manifest version")
        }
        // Bind the inner manifest capsule to the routing context: a different
        // capsule id (or a non-16-byte capsule id) fails closed.
        val expectedCapsuleId = routingContext.capsuleId.toProtoBytes()
        if (manifest.capsuleId.size() != expectedCapsuleId.size() ||
            !MessageDigest.isEqual(manifest.capsuleId.toByteArray(), expectedCapsuleId.toByteArray())
        ) {
            throw GeneralSecurityException("content manifest capsule id does not match routing")
        }
        // MVP fails closed on any future track attachment.
        if (manifest.hasTrack()) throw GeneralSecurityException("track attachment forbidden in v1")
        if (manifest.hasNote() &&
            manifest.note.toByteArray(Charsets.UTF_8).size > MAX_NOTE_BYTES
        ) {
            throw GeneralSecurityException("content manifest note exceeds byte limit")
        }

        val parsed = manifest.photosList.map { entry ->
            val photoBytes = entry.blobId.toByteArray()
            if (photoBytes.size != PHOTO_BLOB_ID_BYTES) {
                throw GeneralSecurityException("content manifest photo blob id has invalid length")
            }
            val buffer = java.nio.ByteBuffer.wrap(photoBytes)
            ManifestPhoto(
                blobId = UUID(buffer.long, buffer.long),
                ordinal = entry.ordinal,
                width = entry.width,
                height = entry.height,
            )
        }
        if (parsed.size !in MIN_PHOTOS..MAX_PHOTOS) {
            throw GeneralSecurityException("content manifest photo count out of v1 range")
        }
        val ordinals = parsed.map { it.ordinal }.sorted()
        val expectedOrdinals = (0 until parsed.size).toList()
        if (ordinals != expectedOrdinals) {
            throw GeneralSecurityException("content manifest photo ordinals are not a 0-based sequence")
        }
        parsed.forEach {
            if (it.width !in 1..MAX_DIMENSION_PX || it.height !in 1..MAX_DIMENSION_PX) {
                throw GeneralSecurityException("content manifest photo dimensions out of range")
            }
        }

        ContentManifestContent(
            protocolVersion = manifest.protocolVersion,
            photos = parsed,
            note = if (manifest.hasNote()) manifest.note else null,
        )
    } catch (failure: GeneralSecurityException) {
        throw failure
    } catch (failure: Exception) {
        // No BufferUnderflowException, IllegalArgumentException, or protobuf
        // exception may escape this public decrypt boundary.
        throw GeneralSecurityException("content manifest failed structural validation").apply {
            initCause(failure)
        }
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

    internal companion object {
        const val PROTOCOL_VERSION = 1
        const val MEDIA_TYPE_JPEG = "image/jpeg"
        const val MIN_PHOTOS = 3
        const val MAX_PHOTOS = 5
        const val MAX_DIMENSION_PX = 10_000
        const val MAX_NOTE_BYTES = 1000
        const val NON_PHOTO_ORDINAL = -1
        const val PHOTO_BLOB_ID_BYTES = 16
    }
}
