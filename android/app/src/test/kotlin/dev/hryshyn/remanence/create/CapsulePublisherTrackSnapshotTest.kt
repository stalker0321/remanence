package dev.hryshyn.remanence.create

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.TinkProtoKeysetFormat
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.CapsuleArtifactCryptor
import dev.hryshyn.remanence.core.crypto.ContentManifestCodec
import dev.hryshyn.remanence.core.crypto.DeliveredCiphertext
import dev.hryshyn.remanence.core.crypto.PresentationAcceptanceGate
import dev.hryshyn.remanence.core.crypto.PresentationAcceptanceInput
import dev.hryshyn.remanence.core.crypto.PresentationAcceptancePreparationResult
import dev.hryshyn.remanence.core.crypto.RecipientEnvelopeCryptor
import dev.hryshyn.remanence.core.crypto.RecognitionManifestCodec
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.network.MusicTrackHit
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorBer1Provider
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S2b-sender: the sealed track snapshot rides the v2 manifest end to end
 * (publish -> envelope open -> statement gate -> manifest decrypt), while
 * v1+snapshot is refused before any crypto work and snapshot-less flows
 * are byte-identical to before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapsulePublisherTrackSnapshotTest {

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-sender-retry-${UUID.randomUUID()}"
    private lateinit var publisher: CapsulePublisher
    private val identity = AccountIdentityGenerator().generate()

    private val capsuleUuid = UUID.fromString("7b111111-2222-4333-8444-555555555555")
    private val userUuid = UUID.fromString("7b222222-3333-4444-8555-666666666666")
    private val bundleUuid = UUID.fromString("7b333333-4444-4555-8666-777777777777")

    private val contentIds = listOf("c0", "c1", "c2")

    private fun input() = GeneratorExpression.GeneratorInput(
        ownerId = userUuid.toString(),
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
        note = null,
        music = null,
    )

    private fun ber1(): GeneratorExpression.ResolvedExpression {
        val plan = GeneratorEditorialRows.plan(input())
        assertTrue("expected Planned, got $plan", plan is GeneratorEditorialRows.PlanResult.Planned)
        return (plan as GeneratorEditorialRows.PlanResult.Planned).expression
    }

    private fun snapshot() = CapsuleTrackSnapshotV1.parse(
        trackId = "be30e36b-1111-4111-8111-000000000001",
        title = "505",
        artistDisplay = "Arctic Monkeys",
        version = null,
        durationMs = 253000L,
    )

    @Before
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        testKekBoundary.createAes256GcmKey(testAlias)
        publisher = CapsulePublisher(SenderRetryKeysetWrapper(testKekBoundary), testAlias)
    }

    private fun request(
        expression: GeneratorExpression.ResolvedExpression?,
        snapshot: CapsuleTrackSnapshotV1?,
    ): CapsulePublishRequest {
        val candidateId = expression?.let { GeneratorBer1Provider.candidateIdFor(it) } ?: "unused"
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
            recipientEncryptionPublicKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
                identity.encryptionPublicKeyset,
            ),
            expression = expression?.let {
                CapsuleExpressionArtifact(
                    candidateId = candidateId,
                    contentIds = it.input.photos.map { photo -> photo.contentId },
                    expression = it,
                )
            },
            trackSnapshot = snapshot,
        )
    }

    @Test
    fun v2SnapshotSealsAndStatementBindingHolds() {
        val prepared = publisher.publish(request(ber1(), snapshot()))

        val opened = RecipientEnvelopeCryptor().open(
            identity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CapsuleId(capsuleUuid), UserId(userUuid), UserId(userUuid), KeyBundleId(bundleUuid),
            ),
            prepared.envelopeCiphertext,
        )
        val delivered = prepared.artifacts.map {
            DeliveredCiphertext(BlobId(it.blobId), it.ciphertext)
        }
        val result = PresentationAcceptanceGate().prepare(
            PresentationAcceptanceInput(
                expectedCapsuleId = CapsuleId(capsuleUuid),
                authenticatedUserId = UserId(userUuid),
                senderVerifyingKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
                    identity.signingPublicKeyset,
                ),
                expectedSenderKeyBundleId = KeyBundleId(bundleUuid),
                expectedSenderUserId = UserId(userUuid),
                expectedRecipientKeyBundleId = KeyBundleId(bundleUuid),
                envelopePlaintextBytes = opened,
                statementBytes = prepared.publishStatementBytes,
                signature = prepared.publishStatementSignature,
                deliveredCiphertexts = delivered,
            ),
        )
        val material = (result as? PresentationAcceptancePreparationResult.Prepared)?.material
            ?: throw AssertionError("expected Prepared, got $result")
        try {
            assertEquals(2, material.protocolVersion)
        } finally {
            material.close()
        }

        val content = prepared.artifacts.single {
            it.kind == dev.hryshyn.remanence.core.data.outbox.OutboxArtifactKind.CONTENT_MANIFEST
        }
        val capsuleKeyset = TinkProtoKeysetFormat.parseKeyset(
            dev.hryshyn.remanence.protocol.v1.RecipientEnvelopePlaintext.parseFrom(opened)
                .capsuleAeadKeyset.toByteArray(),
            InsecureSecretKeyAccess.get(),
        )
        val parsed = ContentManifestCodec().decryptAndParse(
            capsuleKeyset,
            RecognitionManifestCodec.RoutingContext(
                CapsuleId(capsuleUuid),
                BlobId(content.blobId),
                UserId(userUuid),
                UserId(userUuid),
            ),
            content.ciphertext,
        )
        assertEquals(UUID.fromString("be30e36b-1111-4111-8111-000000000001"), parsed.trackSnapshot?.trackId)
        assertEquals("505", parsed.trackSnapshot?.title)
        assertEquals("Arctic Monkeys", parsed.trackSnapshot?.artistDisplay)
        assertEquals(253000L, parsed.trackSnapshot?.durationMs)
    }

    @Test
    fun v1WithSnapshotIsRefusedBeforeAnyWork() {
        assertThrows(IllegalArgumentException::class.java) {
            publisher.publish(request(null, snapshot()))
        }
    }

    @Test
    fun snapshotLessV2ParsesNullSnapshot() {
        val prepared = publisher.publish(request(ber1(), null))
        val content = prepared.artifacts.single {
            it.kind == dev.hryshyn.remanence.core.data.outbox.OutboxArtifactKind.CONTENT_MANIFEST
        }
        val opened = RecipientEnvelopeCryptor().open(
            identity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CapsuleId(capsuleUuid), UserId(userUuid), UserId(userUuid), KeyBundleId(bundleUuid),
            ),
            prepared.envelopeCiphertext,
        )
        val capsuleKeyset = TinkProtoKeysetFormat.parseKeyset(
            dev.hryshyn.remanence.protocol.v1.RecipientEnvelopePlaintext.parseFrom(opened)
                .capsuleAeadKeyset.toByteArray(),
            InsecureSecretKeyAccess.get(),
        )
        val parsed = ContentManifestCodec().decryptAndParse(
            capsuleKeyset,
            RecognitionManifestCodec.RoutingContext(
                CapsuleId(capsuleUuid),
                BlobId(content.blobId),
                UserId(userUuid),
                UserId(userUuid),
            ),
            content.ciphertext,
        )
        assertEquals(2, parsed.protocolVersion)
        assertNull(parsed.trackSnapshot)
    }

    @Test
    fun mapperRejectsEmptyArtistsFailClosed() {
        val hit = MusicTrackHit(
            id = "be30e36b-1111-4111-8111-000000000001",
            title = "505",
            artists = emptyList(),
            version = null,
            release = null,
            year = null,
            durationMs = null,
            artworkAvailable = false,
        )
        assertThrows(IllegalArgumentException::class.java) {
            mapMusicSelectionToSnapshot(hit)
        }
    }
}
