package dev.hryshyn.remanence.create

import com.google.crypto.tink.TinkProtoKeysetFormat
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.DeliveredCiphertext
import dev.hryshyn.remanence.core.crypto.PresentationAcceptanceGate
import dev.hryshyn.remanence.core.crypto.PresentationAcceptanceInput
import dev.hryshyn.remanence.core.crypto.PresentationAcceptancePreparationResult
import dev.hryshyn.remanence.core.crypto.RecipientEnvelopeCryptor
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.outbox.OutboxArtifactKind
import dev.hryshyn.remanence.core.data.outbox.PreparedOutboxCapsule
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorBer1Provider
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-021 sending: a recorded voice note is sealed as the one MEDIA artifact
 * after the photos, named by manifest field 9, and opens through the
 * recipient's gate to the exact MP4; a voice note needs a voice band and no song.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapsulePublisherVoiceTest {

    private val kek = SoftwareKekBoundary()
    private val alias = "test-sender-retry-${UUID.randomUUID()}"
    private lateinit var publisher: CapsulePublisher
    private val identity = AccountIdentityGenerator().generate()

    private val capsuleUuid = UUID.fromString("7c111111-2222-4333-8444-555555555555")
    private val userUuid = UUID.fromString("7c222222-3333-4444-8555-666666666666")
    private val bundleUuid = UUID.fromString("7c333333-4444-4555-8666-777777777777")
    private val contentIds = listOf("c0", "c1", "c2")

    private val sound = byteArrayOf(0, 0, 0, 16) + "ftypM4A ".toByteArray() + ByteArray(64) { it.toByte() }
    private val peaks = IntArray(48) { (it * 37) % 256 }

    @Before
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        kek.createAes256GcmKey(alias)
        publisher = CapsulePublisher(SenderRetryKeysetWrapper(kek), alias)
    }

    private fun expression(mode: String?) = GeneratorExpression.ResolvedExpression(
        canvasVersion = 1,
        grammarId = "6",
        grammarVersion = 3,
        branchId = "hinge-2/1-0.52-equal-paired",
        input = GeneratorExpression.GeneratorInput(
            ownerId = userUuid.toString(),
            epoch = 7L,
            photos = contentIds.mapIndexed { index, id ->
                GeneratorExpression.PhotoRef(id, index, 1000, 1000, ('a' + index).toString().repeat(64))
            },
            note = null,
            music = null,
        ),
        placements = contentIds.mapIndexed { index, id ->
            GeneratorExpression.Placement(
                contentId = id, x = index * 120, y = 0, width = 120, height = 213,
                crop = GeneratorExpression.CropWindow(100, 200, 5000, 6000),
                maskId = null, contentRect = null, shape = GeneratorExpression.PhotoShape.RECT,
            )
        },
        noteTreatment = "regular-region",
        diagnostics = listOf("ok"),
        fontVersion = 2,
        paletteVersion = 4,
        expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V4,
        noteRegion = null,
        style = GeneratorExpression.StyleBinding("signal", "style-study-1", ByteArray(32) { 7 }),
        noteType = GeneratorExpression.TextType("StudySans", 16, 400, false, 18),
        musicRegion = mode?.let {
            GeneratorExpression.MusicRegion(
                x = 10, y = 500, width = 340, height = 76, mode = it,
                type = GeneratorExpression.TextType("Narrow", 24, 700, false, 26),
                artwork = null,
            )
        },
        provenance = listOf(
            GeneratorExpression.ProvenanceMark(
                kind = GeneratorExpression.ProvenanceKind.BRAND, text = GeneratorExpression.BRAND_TEXT,
                x = 12, y = 4, width = 340, height = 75, family = "StudySerif", size = 64, weight = 400,
                italic = false, line = 70, orientation = GeneratorExpression.MarkOrientation.HORIZONTAL,
                align = GeneratorExpression.MarkAlign.LEFT,
            ),
        ),
        decor = listOf(GeneratorExpression.SceneDecor(GeneratorExpression.DecorKind.CARRIER, 6, 5, 349, 627)),
    )

    private fun request(mode: String?, voice: CapsuleVoiceNote?, snapshot: CapsuleTrackSnapshotV1? = null): CapsulePublishRequest {
        val resolved = expression(mode)
        return CapsulePublishRequest(
            capsuleId = CapsuleId(capsuleUuid),
            senderUserId = UserId(userUuid),
            recipientUserId = UserId(userUuid),
            senderKeyBundleId = KeyBundleId(bundleUuid),
            recipientKeyBundleId = KeyBundleId(bundleUuid),
            ownerUserId = userUuid.toString(),
            senderHandleSnapshot = "mykola",
            createdAtEpochSeconds = 1_700_000_000L,
            photoJpegs = (0 until 3).map { "jpeg-$it".toByteArray() + ByteArray(16) },
            photoWidthsPx = listOf(1000, 1000, 1000),
            photoHeightsPx = listOf(1000, 1000, 1000),
            noteUtf8 = null,
            frontFingerprintBytes = CanonicalSiftFingerprintFixture.bytes(seed = 5),
            signingKeyset = identity.signingPrivateHandle,
            recipientEncryptionPublicKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(identity.encryptionPublicKeyset),
            expression = CapsuleExpressionArtifact(GeneratorBer1Provider.candidateIdFor(resolved), contentIds, resolved),
            trackSnapshot = snapshot,
            voice = voice,
        )
    }

    private fun open(prepared: PreparedOutboxCapsule) = PresentationAcceptanceGate().prepare(
        PresentationAcceptanceInput(
            expectedCapsuleId = CapsuleId(capsuleUuid),
            authenticatedUserId = UserId(userUuid),
            senderVerifyingKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(identity.signingPublicKeyset),
            expectedSenderKeyBundleId = KeyBundleId(bundleUuid),
            expectedSenderUserId = UserId(userUuid),
            expectedRecipientKeyBundleId = KeyBundleId(bundleUuid),
            envelopePlaintextBytes = RecipientEnvelopeCryptor().open(
                identity.encryptionPrivateHandle,
                RecipientEnvelopeContextInput(CapsuleId(capsuleUuid), UserId(userUuid), UserId(userUuid), KeyBundleId(bundleUuid)),
                prepared.envelopeCiphertext,
            ),
            statementBytes = prepared.publishStatementBytes,
            signature = prepared.publishStatementSignature,
            deliveredCiphertexts = prepared.artifacts.map { DeliveredCiphertext(BlobId(it.blobId), it.ciphertext) },
        ),
    )

    @Test
    fun aVoiceNoteIsTheOneMediaArtifactAndOpensToTheExactSound() {
        val prepared = publisher.publish(request("voice-plate", CapsuleVoiceNote(sound.copyOf(), 4200, peaks)))
        val media = prepared.artifacts.single { it.kind == OutboxArtifactKind.MEDIA }
        assertEquals(-1, media.ordinal)
        assertEquals(0x03.toByte(), BlobId(media.blobId).toProtoBytes().byteAt(0))
        assertEquals(OutboxArtifactKind.MEDIA, prepared.artifacts.last().kind)
        val result = open(prepared)
        val material = (result as? PresentationAcceptancePreparationResult.Prepared)?.material
            ?: throw AssertionError("expected Prepared, got $result")
        try {
            val voice = requireNotNull(material.voice())
            assertEquals(media.blobId, voice.blobId)
            assertEquals(4200, voice.durationMs)
            assertArrayEquals(peaks, voice.peaks)
            assertArrayEquals(sound, material.loadVoice())
        } finally {
            material.close()
        }
    }

    @Test
    fun aVoiceNoteNeedsAVoiceBandAndNoSong() {
        val voice = CapsuleVoiceNote(sound.copyOf(), 4200, peaks)
        val song = CapsuleTrackSnapshotV1.parse("be30e36b-1111-4111-8111-000000000001", "505", "Arctic Monkeys", null, null)
        assertThrows(IllegalArgumentException::class.java) { publisher.publish(request("plate", voice)) }
        assertThrows(IllegalArgumentException::class.java) { publisher.publish(request(null, voice)) }
        assertThrows(IllegalArgumentException::class.java) { publisher.publish(request("voice-card", null)) }
        assertThrows(IllegalArgumentException::class.java) { publisher.publish(request("voice-plate", voice, song)) }
        assertThrows(IllegalArgumentException::class.java) { CapsuleVoiceNote(ByteArray(16), 4200, peaks) }
        assertThrows(IllegalArgumentException::class.java) { CapsuleVoiceNote(sound.copyOf(), 60_001, peaks) }
    }
}
