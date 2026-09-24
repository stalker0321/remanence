package dev.hryshyn.remanence.create

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.TinkProtoKeysetFormat
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.CapsuleArtifactCryptor
import dev.hryshyn.remanence.core.crypto.ContentManifestCodec
import dev.hryshyn.remanence.core.crypto.DeliveredCiphertext
import dev.hryshyn.remanence.core.crypto.ExpressionReceiverAdmission
import dev.hryshyn.remanence.core.crypto.PresentationAcceptanceGate
import dev.hryshyn.remanence.core.crypto.PresentationAcceptanceInput
import dev.hryshyn.remanence.core.crypto.PresentationAcceptancePreparationResult
import dev.hryshyn.remanence.core.crypto.RecipientEnvelopeCryptor
import dev.hryshyn.remanence.core.crypto.RecognitionManifestCodec
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.model.ArtifactAadInput
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.CapsulePhotoIdentity
import dev.hryshyn.remanence.core.model.GeneratorBer1Provider
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.protocol.v1.RecipientEnvelopePlaintext
import dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-018 publish→receive proof: the sender seals a real BER1 v2 expression
 * (the same `CapsuleExpressionArtifact` the CreateViewModel gate produces) and
 * the receiver's `ContentManifestCodec` + `ExpressionReceiverAdmission` admit
 * exactly that expression, with the `BEXPR01` projection hash recomputed by
 * the receiver equal to the sender's frozen `CapsulePhotoIdentity.projectedHash`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapsulePublisherExpressionRoundTripTest {

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-sender-retry-${UUID.randomUUID()}"
    private lateinit var publisher: CapsulePublisher
    private val identity = AccountIdentityGenerator().generate()

    private val capsuleUuid = UUID.fromString("7a111111-2222-4333-8444-555555555555")
    private val userUuid = UUID.fromString("7a222222-3333-4444-8555-666666666666")
    private val bundleUuid = UUID.fromString("7a333333-4444-4555-8666-777777777777")

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

    @Before
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        testKekBoundary.createAes256GcmKey(testAlias)
        publisher = CapsulePublisher(SenderRetryKeysetWrapper(testKekBoundary), testAlias)
    }

    private fun request(expression: GeneratorExpression.ResolvedExpression): CapsulePublishRequest {
        val candidateId = GeneratorBer1Provider.candidateIdFor(expression)
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
            expression = CapsuleExpressionArtifact(
                candidateId = candidateId,
                contentIds = expression.input.photos.map { it.contentId },
                expression = expression,
            ),
        )
    }

    @Test
    fun publishedV2ExpressionIsAdmittedWithExactProjectionHash() {
        val expression = ber1()
        val prepared = publisher.publish(request(expression))

        val content = prepared.artifacts.single { it.kind == dev.hryshyn.remanence.core.data.outbox.OutboxArtifactKind.CONTENT_MANIFEST }
        val opened = RecipientEnvelopeCryptor().open(
            identity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CapsuleId(capsuleUuid), UserId(userUuid), UserId(userUuid), KeyBundleId(bundleUuid),
            ),
            prepared.envelopeCiphertext,
        )
        val capsuleKeyset = TinkProtoKeysetFormat.parseKeyset(
            RecipientEnvelopePlaintext.parseFrom(opened).capsuleAeadKeyset.toByteArray(),
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
        val admitted = ExpressionReceiverAdmission.admit(parsed)
        assertTrue("expected Supported, got $admitted", admitted is ExpressionReceiverAdmission.Result.Supported)
        val receiverExpression = (admitted as ExpressionReceiverAdmission.Result.Supported).expression

        // The receiver reconstructs the exact frozen geometry + source bindings.
        assertEquals(expression.placements, receiverExpression.placements)
        assertEquals(expression.input.photos, receiverExpression.input.photos)
        assertEquals(
            expression.placements.map { it.contentRect },
            receiverExpression.placements.map { it.contentRect },
        )

        // The receiver-recomputable projection hash equals the sender's
        // original-descriptor `CapsulePhotoIdentity.projectedHash`.
        val candidateId = GeneratorBer1Provider.candidateIdFor(expression)
        val expectedProjection = CapsulePhotoIdentity.projectedHash(
            expression = expression,
            candidateId = candidateId,
            capsuleId = CapsuleId(capsuleUuid),
            contentIds = expression.input.photos.map { it.contentId },
        )
        assertEquals(expectedProjection, parsed.expression!!.projectionHash)
    }

    @Test
    fun presentationGatePreparesMaterialThatAdmitsTheExpression() {
        val expression = ber1()
        val prepared = publisher.publish(request(expression))

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
            val admitted = material.expressionAdmission()
            assertTrue("expected Supported, got $admitted", admitted is ExpressionReceiverAdmission.Result.Supported)
            assertEquals(
                expression.placements,
                (admitted as ExpressionReceiverAdmission.Result.Supported).expression.placements,
            )
        } finally {
            material.close()
        }
    }

    @Test
    fun v1PublicationCarriesNoExpressionAndAdmitsOnlyTheLegacyPath() {
        val v1 = request(ber1()).copy(expression = null)
        val prepared = publisher.publish(v1)
        val content = prepared.artifacts.single { it.kind == dev.hryshyn.remanence.core.data.outbox.OutboxArtifactKind.CONTENT_MANIFEST }
        val opened = RecipientEnvelopeCryptor().open(
            identity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CapsuleId(capsuleUuid), UserId(userUuid), UserId(userUuid), KeyBundleId(bundleUuid),
            ),
            prepared.envelopeCiphertext,
        )
        val capsuleKeyset = TinkProtoKeysetFormat.parseKeyset(
            RecipientEnvelopePlaintext.parseFrom(opened).capsuleAeadKeyset.toByteArray(),
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
        assertEquals(1, parsed.protocolVersion)
        assertTrue(parsed.expression == null)
        assertTrue(
            ExpressionReceiverAdmission.admit(parsed) is ExpressionReceiverAdmission.Result.Unsupported,
        )
    }

    @Test
    fun v1ManifestBytesWithExpressionFieldRejectedAtTheReceiver() {
        // Defence-in-depth at the receiver: a v1 frame carrying field 6 is a
        // version-confusion attempt and must fail closed, never drop it.
        val expression = ber1()
        val prepared = publisher.publish(request(expression))
        val content = prepared.artifacts.single { it.kind == dev.hryshyn.remanence.core.data.outbox.OutboxArtifactKind.CONTENT_MANIFEST }
        val opened = RecipientEnvelopeCryptor().open(
            identity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CapsuleId(capsuleUuid), UserId(userUuid), UserId(userUuid), KeyBundleId(bundleUuid),
            ),
            prepared.envelopeCiphertext,
        )
        val capsuleKeyset = TinkProtoKeysetFormat.parseKeyset(
            RecipientEnvelopePlaintext.parseFrom(opened).capsuleAeadKeyset.toByteArray(),
            InsecureSecretKeyAccess.get(),
        )
        val routing = RecognitionManifestCodec.RoutingContext(
            CapsuleId(capsuleUuid),
            BlobId(content.blobId),
            UserId(userUuid),
            UserId(userUuid),
        )
        val aad = ArtifactAadInput(
            capsuleId = CapsuleId(capsuleUuid),
            blobId = BlobId(content.blobId),
            artifactKind = CapsuleArtifactKind.CONTENT_MANIFEST,
            ordinal = -1,
            senderUserId = UserId(userUuid),
            recipientUserId = UserId(userUuid),
        )
        val plaintext = CapsuleArtifactCryptor().decrypt(capsuleKeyset, aad, content.ciphertext)
        val mixed = dev.hryshyn.remanence.protocol.v1.ContentManifest.parseFrom(plaintext)
            .toBuilder()
            .setProtocolVersion(1)
            .build()
        val mixedCiphertext = CapsuleArtifactCryptor().encrypt(capsuleKeyset, aad, mixed.toByteArray())
        val rejected = runCatching {
            ContentManifestCodec().decryptAndParse(capsuleKeyset, routing, mixedCiphertext)
        }
        assertTrue("v1+field6 must fail closed, got $rejected", rejected.isFailure)
    }
}
