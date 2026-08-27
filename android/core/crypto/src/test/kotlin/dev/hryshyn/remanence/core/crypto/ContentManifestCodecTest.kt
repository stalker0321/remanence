package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeysetHandle
import java.util.UUID
import kotlin.test.BeforeTest
import dev.hryshyn.remanence.core.model.ArtifactAadInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.security.GeneralSecurityException
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId

class ContentManifestCodecTest {

    private val codec = ContentManifestCodec()
    private lateinit var keyset: KeysetHandle
    private lateinit var wrongKeyset: KeysetHandle

    private val routing = RecognitionManifestCodec.RoutingContext(
        capsuleId = CapsuleId(UUID.fromString("1f0a1234-5678-4abc-9def-aabbccdd1001")),
        blobId = BlobId(UUID.fromString("6f0a1234-5678-4abc-9def-aabbccdd6006")),
        senderUserId = UserId(UUID.fromString("3f0a1234-5678-4abc-9def-aabbccdd3003")),
        recipientUserId = UserId(UUID.fromString("4f0a1234-5678-4abc-9def-aabbccdd4004")),
    )

    private fun photo(ordinal: Int, idHex: Char) = ManifestPhoto(
        blobId = UUID.fromString("af0a1234-5678-4abc-9def-aabbccdd00${ordinal}${idHex}"),
        ordinal = ordinal,
        width = 2560,
        height = 1600,
    )

    private fun photos(n: Int): List<ManifestPhoto> =
        (0 until n).map { photo(it, idHex = 'a' + it) }

    @BeforeTest
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        keyset = CapsuleKeysetGenerator().generate()
        wrongKeyset = CapsuleKeysetGenerator().generate()
    }

    @Test
    fun roundTripThreePhotosWithNotePreservesOrderAndFields() {
        val note = "С днём рождения!"
        val ciphertext = codec.buildAndEncrypt(keyset, routing, photos(3), note)
        val content = codec.decryptAndParse(keyset, routing, ciphertext)

        assertEquals(1, content.protocolVersion)
        assertEquals(note, content.note)
        assertEquals(listOf(0, 1, 2), content.photos.map { it.ordinal })
        assertTrue(content.photos.all { it.width == 2560 && it.height == 1600 })
        assertEquals(photos(3).map { it.blobId }, content.photos.map { it.blobId })
    }

    @Test
    fun fivePhotosRoundTripWithoutNote() {
        val ciphertext = codec.buildAndEncrypt(keyset, routing, photos(5), null)
        val content = codec.decryptAndParse(keyset, routing, ciphertext)
        assertNull(content.note)
        assertEquals(5, content.photos.size)
    }

    @Test
    fun fewerThanThreePhotosRejectedBeforeEncryption() {
        assertFailsWith<IllegalArgumentException> { codec.buildAndEncrypt(keyset, routing, photos(2), null) }
    }

    @Test
    fun moreThanFivePhotosRejected() {
        assertFailsWith<IllegalArgumentException> { codec.buildAndEncrypt(keyset, routing, photos(6), null) }
    }

    @Test
    fun duplicateOrdinalsRejected() {
        val bad = listOf(photo(0, 'a'), photo(0, 'b'), photo(1, 'c'))
        assertFailsWith<IllegalArgumentException> { codec.buildAndEncrypt(keyset, routing, bad, null) }
    }

    @Test
    fun gappedOrdinalsRejected() {
        val bad = listOf(photo(0, 'a'), photo(2, 'b'), photo(3, 'c'))
        assertFailsWith<IllegalArgumentException> { codec.buildAndEncrypt(keyset, routing, bad, null) }
    }

    @Test
    fun oversizeNoteRejectedBeforeEncryption() {
        assertFailsWith<IllegalArgumentException> {
            codec.buildAndEncrypt(keyset, routing, photos(3), "x".repeat(1001))
        }
    }

    @Test
    fun wrongKeyFailsClosedOnDecrypt() {
        val ciphertext = codec.buildAndEncrypt(keyset, routing, photos(3), null)
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(wrongKeyset, routing, ciphertext)
        }
    }

    @Test
    fun decryptedPhotosRevalidateCardinality() {
        // Build a valid manifest, then prove the parser still enforces bounds.
        val ciphertext = codec.buildAndEncrypt(keyset, routing, photos(3), null)
        val bytes = CapsuleArtifactCryptor().decrypt(
            keyset,
            ArtifactAadInput(
                capsuleId = routing.capsuleId,
                blobId = routing.blobId,
                artifactKind = dev.hryshyn.remanence.core.model.CapsuleArtifactKind.CONTENT_MANIFEST,
                ordinal = -1,
                senderUserId = routing.senderUserId,
                recipientUserId = routing.recipientUserId,
            ),
            ciphertext,
        )
        val resealed = CapsuleArtifactCryptor().encrypt(keyset, contentAad(), padWithExtraPhoto(bytes))
        // AEAD passes for the correctly re-sealed blob; cardinality must still fail closed
        // and the failure must surface as GeneralSecurityException, never IllegalArgumentException.
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing, resealed)
        }
    }

    private fun contentAad(): ArtifactAadInput =
        ArtifactAadInput(
            capsuleId = routing.capsuleId,
            blobId = routing.blobId,
            artifactKind = dev.hryshyn.remanence.core.model.CapsuleArtifactKind.CONTENT_MANIFEST,
            ordinal = -1,
            senderUserId = routing.senderUserId,
            recipientUserId = routing.recipientUserId,
        )

    /** Appends a syntactically valid extra PhotoEntry to the plaintext manifest. */
    private fun padWithExtraPhoto(manifestBytes: ByteArray): ByteArray {
        val parsed = dev.hryshyn.remanence.protocol.v1.ContentManifest.parseFrom(manifestBytes)
        val builder = parsed.toBuilder()
        for (ordinal in 3..5) {
            builder.addPhotos(
                dev.hryshyn.remanence.protocol.v1.PhotoEntry.newBuilder()
                    .setBlobId(com.google.protobuf.ByteString.copyFrom(ByteArray(16)))
                    .setOrdinal(ordinal)
                    .setMediaType(ContentManifestCodec.MEDIA_TYPE_JPEG)
                    .setWidth(100)
                    .setHeight(100),
            )
        }
        // Six photos violate the v1 maximum of five.
        return builder.build().toByteArray()
    }

    /** Encrypts an arbitrary plaintext under the content-manifest AAD for the given routing. */
    private fun encryptAsContentManifest(
        capsuleId: dev.hryshyn.remanence.core.model.CapsuleId,
        plaintext: ByteArray,
    ): ByteArray = CapsuleArtifactCryptor().encrypt(
        capsuleKeyset = keyset,
        context = ArtifactAadInput(
            capsuleId = capsuleId,
            blobId = routing.blobId,
            artifactKind = dev.hryshyn.remanence.core.model.CapsuleArtifactKind.CONTENT_MANIFEST,
            ordinal = -1,
            senderUserId = routing.senderUserId,
            recipientUserId = routing.recipientUserId,
        ),
        plaintext = plaintext,
    )

    @Test
    fun validGoldenThreePhotosWithNote() {
        val note = "С днём рождения!"
        val ciphertext = codec.buildAndEncrypt(keyset, routing, photos(3), note)
        val content = codec.decryptAndParse(keyset, routing, ciphertext)

        assertEquals(1, content.protocolVersion)
        assertEquals(note, content.note)
        assertEquals(listOf(0, 1, 2), content.photos.map { it.ordinal })
        assertTrue(content.photos.all { it.width == 2560 && it.height == 1600 })
        assertEquals(photos(3).map { it.blobId }, content.photos.map { it.blobId })
    }

    @Test
    fun validGoldenFivePhotosWithoutNote() {
        val ciphertext = codec.buildAndEncrypt(keyset, routing, photos(5), null)
        val content = codec.decryptAndParse(keyset, routing, ciphertext)
        assertNull(content.note)
        assertEquals(5, content.photos.size)
        assertEquals(listOf(0, 1, 2, 3, 4), content.photos.map { it.ordinal })
    }

    @Test
    fun wrongInnerCapsuleIdFailsClosed() {
        // Build a real manifest under one capsuleId, then encrypt and present
        // a different routing capsuleId at decrypt. The inner capsule must
        // bind to the routing; a mismatch fails closed with
        // GeneralSecurityException, never IllegalArgumentException.
        val otherCapsule = CapsuleId(UUID.fromString("9f0a1234-5678-4abc-9def-aabbccdd9999"))
        val ciphertext = codec.buildAndEncrypt(keyset, routing, photos(3), null)
        val otherRouting = routing.copy(capsuleId = otherCapsule)
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, otherRouting, ciphertext)
        }
    }

    @Test
    fun emptyPhotoBlobIdFailsClosed() {
        val parsed = dev.hryshyn.remanence.protocol.v1.ContentManifest.parseFrom(
            codec.buildAndEncrypt(keyset, routing, photos(3), null).let { c ->
                CapsuleArtifactCryptor().decrypt(keyset, contentAad(), c)
            },
        )
        val builder = parsed.toBuilder()
        builder.setPhotos(
            0,
            dev.hryshyn.remanence.protocol.v1.PhotoEntry.newBuilder()
                .setBlobId(com.google.protobuf.ByteString.EMPTY)
                .setOrdinal(0)
                .setMediaType(ContentManifestCodec.MEDIA_TYPE_JPEG)
                .setWidth(100)
                .setHeight(100)
                .build(),
        )
        val ciphertext = encryptAsContentManifest(routing.capsuleId, builder.build().toByteArray())
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing, ciphertext)
        }
    }

    @Test
    fun oversizedPhotoBlobIdFailsClosed() {
        val manifestBytes = CapsuleArtifactCryptor().decrypt(
            keyset,
            contentAad(),
            codec.buildAndEncrypt(keyset, routing, photos(3), null),
        )
        val parsed = dev.hryshyn.remanence.protocol.v1.ContentManifest.parseFrom(manifestBytes)
        val builder = parsed.toBuilder()
        builder.setPhotos(
            0,
            dev.hryshyn.remanence.protocol.v1.PhotoEntry.newBuilder()
                .setBlobId(com.google.protobuf.ByteString.copyFrom(ByteArray(17)))
                .setOrdinal(0)
                .setMediaType(ContentManifestCodec.MEDIA_TYPE_JPEG)
                .setWidth(100)
                .setHeight(100)
                .build(),
        )
        val ciphertext = encryptAsContentManifest(routing.capsuleId, builder.build().toByteArray())
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing, ciphertext)
        }
    }

    @Test
    fun truncatedPhotoBlobIdFailsClosed() {
        val manifestBytes = CapsuleArtifactCryptor().decrypt(
            keyset,
            contentAad(),
            codec.buildAndEncrypt(keyset, routing, photos(3), null),
        )
        val parsed = dev.hryshyn.remanence.protocol.v1.ContentManifest.parseFrom(manifestBytes)
        val builder = parsed.toBuilder()
        builder.setPhotos(
            0,
            dev.hryshyn.remanence.protocol.v1.PhotoEntry.newBuilder()
                .setBlobId(com.google.protobuf.ByteString.copyFrom(ByteArray(15)))
                .setOrdinal(0)
                .setMediaType(ContentManifestCodec.MEDIA_TYPE_JPEG)
                .setWidth(100)
                .setHeight(100)
                .build(),
        )
        val ciphertext = encryptAsContentManifest(routing.capsuleId, builder.build().toByteArray())
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing, ciphertext)
        }
    }

    @Test
    fun malformedProtobufPlaintextFailsClosed() {
        // Garbage bytes that decrypt under the content AAD but parse as
        // nothing meaningful must surface as GeneralSecurityException, never
        // as a protobuf exception.
        val ciphertext = encryptAsContentManifest(routing.capsuleId, ByteArray(64) { 0x55 })
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing, ciphertext)
        }
    }

    @Test
    fun invalidPhotoDimensionsFailClosed() {
        val manifestBytes = CapsuleArtifactCryptor().decrypt(
            keyset,
            contentAad(),
            codec.buildAndEncrypt(keyset, routing, photos(3), null),
        )
        val parsed = dev.hryshyn.remanence.protocol.v1.ContentManifest.parseFrom(manifestBytes)
        val builder = parsed.toBuilder()
        builder.setPhotos(
            0,
            dev.hryshyn.remanence.protocol.v1.PhotoEntry.newBuilder()
                .setBlobId(com.google.protobuf.ByteString.copyFrom(longToBytes(photos(3)[0].blobId)))
                .setOrdinal(0)
                .setMediaType(ContentManifestCodec.MEDIA_TYPE_JPEG)
                .setWidth(0)
                .setHeight(100)
                .build(),
        )
        val ciphertext = encryptAsContentManifest(routing.capsuleId, builder.build().toByteArray())
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing, ciphertext)
        }
    }

    @Test
    fun oversizedNoteOnDecryptedManifestFailsClosed() {
        val manifestBytes = CapsuleArtifactCryptor().decrypt(
            keyset,
            contentAad(),
            codec.buildAndEncrypt(keyset, routing, photos(3), null),
        )
        val parsed = dev.hryshyn.remanence.protocol.v1.ContentManifest.parseFrom(manifestBytes)
        val builder = parsed.toBuilder().setNote("x".repeat(1001))
        val ciphertext = encryptAsContentManifest(routing.capsuleId, builder.build().toByteArray())
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing, ciphertext)
        }
    }

    @Test
    fun duplicateOrdinalsOnDecryptedManifestFailClosed() {
        val manifestBytes = CapsuleArtifactCryptor().decrypt(
            keyset,
            contentAad(),
            codec.buildAndEncrypt(keyset, routing, photos(3), null),
        )
        val parsed = dev.hryshyn.remanence.protocol.v1.ContentManifest.parseFrom(manifestBytes)
        val builder = parsed.toBuilder()
        builder.setPhotos(
            1,
            dev.hryshyn.remanence.protocol.v1.PhotoEntry.newBuilder()
                .setBlobId(com.google.protobuf.ByteString.copyFrom(longToBytes(photos(3)[0].blobId)))
                .setOrdinal(0)
                .setMediaType(ContentManifestCodec.MEDIA_TYPE_JPEG)
                .setWidth(100)
                .setHeight(100)
                .build(),
        )
        val ciphertext = encryptAsContentManifest(routing.capsuleId, builder.build().toByteArray())
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing, ciphertext)
        }
    }

    @Test
    fun gappedOrdinalsOnDecryptedManifestFailClosed() {
        val manifestBytes = CapsuleArtifactCryptor().decrypt(
            keyset,
            contentAad(),
            codec.buildAndEncrypt(keyset, routing, photos(3), null),
        )
        val parsed = dev.hryshyn.remanence.protocol.v1.ContentManifest.parseFrom(manifestBytes)
        val builder = parsed.toBuilder()
        builder.setPhotos(
            1,
            dev.hryshyn.remanence.protocol.v1.PhotoEntry.newBuilder()
                .setBlobId(com.google.protobuf.ByteString.copyFrom(longToBytes(photos(3)[1].blobId)))
                .setOrdinal(3)
                .setMediaType(ContentManifestCodec.MEDIA_TYPE_JPEG)
                .setWidth(100)
                .setHeight(100)
                .build(),
        )
        // Now ordinals are 0, 3, 2 (sorted: 0, 2, 3) — gapped.
        val ciphertext = encryptAsContentManifest(routing.capsuleId, builder.build().toByteArray())
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing, ciphertext)
        }
    }

    private fun longToBytes(uuid: UUID): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }
}
