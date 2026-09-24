package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.core.model.ArtifactAadInput
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.protocol.v1.ContentManifest
import dev.hryshyn.remanence.protocol.v1.ExpressionPlacement
import dev.hryshyn.remanence.protocol.v1.ExpressionSource
import dev.hryshyn.remanence.protocol.v1.ExpressionV1
import dev.hryshyn.remanence.protocol.v1.PhotoEntry
import com.google.crypto.tink.KeysetHandle
import com.google.protobuf.ByteString
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** ADR-018 reviewer negatives: crafted v2 manifests must fail closed on decrypt. */
class ContentManifestCodecV2NegativeTest {

    private val codec = ContentManifestCodec()
    private lateinit var keyset: KeysetHandle

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

    private fun input() = GeneratorExpression.GeneratorInput(
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

    private fun ber1(): GeneratorExpression.ResolvedExpression {
        val result = GeneratorEditorialRows.plan(input())
        return (result as GeneratorEditorialRows.PlanResult.Planned).expression
    }

    @BeforeTest
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        keyset = CapsuleKeysetGenerator().generate()
    }

    private fun expressionProto(
        expression: GeneratorExpression.ResolvedExpression,
        hash: String,
        version: Int = 1,
        contentIdOverride: String? = null,
    ): ExpressionV1 {
        val builder = ExpressionV1.newBuilder()
            .setExpressionVersion(version)
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
                ExpressionSource.newBuilder()
                    .setOrdinal(photo.ordinal)
                    .setBlobId(ByteString.copyFrom(uuidBytes(blobUuids[photo.ordinal])))
                    .setContentId(contentIdOverride ?: photo.contentId)
                    .setContentHash(photo.contentHash)
                    .setWidthPx(photo.widthPx)
                    .setHeightPx(photo.heightPx),
            )
        }
        expression.placements.forEach { placement ->
            val p = ExpressionPlacement.newBuilder()
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

    private fun encrypt(manifest: ContentManifest): ByteArray =
        CapsuleArtifactCryptor().encrypt(
            capsuleKeyset = keyset,
            context = ArtifactAadInput(
                capsuleId = CapsuleId(capsuleUuid),
                blobId = BlobId(contentBlobUuid),
                artifactKind = CapsuleArtifactKind.CONTENT_MANIFEST,
                ordinal = -1,
                senderUserId = UserId(senderUuid),
                recipientUserId = UserId(recipientUuid),
            ),
            plaintext = manifest.toByteArray(),
        )

    private fun manifest(
        expression: ExpressionV1?,
        protocolVersion: Int = 2,
    ): ContentManifest {
        val b = ContentManifest.newBuilder()
            .setProtocolVersion(protocolVersion)
            .setCapsuleId(ByteString.copyFrom(uuidBytes(capsuleUuid)))
        blobUuids.forEachIndexed { index, blobId ->
            b.addPhotos(
                PhotoEntry.newBuilder()
                    .setBlobId(ByteString.copyFrom(uuidBytes(blobId)))
                    .setOrdinal(index)
                    .setMediaType("image/jpeg")
                    .setWidth(1000).setHeight(1000),
            )
        }
        expression?.let(b::setExpression)
        return b.build()
    }

    private fun routing() = RecognitionManifestCodec.RoutingContext(
        capsuleId = CapsuleId(capsuleUuid),
        blobId = BlobId(contentBlobUuid),
        senderUserId = UserId(senderUuid),
        recipientUserId = UserId(recipientUuid),
    )

    private fun expectRejected(manifest: ContentManifest) {
        val ciphertext = encrypt(manifest)
        assertFailsWith<GeneralSecurityException> { codec.decryptAndParse(keyset, routing(), ciphertext) }
    }

    @Test
    fun expressionHashTamperFails() {
        val expression = ber1()
        expectRejected(manifest(expressionProto(expression, hash = "0".repeat(64))))
    }

    @Test
    fun unknownExpressionVersionFails() {
        val expression = ber1()
        val validHash = dev.hryshyn.remanence.core.model.GeneratorExpressionProjection.hash(
            expression, "cand-1",
            contentIds.mapIndexed { i, id -> id to uuidBytes(blobUuids[i]) }.toMap(),
        )
        expectRejected(manifest(expressionProto(expression, hash = validHash, version = 2)))
    }

    @Test
    fun v2ManifestWithoutExpressionFails() {
        expectRejected(manifest(expression = null, protocolVersion = 2))
    }

    @Test
    fun permutedOrDuplicatePlacementsFail() {
        val expression = ber1()
        val validHash = dev.hryshyn.remanence.core.model.GeneratorExpressionProjection.hash(
            expression, "cand-1",
            contentIds.mapIndexed { i, id -> id to uuidBytes(blobUuids[i]) }.toMap(),
        )
        val base = expressionProto(expression, validHash)
        val permuted = base.toBuilder().also { e ->
            val list = e.placementsList.toList().asReversed()
            e.clearPlacements()
            list.forEach(e::addPlacements)
        }.build()
        expectRejected(manifest(permuted))
        val single = base.placementsList[0]
        val duplicated = base.toBuilder().also { e ->
            e.clearPlacements()
            repeat(3) { e.addPlacements(single) }
        }.build()
        expectRejected(manifest(duplicated))
    }

    @Test
    fun uriContentIdFails() {
        val expression = ber1()
        val validHash = dev.hryshyn.remanence.core.model.GeneratorExpressionProjection.hash(
            expression, "cand-1",
            contentIds.mapIndexed { i, id -> id to uuidBytes(blobUuids[i]) }.toMap(),
        )
        expectRejected(manifest(expressionProto(expression, validHash, contentIdOverride = "content://media/1")))
    }
}
