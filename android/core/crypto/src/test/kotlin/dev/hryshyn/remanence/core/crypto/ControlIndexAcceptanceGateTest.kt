package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.core.model.ArtifactSlot
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.PublishArtifact
import dev.hryshyn.remanence.core.model.PublishStatementBuildResult
import dev.hryshyn.remanence.core.model.PublishStatementBuilder
import dev.hryshyn.remanence.core.model.PublishStatementInput
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.protocol.v1.PublishStatement
import dev.hryshyn.remanence.protocol.v1.RecipientEnvelopePlaintext
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M2-P11 control/index acceptance: verify that
 * [ControlIndexAcceptanceGate] accepts a valid envelope+statement+
 * recognition-ciphertext, propagates the [CanonicalControlVerifier]
 * rejection reasons unchanged, and rejects any AEAD or payload-level
 * mismatch without ever touching content/photo plaintext. The full
 * [CapsuleAcceptanceGate] tests in [CapsuleAcceptanceGateTest] and
 * [CapsuleAcceptanceGateCompositionTest] remain the authoritative
 * proof that the full gate is unchanged.
 */
class ControlIndexAcceptanceGateTest {

    private val indexGate = ControlIndexAcceptanceGate()
    private val fullGate = CapsuleAcceptanceGate()
    private val signer = PublishStatementSigner()
    private val codec = RecognitionManifestCodec()
    private val senderIdentity = AccountIdentityGenerator().generate()

    private val capsuleId = CapsuleId(UUID.fromString("1a111111-2222-4333-8444-555555555555"))
    private val senderUser = UserId(UUID.fromString("2a222222-3333-4444-8555-666666666666"))
    private val recipientUser = UserId(UUID.fromString("3a333333-4444-4555-8666-777777777777"))
    private val senderBundle = KeyBundleId(UUID.fromString("4a444444-5555-4666-8777-888888888888"))
    private val recipientBundle = KeyBundleId(UUID.fromString("5a555555-6666-4777-8888-999999999999"))

    private val recognitionBlobId = BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000001"))
    private val contentBlobId = BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000002"))
    private val photo1BlobId = BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000003"))
    private val photo2BlobId = BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000004"))
    private val photo3BlobId = BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000005"))

    private val front = ByteArray(96) { (it * 3).toByte() }
    private val back = ByteArray(96) { (it * 7 + 1).toByte() }

    private data class IndexCapsule(
        val keyset: KeysetHandle,
        val statementBytes: ByteArray,
        val signature: ByteArray,
        val envelopeBytes: ByteArray,
        val recognitionCiphertext: ByteArray,
    )

    @BeforeTest
    fun setUp() {
        TinkPrimitives.ensureRegistered()
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun artifacts(recognitionSize: Long, recognitionSha256: ByteArray): List<PublishArtifact> = listOf(
        PublishArtifact(
            slot = ArtifactSlot(recognitionBlobId, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1),
            ciphertextSize = recognitionSize,
            ciphertextSha256 = ByteString.copyFrom(recognitionSha256),
        ),
        PublishArtifact(
            slot = ArtifactSlot(contentBlobId, CapsuleArtifactKind.CONTENT_MANIFEST, -1),
            ciphertextSize = 200L,
            ciphertextSha256 = ByteString.copyFrom(sha256("content".toByteArray())),
        ),
        PublishArtifact(
            slot = ArtifactSlot(photo1BlobId, CapsuleArtifactKind.PHOTO, 0),
            ciphertextSize = 300L,
            ciphertextSha256 = ByteString.copyFrom(sha256("p1".toByteArray())),
        ),
        PublishArtifact(
            slot = ArtifactSlot(photo2BlobId, CapsuleArtifactKind.PHOTO, 1),
            ciphertextSize = 301L,
            ciphertextSha256 = ByteString.copyFrom(sha256("p2".toByteArray())),
        ),
        PublishArtifact(
            slot = ArtifactSlot(photo3BlobId, CapsuleArtifactKind.PHOTO, 2),
            ciphertextSize = 302L,
            ciphertextSha256 = ByteString.copyFrom(sha256("p3".toByteArray())),
        ),
    )

    private fun serializeKeyset(keyset: KeysetHandle): ByteArray =
        TinkProtoKeysetFormat.serializeKeyset(keyset, InsecureSecretKeyAccess.get())

    private fun envelopeFor(statementBytes: ByteArray, keysetBytes: ByteArray, statementHash: ByteArray): ByteArray {
        val statement = PublishStatement.parseFrom(statementBytes)
        return RecipientEnvelopePlaintext.newBuilder()
            .setProtocolVersion(1)
            .setCapsuleId(statement.capsuleId)
            .setSenderUserId(statement.senderUserId)
            .setRecipientUserId(statement.recipientUserId)
            .setSenderKeyBundleId(statement.senderKeyBundleId)
            .setRecipientKeyBundleId(statement.recipientKeyBundleId)
            .setCapsuleAeadKeyset(ByteString.copyFrom(keysetBytes))
            .setPublishStatementSha256(ByteString.copyFrom(statementHash))
            .build()
            .toByteArray()
    }

    private fun buildIndexCapsule(
        keyset: KeysetHandle = CapsuleKeysetGenerator().generate(),
        frontFingerprint: ByteArray = front,
        backFingerprint: ByteArray = back,
        handleSnapshot: String = "mykola",
        createdAtEpochSeconds: Long = 1_700_000_000L,
        placeLabel: String? = null,
    ): IndexCapsule {
        val routing = RecognitionManifestCodec.RoutingContext(
            capsuleId = capsuleId,
            blobId = recognitionBlobId,
            senderUserId = senderUser,
            recipientUserId = recipientUser,
        )
        val recognitionCiphertext = codec.buildAndEncrypt(
            capsuleKeyset = keyset,
            routingContext = routing,
            senderHandleSnapshot = handleSnapshot,
            createdAtEpochSeconds = createdAtEpochSeconds,
            placeLabel = placeLabel,
            frontFingerprint = frontFingerprint,
            backFingerprint = backFingerprint,
        )
        val recognitionSize = recognitionCiphertext.size.toLong()
        val recognitionSha = sha256(recognitionCiphertext)
        val keysetBytes = serializeKeyset(keyset)

        val success = PublishStatementBuilder.build(
            PublishStatementInput(
                capsuleId, senderUser, recipientUser, senderBundle, recipientBundle,
                createdAtEpochSeconds, artifacts(recognitionSize, recognitionSha),
            ),
        ) as PublishStatementBuildResult.Success
        val bytes = success.deterministicBytes.toByteArray()
        val signed = signer.sign(senderIdentity.signingPrivateHandle, bytes)
        return IndexCapsule(
            keyset = keyset,
            statementBytes = bytes,
            signature = signed.signature,
            envelopeBytes = envelopeFor(bytes, keysetBytes, sha256(bytes)),
            recognitionCiphertext = recognitionCiphertext,
        )
    }

    private fun senderVerifyingKeyset() =
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(senderIdentity.signingPublicKeyset)

    private fun indexInput(
        capsule: IndexCapsule,
        recognitionBlobIdOverride: BlobId = recognitionBlobId,
        recognitionCiphertextOverride: ByteArray? = null,
        envelopeOverride: ByteArray? = null,
        statementOverride: ByteArray? = null,
        signatureOverride: ByteArray? = null,
        expectedCapsule: CapsuleId = capsuleId,
        authenticatedUser: UserId = recipientUser,
        expectedSenderBundle: KeyBundleId = senderBundle,
    ): ControlIndexAcceptanceInput = ControlIndexAcceptanceInput(
        expectedCapsuleId = expectedCapsule,
        authenticatedUserId = authenticatedUser,
        senderVerifyingKeyset = senderVerifyingKeyset(),
        expectedSenderKeyBundleId = expectedSenderBundle,
        envelopePlaintextBytes = envelopeOverride ?: capsule.envelopeBytes,
        statementBytes = statementOverride ?: capsule.statementBytes,
        signature = signatureOverride ?: capsule.signature,
        recognitionBlobId = recognitionBlobIdOverride,
        recognitionCiphertext = recognitionCiphertextOverride ?: capsule.recognitionCiphertext,
    )

    private fun rejected(result: ControlIndexAcceptanceResult): RejectionReason =
        assertIs<ControlIndexAcceptanceResult.Rejected>(result).reason

    @Test
    fun validIndexAcceptanceSucceeds() {
        val capsule = buildIndexCapsule()
        val result = indexGate.verify(indexInput(capsule))

        val verified = assertIs<ControlIndexAcceptanceResult.Verified>(result)
        assertEquals(5, verified.statement.artifactsCount)
        assertEquals(1, verified.recognition.protocolVersion)
        assertContentEquals(capsuleId.toProtoBytes().toByteArray(), verified.recognition.capsuleIdRaw)
        assertContentEquals(front, verified.recognition.frontFingerprint)
        assertContentEquals(back, verified.recognition.backFingerprint)
        assertEquals("mykola", verified.recognition.senderHandleSnapshot)
    }

    @Test
    fun validIndexAcceptanceIgnoresAbsentContentAndPhotos() {
        val capsule = buildIndexCapsule()
        val result = indexGate.verify(indexInput(capsule))
        val verified = assertIs<ControlIndexAcceptanceResult.Verified>(result)
        assertEquals(5, verified.statement.artifactsCount)
    }

    @Test
    fun wrongRecipientFailsAadIntegrityBeforeSemanticCheck() {
        val capsule = buildIndexCapsule()
        val result = indexGate.verify(indexInput(capsule, authenticatedUser = UserId(UUID.fromString("8a888888-8888-4888-8888-888888888888"))))
        assertEquals(RejectionReason.ID_MISMATCH, rejected(result))
    }

    @Test
    fun tamperedRecognitionCiphertextFailsAad() {
        val capsule = buildIndexCapsule()
        val tampered = capsule.recognitionCiphertext.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        val result = indexGate.verify(indexInput(capsule, recognitionCiphertextOverride = tampered))
        assertEquals(RejectionReason.RECOGNITION_BINDING_INVALID, rejected(result))
    }

    @Test
    fun wrongRecognitionBlobIdFailsBindingCheck() {
        val capsule = buildIndexCapsule()
        val wrongId = BlobId(UUID.fromString("9a999999-9999-4999-8999-999999999999"))
        val result = indexGate.verify(indexInput(capsule, recognitionBlobIdOverride = wrongId))
        assertEquals(RejectionReason.RECOGNITION_BINDING_INVALID, rejected(result))
    }

    @Test
    fun garbageRecognitionCiphertextFailsBindingCheck() {
        val capsule = buildIndexCapsule()
        val garbage = ByteArray(64) { 0x55 }
        val result = indexGate.verify(indexInput(capsule, recognitionCiphertextOverride = garbage))
        assertEquals(RejectionReason.RECOGNITION_BINDING_INVALID, rejected(result))
    }

    @Test
    fun wrongCapsuleKeysetInEnvelopeFailsAad() {
        val capsule = buildIndexCapsule()
        val otherKeysetBytes = serializeKeyset(CapsuleKeysetGenerator().generate())
        val envelope = envelopeFor(capsule.statementBytes, otherKeysetBytes, sha256(capsule.statementBytes))
        val result = indexGate.verify(indexInput(capsule, envelopeOverride = envelope))
        assertEquals(RejectionReason.RECOGNITION_AEAD_INVALID, rejected(result))
    }

    @Test
    fun tamperedRecognitionCiphertextRemainsAeadInvalid() {
        val capsule = buildIndexCapsule()
        val tamperedCiphertext = capsule.recognitionCiphertext.copyOf().also { ciphertext ->
            ciphertext[ciphertext.lastIndex] = (ciphertext[ciphertext.lastIndex].toInt() xor 1).toByte()
        }
        val originalStatement = PublishStatement.parseFrom(capsule.statementBytes)
        val forgedStatement = originalStatement.toBuilder()
            .setArtifacts(
                0,
                originalStatement.getArtifacts(0).toBuilder()
                    .setCiphertextSize(tamperedCiphertext.size.toLong())
                    .setCiphertextSha256(ByteString.copyFrom(sha256(tamperedCiphertext)))
                    .build(),
            )
            .build()
        val statementBytes = deterministicBytes(forgedStatement)
        val signed = signer.sign(senderIdentity.signingPrivateHandle, statementBytes)
        val envelope = envelopeFor(statementBytes, serializeKeyset(capsule.keyset), sha256(statementBytes))
        val result = indexGate.verify(
            indexInput(
                capsule,
                recognitionCiphertextOverride = tamperedCiphertext,
                envelopeOverride = envelope,
                statementOverride = statementBytes,
                signatureOverride = signed.signature,
            ),
        )

        assertEquals(RejectionReason.RECOGNITION_AEAD_INVALID, rejected(result))
    }

    @Test
    fun malformedInnerManifestFailsPayload() {
        val capsule = buildIndexCapsuleWithMalformedPlaintext()
        val result = indexGate.verify(indexInput(capsule))
        assertEquals(RejectionReason.RECOGNITION_PAYLOAD_INVALID, rejected(result))
    }

    private fun buildIndexCapsuleWithMalformedPlaintext(): IndexCapsule {
        val keyset = CapsuleKeysetGenerator().generate()
        val routing = RecognitionManifestCodec.RoutingContext(
            capsuleId = capsuleId,
            blobId = recognitionBlobId,
            senderUserId = senderUser,
            recipientUserId = recipientUser,
        )
        val garbagePlaintext = byteArrayOf(0x0a, 0x80.toByte())
        val recognitionCiphertext = CapsuleArtifactCryptor().encrypt(
            capsuleKeyset = keyset,
            context = dev.hryshyn.remanence.core.model.ArtifactAadInput(
                capsuleId = routing.capsuleId,
                blobId = routing.blobId,
                artifactKind = CapsuleArtifactKind.RECOGNITION_MANIFEST,
                ordinal = -1,
                senderUserId = routing.senderUserId,
                recipientUserId = routing.recipientUserId,
            ),
            plaintext = garbagePlaintext,
        )
        val recognitionSize = recognitionCiphertext.size.toLong()
        val recognitionSha = sha256(recognitionCiphertext)
        val keysetBytes = serializeKeyset(keyset)

        val success = PublishStatementBuilder.build(
            PublishStatementInput(
                capsuleId, senderUser, recipientUser, senderBundle, recipientBundle,
                1_700_000_000L, artifacts(recognitionSize, recognitionSha),
            ),
        ) as PublishStatementBuildResult.Success
        val bytes = success.deterministicBytes.toByteArray()
        val signed = signer.sign(senderIdentity.signingPrivateHandle, bytes)
        return IndexCapsule(
            keyset = keyset,
            statementBytes = bytes,
            signature = signed.signature,
            envelopeBytes = envelopeFor(bytes, keysetBytes, sha256(bytes)),
            recognitionCiphertext = recognitionCiphertext,
        )
    }

    @Test
    fun emptyFrontFingerprintFailsPayload() {
        val capsule = buildIndexCapsuleWithForgedManifest(
            frontFingerprint = ByteArray(0),
            backFingerprint = back,
        )
        val result = indexGate.verify(indexInput(capsule))
        assertEquals(RejectionReason.RECOGNITION_PAYLOAD_INVALID, rejected(result))
    }

    @Test
    fun emptyBackFingerprintFailsPayload() {
        val capsule = buildIndexCapsuleWithForgedManifest(
            frontFingerprint = front,
            backFingerprint = ByteArray(0),
        )
        val result = indexGate.verify(indexInput(capsule))
        assertEquals(RejectionReason.RECOGNITION_PAYLOAD_INVALID, rejected(result))
    }

    private fun buildIndexCapsuleWithForgedManifest(
        keyset: KeysetHandle = CapsuleKeysetGenerator().generate(),
        frontFingerprint: ByteArray = front,
        backFingerprint: ByteArray = back,
    ): IndexCapsule {
        val routing = RecognitionManifestCodec.RoutingContext(
            capsuleId = capsuleId,
            blobId = recognitionBlobId,
            senderUserId = senderUser,
            recipientUserId = recipientUser,
        )
        val hint = dev.hryshyn.remanence.protocol.v1.ChooserHint.newBuilder()
            .setSenderHandleSnapshot("mykola")
            .setCreatedAtEpochSeconds(1_700_000_000L)
            .build()
        val manifest = dev.hryshyn.remanence.protocol.v1.RecognitionManifest.newBuilder()
            .setProtocolVersion(1)
            .setCapsuleId(routing.capsuleIdProto())
            .setChooserHint(hint)
            .setFrontFingerprint(ByteString.copyFrom(frontFingerprint))
            .setBackFingerprint(ByteString.copyFrom(backFingerprint))
            .build()
        val recognitionCiphertext = CapsuleArtifactCryptor().encrypt(
            capsuleKeyset = keyset,
            context = dev.hryshyn.remanence.core.model.ArtifactAadInput(
                capsuleId = routing.capsuleId,
                blobId = routing.blobId,
                artifactKind = CapsuleArtifactKind.RECOGNITION_MANIFEST,
                ordinal = -1,
                senderUserId = routing.senderUserId,
                recipientUserId = routing.recipientUserId,
            ),
            plaintext = manifest.toByteArray(),
        )
        val recognitionSize = recognitionCiphertext.size.toLong()
        val recognitionSha = sha256(recognitionCiphertext)
        val keysetBytes = serializeKeyset(keyset)

        val success = PublishStatementBuilder.build(
            PublishStatementInput(
                capsuleId, senderUser, recipientUser, senderBundle, recipientBundle,
                1_700_000_000L, artifacts(recognitionSize, recognitionSha),
            ),
        ) as PublishStatementBuildResult.Success
        val bytes = success.deterministicBytes.toByteArray()
        val signed = signer.sign(senderIdentity.signingPrivateHandle, bytes)
        return IndexCapsule(
            keyset = keyset,
            statementBytes = bytes,
            signature = signed.signature,
            envelopeBytes = envelopeFor(bytes, keysetBytes, sha256(bytes)),
            recognitionCiphertext = recognitionCiphertext,
        )
    }

    @Test
    fun innerCapsuleIdMismatchFailsPayload() {
        val forgedKeyset = CapsuleKeysetGenerator().generate()
        val routing = RecognitionManifestCodec.RoutingContext(
            capsuleId = capsuleId,
            blobId = recognitionBlobId,
            senderUserId = senderUser,
            recipientUserId = recipientUser,
        )
        val hint = dev.hryshyn.remanence.protocol.v1.ChooserHint.newBuilder()
            .setSenderHandleSnapshot("mykola")
            .setCreatedAtEpochSeconds(1_700_000_000L)
            .build()
        val otherCapsuleId = CapsuleId(UUID.fromString("9a999999-9999-4999-8999-999999999999"))
        val manifest = dev.hryshyn.remanence.protocol.v1.RecognitionManifest.newBuilder()
            .setProtocolVersion(1)
            .setCapsuleId(otherCapsuleId.toProtoBytes())
            .setChooserHint(hint)
            .setFrontFingerprint(ByteString.copyFrom(front))
            .setBackFingerprint(ByteString.copyFrom(back))
            .build()
        val forgedCiphertext = CapsuleArtifactCryptor().encrypt(
            capsuleKeyset = forgedKeyset,
            context = dev.hryshyn.remanence.core.model.ArtifactAadInput(
                capsuleId = routing.capsuleId,
                blobId = routing.blobId,
                artifactKind = CapsuleArtifactKind.RECOGNITION_MANIFEST,
                ordinal = -1,
                senderUserId = routing.senderUserId,
                recipientUserId = routing.recipientUserId,
            ),
            plaintext = manifest.toByteArray(),
        )
        val forgedSize = forgedCiphertext.size.toLong()
        val forgedSha = sha256(forgedCiphertext)

        val success = PublishStatementBuilder.build(
            PublishStatementInput(
                capsuleId, senderUser, recipientUser, senderBundle, recipientBundle,
                1_700_000_000L, listOf(
                    PublishArtifact(
                        slot = ArtifactSlot(recognitionBlobId, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1),
                        ciphertextSize = forgedSize,
                        ciphertextSha256 = ByteString.copyFrom(forgedSha),
                    ),
                    PublishArtifact(
                        slot = ArtifactSlot(contentBlobId, CapsuleArtifactKind.CONTENT_MANIFEST, -1),
                        ciphertextSize = 200L,
                        ciphertextSha256 = ByteString.copyFrom(sha256("content".toByteArray())),
                    ),
                    PublishArtifact(
                        slot = ArtifactSlot(photo1BlobId, CapsuleArtifactKind.PHOTO, 0),
                        ciphertextSize = 300L,
                        ciphertextSha256 = ByteString.copyFrom(sha256("p1".toByteArray())),
                    ),
                    PublishArtifact(
                        slot = ArtifactSlot(photo2BlobId, CapsuleArtifactKind.PHOTO, 1),
                        ciphertextSize = 301L,
                        ciphertextSha256 = ByteString.copyFrom(sha256("p2".toByteArray())),
                    ),
                    PublishArtifact(
                        slot = ArtifactSlot(photo3BlobId, CapsuleArtifactKind.PHOTO, 2),
                        ciphertextSize = 302L,
                        ciphertextSha256 = ByteString.copyFrom(sha256("p3".toByteArray())),
                    ),
                ),
            ),
        ) as PublishStatementBuildResult.Success
        val bytes = success.deterministicBytes.toByteArray()
        val signed = signer.sign(senderIdentity.signingPrivateHandle, bytes)
        val envelope = envelopeFor(bytes, serializeKeyset(forgedKeyset), sha256(bytes))
        val input = ControlIndexAcceptanceInput(
            expectedCapsuleId = capsuleId,
            authenticatedUserId = recipientUser,
            senderVerifyingKeyset = senderVerifyingKeyset(),
            expectedSenderKeyBundleId = senderBundle,
            envelopePlaintextBytes = envelope,
            statementBytes = bytes,
            signature = signed.signature,
            recognitionBlobId = recognitionBlobId,
            recognitionCiphertext = forgedCiphertext,
        )
        val result = indexGate.verify(input)
        assertEquals(RejectionReason.RECOGNITION_PAYLOAD_INVALID, rejected(result))
    }

    @Test
    fun missingCanonicalControlRejectionsArePropagatedUnchanged() {
        val capsule = buildIndexCapsule()
        val malformedEnvelope = ByteArray(0)
        val result = indexGate.verify(indexInput(capsule, envelopeOverride = malformedEnvelope))
        assertEquals(RejectionReason.MALFORMED_ENVELOPE, rejected(result))
    }

    @Test
    fun fullCapsuleAcceptanceGateBehaviorIsUnchanged() {
        val capsule = buildIndexCapsule()
        val fullInput = CapsuleAcceptanceInput(
            expectedCapsuleId = capsuleId,
            authenticatedUserId = recipientUser,
            senderVerifyingKeyset = senderVerifyingKeyset(),
            expectedSenderKeyBundleId = senderBundle,
            envelopePlaintextBytes = capsule.envelopeBytes,
            statementBytes = capsule.statementBytes,
            signature = capsule.signature,
            deliveredBlobs = listOf(
                DeliveredBlob(
                    blobId = recognitionBlobId,
                    ciphertextSize = capsule.recognitionCiphertext.size.toLong(),
                    ciphertextSha256 = sha256(capsule.recognitionCiphertext),
                ),
                DeliveredBlob(
                    blobId = contentBlobId,
                    ciphertextSize = 200L,
                    ciphertextSha256 = sha256("content".toByteArray()),
                ),
                DeliveredBlob(
                    blobId = photo1BlobId,
                    ciphertextSize = 300L,
                    ciphertextSha256 = sha256("p1".toByteArray()),
                ),
                DeliveredBlob(
                    blobId = photo2BlobId,
                    ciphertextSize = 301L,
                    ciphertextSha256 = sha256("p2".toByteArray()),
                ),
                DeliveredBlob(
                    blobId = photo3BlobId,
                    ciphertextSize = 302L,
                    ciphertextSha256 = sha256("p3".toByteArray()),
                ),
            ),
        )
        val accepted = assertIs<CapsuleAcceptanceResult.Accepted>(fullGate.verify(fullInput))
        assertEquals(5, accepted.statement.artifactsCount)
    }

    @Test
    fun fullCapsuleAcceptanceGateStillRejectsWhenRecognitionOnly() {
        val capsule = buildIndexCapsule()
        val fullInput = CapsuleAcceptanceInput(
            expectedCapsuleId = capsuleId,
            authenticatedUserId = recipientUser,
            senderVerifyingKeyset = senderVerifyingKeyset(),
            expectedSenderKeyBundleId = senderBundle,
            envelopePlaintextBytes = capsule.envelopeBytes,
            statementBytes = capsule.statementBytes,
            signature = capsule.signature,
            deliveredBlobs = listOf(
                DeliveredBlob(
                    blobId = recognitionBlobId,
                    ciphertextSize = capsule.recognitionCiphertext.size.toLong(),
                    ciphertextSha256 = sha256(capsule.recognitionCiphertext),
                ),
            ),
        )
        val result = fullGate.verify(fullInput)
        assertEquals(RejectionReason.BLOB_SUBSTITUTION, (result as CapsuleAcceptanceResult.Rejected).reason)
    }

    @Test
    fun indexGateReturnsEqualOrDistinctInstance() {
        val capsule = buildIndexCapsule()
        val a = indexGate.verify(indexInput(capsule))
        val b = indexGate.verify(indexInput(capsule))
        val av = assertIs<ControlIndexAcceptanceResult.Verified>(a)
        val bv = assertIs<ControlIndexAcceptanceResult.Verified>(b)
        assertTrue(av.recognition.capsuleIdRaw.contentEquals(bv.recognition.capsuleIdRaw))
    }

    /**
     * CRITICAL: a second AEAD-valid ciphertext under the same key/AAD with
     * different plaintext must be rejected by the binding check, because
     * its transport identity (size, SHA-256) does not match the signed
     * binding. The size/SHA-256 are now derived from the actual bytes
     * the caller holds, so copying signed metadata while swapping the
     * ciphertext is no longer enough to pass the binding check.
     */
    @Test
    fun aeadValidAltCiphertextWithDifferentPlaintextRejectsBinding() {
        val capsule = buildIndexCapsule()
        val altManifest = dev.hryshyn.remanence.protocol.v1.RecognitionManifest.newBuilder()
            .setProtocolVersion(1)
            .setCapsuleId(capsuleId.toProtoBytes())
            .setChooserHint(
                dev.hryshyn.remanence.protocol.v1.ChooserHint.newBuilder()
                    .setSenderHandleSnapshot("attacker")
                    .setCreatedAtEpochSeconds(1_700_000_001L)
                    .build(),
            )
            .setFrontFingerprint(ByteString.copyFrom(ByteArray(64) { 0x42 }))
            .setBackFingerprint(ByteString.copyFrom(ByteArray(64) { 0x43 }))
            .build()
        val altCiphertext = CapsuleArtifactCryptor().encrypt(
            capsuleKeyset = capsule.keyset,
            context = dev.hryshyn.remanence.core.model.ArtifactAadInput(
                capsuleId = capsuleId,
                blobId = recognitionBlobId,
                artifactKind = CapsuleArtifactKind.RECOGNITION_MANIFEST,
                ordinal = -1,
                senderUserId = senderUser,
                recipientUserId = recipientUser,
            ),
            plaintext = altManifest.toByteArray(),
        )
        val result = indexGate.verify(indexInput(capsule, recognitionCiphertextOverride = altCiphertext))
        assertEquals(RejectionReason.RECOGNITION_BINDING_INVALID, rejected(result))
    }

    /**
     * CRITICAL: the canonical control verifier agrees envelope/statement
     * bytes without enforcing 16-byte length on every typed ID. The
     * signed sender_user_id could be malformed, in which case building
     * the routing context would throw. The gate must catch that and
     * return Rejected, never Verified.
     */
    @Test
    fun malformedSignedSenderIdAlwaysRejects() {
        val capsule = buildIndexCapsule()
        val parsed = PublishStatement.parseFrom(capsule.statementBytes)
        val malformedSender = ByteString.copyFrom(ByteArray(15) { 0x0a })
        val forged = parsed.toBuilder().setSenderUserId(malformedSender).build()
        val bytes = deterministicBytes(forged)
        val signed = signer.sign(senderIdentity.signingPrivateHandle, bytes)
        val envelopeBytes = envelopeFor(bytes, serializeKeyset(capsule.keyset), sha256(bytes))
        val result = indexGate.verify(
            indexInput(
                capsule,
                statementOverride = bytes,
                signatureOverride = signed.signature,
                envelopeOverride = envelopeBytes,
            ),
        )
        assertIs<ControlIndexAcceptanceResult.Rejected>(result)
    }

    private fun deterministicBytes(statement: PublishStatement): ByteArray {
        val bytes = ByteArray(statement.serializedSize)
        val out = com.google.protobuf.CodedOutputStream.newInstance(bytes)
        out.useDeterministicSerialization()
        statement.writeTo(out)
        out.flush()
        out.checkNoSpaceLeft()
        return bytes
    }
}
