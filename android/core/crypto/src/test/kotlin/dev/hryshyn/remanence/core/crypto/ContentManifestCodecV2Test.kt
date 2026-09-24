package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.core.model.ArtifactAadInput
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.GeneratorExpressionProjection
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.protocol.v1.ContentManifest
import com.google.crypto.tink.KeysetHandle
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ADR-018 step 5: v2 expression artifact codec round-trip / tamper / mismatch. */
class ContentManifestCodecV2Test {

    private val codec = ContentManifestCodec()
    private lateinit var keyset: KeysetHandle

    private val routing = RecognitionManifestCodec.RoutingContext(
        capsuleId = CapsuleId(UUID.fromString("1f0a1234-5678-4abc-9def-aabbccdd2001")),
        blobId = BlobId(UUID.fromString("6f0a1234-5678-4abc-9def-aabbccdd6007")),
        senderUserId = UserId(UUID.fromString("3f0a1234-5678-4abc-9def-aabbccdd3003")),
        recipientUserId = UserId(UUID.fromString("4f0a1234-5678-4abc-9def-aabbccdd4004")),
    )

    private val contentIds = listOf("c0", "c1", "c2")
    private val blobIds = listOf(
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

    private fun manifestPhotos(): List<ManifestPhoto> =
        blobIds.mapIndexed { index, blobId -> ManifestPhoto(blobId, index, 1000, 1000) }

    private fun blobMap(): Map<String, ByteArray> =
        contentIds.mapIndexed { index, id -> id to uuidBytes(blobIds[index]) }.toMap()

    private fun input(note: String? = null) = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 7L,
        photos = contentIds.mapIndexed { index, id ->
            GeneratorExpression.PhotoRef(
                contentId = id,
                ordinal = index,
                widthPx = 1000,
                heightPx = 1000,
                contentHash = ('a' + index).toString().repeat(64),
            )
        },
        note = note,
        music = null,
    )

    private val fitsPort = GeneratorEditorialRows.NoteMeasurementPort {
        GeneratorEditorialRows.NoteMeasurement.Fits(GeneratorEditorialRows.NOTE_REGION)
    }

    private fun ber1(note: String? = null): GeneratorExpression.ResolvedExpression {
        val result = GeneratorEditorialRows.plan(input(note), if (note == null) null else fitsPort)
        assertTrue(result is GeneratorEditorialRows.PlanResult.Planned, "got $result")
        return (result as GeneratorEditorialRows.PlanResult.Planned).expression
    }

    @BeforeTest
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        keyset = CapsuleKeysetGenerator().generate()
    }

    @Test
    fun v2RoundTripPreservesExpressionAndHash() {
        val expression = ber1()
        val ciphertext = codec.buildAndEncryptV2(
            keyset, routing, manifestPhotos(), null, "cand-1", expression, blobMap(),
        )
        val content = codec.decryptAndParse(keyset, routing, ciphertext)
        assertEquals(2, content.protocolVersion)
        assertEquals(listOf(0, 1, 2), content.photos.map { it.ordinal })
        assertEquals(blobIds, content.photos.map { it.blobId })
        val parsed = assertNotNull(content.expression)
        assertEquals("cand-1", parsed.candidateId)
        assertEquals(
            GeneratorExpressionProjection.hash(expression, "cand-1", blobMap()),
            parsed.projectionHash,
        )
        assertEquals(expression.placements.map { it.contentId }, parsed.expression.placements.map { it.contentId })
        assertEquals(expression.placements.map { it.contentRect }, parsed.expression.placements.map { it.contentRect })
        assertEquals(expression.placements.map { it.x }, parsed.expression.placements.map { it.x })
        // Reviewer: the receiver must see the FULL source bindings, not just
        // the projection hash.
        assertEquals(contentIds, parsed.expression.input.photos.map { it.contentId })
        assertEquals(listOf(0, 1, 2), parsed.expression.input.photos.map { it.ordinal })
        assertEquals(
            expression.input.photos.map { it.contentHash },
            parsed.expression.input.photos.map { it.contentHash },
        )
        assertEquals(
            expression.input.photos.map { it.widthPx to it.heightPx },
            parsed.expression.input.photos.map { it.widthPx to it.heightPx },
        )
        assertEquals(blobIds, content.photos.map { it.blobId })
    }

    @Test
    fun v2MissingSourceBindingFailsClosedAtBuild() {
        val incomplete = blobMap().toMutableMap().apply { remove("c1") }
        assertFailsWith<IllegalArgumentException> {
            codec.buildAndEncryptV2(
                keyset, routing, manifestPhotos(), null, "cand-1", ber1(), incomplete,
            )
        }
    }

    @Test
    fun v2BlobIdMismatchFailsClosed() {
        val tampered = blobMap().toMutableMap().apply { put("c1", uuidBytes(UUID.randomUUID())) }
        val ciphertext = codec.buildAndEncryptV2(
            keyset, routing, manifestPhotos(), null, "cand-1", ber1(), tampered,
        )
        assertFailsWith<GeneralSecurityException> { codec.decryptAndParse(keyset, routing, ciphertext) }
    }

    @Test
    fun v2NoteRegionWithoutNoteFailsClosed() {
        val expression = ber1(note = "hi")
        val ciphertext = codec.buildAndEncryptV2(
            keyset, routing, manifestPhotos(), null, "cand-1", expression, blobMap(),
        )
        assertFailsWith<GeneralSecurityException> { codec.decryptAndParse(keyset, routing, ciphertext) }
    }

    @Test
    fun receiverAdmitsV2RoundTripAndRejectsV1() {
        val ciphertext = codec.buildAndEncryptV2(
            keyset, routing, manifestPhotos(), null, "cand-1", ber1(), blobMap(),
        )
        val content = codec.decryptAndParse(keyset, routing, ciphertext)
        val admitted = ExpressionReceiverAdmission.admit(content)
        assertTrue(admitted is ExpressionReceiverAdmission.Result.Supported, "got $admitted")
        assertEquals(
            content.expression!!.expression.placements.map { it.contentId },
            (admitted as ExpressionReceiverAdmission.Result.Supported).expression.placements.map { it.contentId },
        )
        val v1 = codec.decryptAndParse(keyset, routing, codec.buildAndEncrypt(keyset, routing, manifestPhotos(), null))
        assertTrue(
            ExpressionReceiverAdmission.admit(v1) is ExpressionReceiverAdmission.Result.Unsupported,
        )
    }

    @Test
    fun receiverRejectsNoteRegionMismatch() {
        val expression = ber1(note = "hi")
        val mismatched = ContentManifestContent(
            protocolVersion = 2,
            photos = manifestPhotos(),
            note = null,
            expression = ContentExpression("cand-1", "a".repeat(64), expression),
        )
        val admitted = ExpressionReceiverAdmission.admit(mismatched)
        assertTrue(admitted is ExpressionReceiverAdmission.Result.Unsupported, "got $admitted")
    }

    @Test
    fun v1ManifestCarryingExpressionFieldFailsClosed() {
        // Build an authenticated v2 manifest, then flip only the outer
        // protocol_version back to 1 while KEEPING field 6 (expression). A v1
        // reader must reject the mixed-version frame instead of silently
        // dropping the expression and rendering a v1 photo layout.
        val v2Ciphertext = codec.buildAndEncryptV2(
            keyset, routing, manifestPhotos(), null, "cand-1", ber1(), blobMap(),
        )
        val contentContext = ArtifactAadInput(
            capsuleId = routing.capsuleId,
            blobId = routing.blobId,
            artifactKind = CapsuleArtifactKind.CONTENT_MANIFEST,
            ordinal = -1,
            senderUserId = routing.senderUserId,
            recipientUserId = routing.recipientUserId,
        )
        val v2Plaintext = CapsuleArtifactCryptor().decrypt(keyset, contentContext, v2Ciphertext)
        val mixed = ContentManifest.parseFrom(v2Plaintext)
            .toBuilder()
            .setProtocolVersion(1)
            .build()
        assertTrue(mixed.hasExpression(), "fixture must actually carry field 6")
        val mixedCiphertext = CapsuleArtifactCryptor().encrypt(
            capsuleKeyset = keyset,
            context = contentContext,
            plaintext = mixed.toByteArray(),
        )
        assertFailsWith<GeneralSecurityException> {
            codec.decryptAndParse(keyset, routing, mixedCiphertext)
        }
    }

    @Test
    fun builderRejectsOversizedCandidateIdAndInvalidExpression() {        assertFailsWith<IllegalArgumentException> {
            codec.buildAndEncryptV2(
                keyset, routing, manifestPhotos(), null, "x".repeat(257), ber1(), blobMap(),
            )
        }
        val v1 = ber1().copy(
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V1,
            placements = ber1().placements.map { it.copy(contentRect = null) },
            noteRegion = null,
        )
        assertFailsWith<IllegalArgumentException> {
            codec.buildAndEncryptV2(keyset, routing, manifestPhotos(), null, "cand-1", v1, blobMap())
        }
    }
}
