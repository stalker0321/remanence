package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.core.model.ArtifactAadInput
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.GeneratorExpressionProjection
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.protocol.v1.CapsuleTrackSnapshotV1 as ProtoTrackSnapshot
import dev.hryshyn.remanence.protocol.v1.ContentManifest
import dev.hryshyn.remanence.protocol.v1.PhotoEntry
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** S2 snapshot codec: v2 round-trip/exposure, v1+field7 rejection, AEAD tamper. */
class ContentManifestCodecTrackSnapshotTest {

    private val codec = ContentManifestCodec()
    private lateinit var keyset: KeysetHandle
    private lateinit var wrongKeyset: KeysetHandle

    private val capsuleUuid = UUID.fromString("1f0a1234-5678-4abc-9def-aabbccdd2001")
    private val contentBlobUuid = UUID.fromString("6f0a1234-5678-4abc-9def-aabbccdd6007")
    private val senderUuid = UUID.fromString("3f0a1234-5678-4abc-9def-aabbccdd3003")
    private val recipientUuid = UUID.fromString("4f0a1234-5678-4abc-9def-aabbccdd4004")

    private val contentIds = listOf("c0", "c1", "c2")
    private val blobUuids = listOf(
        UUID.fromString("af0a1234-5678-4abc-9def-aabbccdd0001"),
        UUID.fromString("af0a1234-5678-4abc-9def-aabbccdd0002"),
        UUID.fromString("af0a1234-5678-4abc-9def-aabbccdd0003"),
    )

    private fun uuidBytes(uuid: UUID): ByteArray {
        val buffer = ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    private fun routing() = RecognitionManifestCodec.RoutingContext(
        capsuleId = CapsuleId(capsuleUuid),
        blobId = BlobId(contentBlobUuid),
        senderUserId = UserId(senderUuid),
        recipientUserId = UserId(recipientUuid),
    )

    private fun manifestPhotos(): List<ManifestPhoto> =
        blobUuids.mapIndexed { index, id ->
            ManifestPhoto(blobId = id, ordinal = index, width = 1000, height = 1000)
        }

    private fun blobMap(): Map<String, ByteArray> =
        contentIds.mapIndexed { i, id -> id to uuidBytes(blobUuids[i]) }.toMap()

    private fun ber1(): GeneratorExpression.ResolvedExpression {
        val input = GeneratorExpression.GeneratorInput(
            ownerId = "owner-1",
            epoch = 7L,
            photos = contentIds.mapIndexed { index, id ->
                GeneratorExpression.PhotoRef(
                    contentId = id, ordinal = index, widthPx = 1000, heightPx = 1000,
                    contentHash = ('a' + index).toString().repeat(64),
                )
            },
            note = null,
            music = null,
        )
        val result = GeneratorEditorialRows.plan(input)
        return (result as GeneratorEditorialRows.PlanResult.Planned).expression
    }

    private fun snapshot() = CapsuleTrackSnapshotV1.parse(
        trackId = "be30e36b-1111-4111-8111-000000000001",
        title = "505",
        artistDisplay = "Arctic Monkeys",
        version = null,
        durationMs = 253000L,
    )

    @BeforeTest
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        keyset = CapsuleKeysetGenerator().generate()
        wrongKeyset = CapsuleKeysetGenerator().generate()
    }

    @Test
    fun v2RoundTripWithSnapshotExposesAllFields() {
        val ciphertext = codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null,
            "cand-1", ber1(), blobMap(), snapshot(),
        )
        val content = codec.decryptAndParse(keyset, routing(), ciphertext)
        assertEquals(2, content.protocolVersion)
        assertEquals(3, content.photos.size)
        val parsed = content.trackSnapshot
            ?: throw AssertionError("expected track snapshot")
        assertEquals(UUID.fromString("be30e36b-1111-4111-8111-000000000001"), parsed.trackId)
        assertEquals("505", parsed.title)
        assertEquals("Arctic Monkeys", parsed.artistDisplay)
        assertNull(parsed.version)
        assertEquals(253000L, parsed.durationMs)
    }

    @Test
    fun v2WithoutSnapshotParsesNullAndExpressionIntact() {
        val ciphertext = codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null,
            "cand-1", ber1(), blobMap(),
        )
        val content = codec.decryptAndParse(keyset, routing(), ciphertext)
        assertEquals(2, content.protocolVersion)
        assertNull(content.trackSnapshot)
    }

    @Test
    fun v1FrameCarryingField7FailsClosed() {
        val manifest = ContentManifest.newBuilder()
            .setProtocolVersion(1)
            .setCapsuleId(ByteString.copyFrom(uuidBytes(capsuleUuid)))
            .setTrackSnapshot(
                ProtoTrackSnapshot.newBuilder()
                    .setSnapshotVersion(1)
                    .setTrackId("be30e36b-1111-4111-8111-000000000001")
                    .setTitle("505")
                    .setArtistDisplay("Arctic Monkeys"),
            )
        blobUuids.forEachIndexed { index, blobId ->
            manifest.addPhotos(
                PhotoEntry.newBuilder()
                    .setBlobId(ByteString.copyFrom(uuidBytes(blobId)))
                    .setOrdinal(index)
                    .setMediaType("image/jpeg")
                    .setWidth(1000).setHeight(1000),
            )
        }
        val ciphertext = CapsuleArtifactCryptor().encrypt(
            capsuleKeyset = keyset,
            context = ArtifactAadInput(
                capsuleId = CapsuleId(capsuleUuid),
                blobId = BlobId(contentBlobUuid),
                artifactKind = CapsuleArtifactKind.CONTENT_MANIFEST,
                ordinal = -1,
                senderUserId = UserId(senderUuid),
                recipientUserId = UserId(recipientUuid),
            ),
            plaintext = manifest.build().toByteArray(),
        )
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing(), ciphertext)
        }
    }

    private fun expressionProto(expression: GeneratorExpression.ResolvedExpression): dev.hryshyn.remanence.protocol.v1.ExpressionV1 {
        val hash = GeneratorExpressionProjection.hash(expression, "cand-1", blobMap())
        val builder = dev.hryshyn.remanence.protocol.v1.ExpressionV1.newBuilder()
            .setExpressionVersion(1)
            .setCandidateId("cand-1")
            .setExpressionHash(hash)
            .setCanvasVersion(expression.canvasVersion)
            .setGrammarId(expression.grammarId)
            .setGrammarVersion(expression.grammarVersion)
            .setBranchId(expression.branchId)
            .setNoteTreatment(expression.noteTreatment)
            .setFontVersion(expression.fontVersion)
            .setPaletteVersion(expression.paletteVersion)
        expression.input.photos.forEach { photo ->
            builder.addSources(
                dev.hryshyn.remanence.protocol.v1.ExpressionSource.newBuilder()
                    .setOrdinal(photo.ordinal)
                    .setBlobId(ByteString.copyFrom(uuidBytes(blobUuids[photo.ordinal])))
                    .setContentId(photo.contentId)
                    .setContentHash(photo.contentHash)
                    .setWidthPx(photo.widthPx)
                    .setHeightPx(photo.heightPx),
            )
        }
        expression.placements.forEach { placement ->
            val p = dev.hryshyn.remanence.protocol.v1.ExpressionPlacement.newBuilder()
                .setContentId(placement.contentId)
                .setX(placement.x).setY(placement.y)
                .setWidth(placement.width).setHeight(placement.height)
            placement.contentRect?.let { r ->
                p.setHasContentRect(true).setRectX(r.x).setRectY(r.y).setRectWidth(r.width).setRectHeight(r.height)
            }
            builder.addPlacements(p)
        }
        return builder.build()
    }

    private fun sealedV2WithSnapshot(snapshot: ProtoTrackSnapshot): ByteArray {
        val manifest = ContentManifest.newBuilder()
            .setProtocolVersion(2)
            .setCapsuleId(ByteString.copyFrom(uuidBytes(capsuleUuid)))
            .setExpression(expressionProto(ber1()))
            .setTrackSnapshot(snapshot)
        blobUuids.forEachIndexed { index, blobId ->
            manifest.addPhotos(
                PhotoEntry.newBuilder()
                    .setBlobId(ByteString.copyFrom(uuidBytes(blobId)))
                    .setOrdinal(index)
                    .setMediaType("image/jpeg")
                    .setWidth(1000).setHeight(1000),
            )
        }
        return CapsuleArtifactCryptor().encrypt(
            capsuleKeyset = keyset,
            context = ArtifactAadInput(
                capsuleId = CapsuleId(capsuleUuid),
                blobId = BlobId(contentBlobUuid),
                artifactKind = CapsuleArtifactKind.CONTENT_MANIFEST,
                ordinal = -1,
                senderUserId = UserId(senderUuid),
                recipientUserId = UserId(recipientUuid),
            ),
            plaintext = manifest.build().toByteArray(),
        )
    }

    private fun validSnapshotProto(): ProtoTrackSnapshot =
        ProtoTrackSnapshot.newBuilder()
            .setSnapshotVersion(1)
            .setTrackId("be30e36b-1111-4111-8111-000000000001")
            .setTitle("505")
            .setArtistDisplay("Arctic Monkeys")
            .setDurationMs(253000L)
            .build()

    @Test
    fun snapshotWithWrongVersionFailsClosed() {
        val bad = validSnapshotProto().toBuilder().setSnapshotVersion(2).build()
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing(), sealedV2WithSnapshot(bad))
        }
    }

    @Test
    fun snapshotWithBadUuidFailsClosed() {
        val bad = validSnapshotProto().toBuilder().setTrackId("not-a-uuid").build()
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing(), sealedV2WithSnapshot(bad))
        }
    }

    @Test
    fun snapshotWithBlankTitleFailsClosed() {
        val bad = validSnapshotProto().toBuilder().setTitle("   ").build()
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing(), sealedV2WithSnapshot(bad))
        }
    }

    @Test
    fun sealedValidSnapshotParsesThroughManualFrame() {
        val content = codec.decryptAndParse(keyset, routing(), sealedV2WithSnapshot(validSnapshotProto()))
        assertEquals(253000L, content.trackSnapshot?.durationMs)
    }

    @Test
    fun tamperedCiphertextWithSnapshotFailsClosed() {
        val ciphertext = codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null,
            "cand-1", ber1(), blobMap(), snapshot(),
        )
        ciphertext[ciphertext.size / 2] = ciphertext[ciphertext.size / 2].inc()
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing(), ciphertext)
        }
    }

    @Test
    fun wrongKeyWithSnapshotFailsClosed() {
        val ciphertext = codec.buildAndEncryptV2(
            keyset, routing(), manifestPhotos(), null,
            "cand-1", ber1(), blobMap(), snapshot(),
        )
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(wrongKeyset, routing(), ciphertext)
        }
    }
}
