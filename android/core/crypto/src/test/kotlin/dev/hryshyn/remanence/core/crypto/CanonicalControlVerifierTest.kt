package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeyTemplates
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * M2-P10 focused delegation tests: every rejection reason that the
 * [CapsuleAcceptanceGate] used to compute before seeing any delivered
 * blobs is now produced by [CanonicalControlVerifier] with the same
 * ordered checks. This test pins the delegation without changing any
 * existing acceptance decision.
 */
class CanonicalControlVerifierTest {

    private val verifier = CanonicalControlVerifier()
    private val signer = PublishStatementSigner()
    private val senderIdentity = AccountIdentityGenerator().generate()

    private val capsuleId = CapsuleId(UUID.fromString("1a111111-2222-4333-8444-555555555555"))
    private val senderUser = UserId(UUID.fromString("2a222222-3333-4444-8555-666666666666"))
    private val recipientUser = UserId(UUID.fromString("3a333333-4444-4555-8666-777777777777"))
    private val senderBundle = KeyBundleId(UUID.fromString("4a444444-5555-4666-8777-888888888888"))
    private val recipientBundle = KeyBundleId(UUID.fromString("5a555555-6666-4777-8888-999999999999"))

    private data class SealedCapsule(
        val statementBytes: ByteArray,
        val signature: ByteArray,
        val envelopeBytes: ByteArray,
        val blobs: List<DeliveredBlob>,
    )

    @BeforeTest
    fun setUp() {
        TinkPrimitives.ensureRegistered()
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun artifacts(): List<PublishArtifact> {
        val slots = listOf(
            Triple(CapsuleArtifactKind.RECOGNITION_MANIFEST, -1, "a1000000-0000-4000-8000-000000000001"),
            Triple(CapsuleArtifactKind.CONTENT_MANIFEST, -1, "a1000000-0000-4000-8000-000000000002"),
            Triple(CapsuleArtifactKind.PHOTO, 0, "a1000000-0000-4000-8000-000000000003"),
            Triple(CapsuleArtifactKind.PHOTO, 1, "a1000000-0000-4000-8000-000000000004"),
            Triple(CapsuleArtifactKind.PHOTO, 2, "a1000000-0000-4000-8000-000000000005"),
        )
        return slots.mapIndexed { index, (kind, ordinal, blob) ->
            PublishArtifact(
                slot = ArtifactSlot(BlobId(UUID.fromString(blob)), kind, ordinal),
                ciphertextSize = 100L + index,
                ciphertextSha256 = ByteString.copyFrom(sha256("blob-$index".toByteArray())),
            )
        }
    }

    private fun validCapsuleKeysetBytes(): ByteArray =
        TinkProtoKeysetFormat.serializeKeyset(
            CapsuleKeysetGenerator().generate(),
            com.google.crypto.tink.InsecureSecretKeyAccess.get(),
        )

    private fun envelopeFor(
        statementBytes: ByteArray,
        statementHash: ByteArray = sha256(statementBytes),
        capsuleKeysetBytes: ByteArray = validCapsuleKeysetBytes(),
    ): ByteArray {
        val statement = PublishStatement.parseFrom(statementBytes)
        return RecipientEnvelopePlaintext.newBuilder()
            .setProtocolVersion(1)
            .setCapsuleId(statement.capsuleId)
            .setSenderUserId(statement.senderUserId)
            .setRecipientUserId(statement.recipientUserId)
            .setSenderKeyBundleId(statement.senderKeyBundleId)
            .setRecipientKeyBundleId(statement.recipientKeyBundleId)
            .setCapsuleAeadKeyset(ByteString.copyFrom(capsuleKeysetBytes))
            .setPublishStatementSha256(ByteString.copyFrom(statementHash))
            .build()
            .toByteArray()
    }

    private fun blobsFor(statementBytes: ByteArray): List<DeliveredBlob> =
        PublishStatement.parseFrom(statementBytes).artifactsList.mapNotNull { binding ->
            val id = try {
                BlobId.fromProtoBytes(binding.blobId)
            } catch (_: IllegalArgumentException) {
                return@mapNotNull null
            }
            DeliveredBlob(
                blobId = id,
                ciphertextSize = binding.ciphertextSize,
                ciphertextSha256 = binding.ciphertextSha256.toByteArray(),
            )
        }

    private fun sealedCapsule(
        artifacts: List<PublishArtifact> = artifacts(),
        statementHash: ByteArray? = null,
        envelopeKeyset: ByteArray? = null,
    ): SealedCapsule {
        val success = PublishStatementBuilder.build(
            PublishStatementInput(
                capsuleId, senderUser, recipientUser, senderBundle, recipientBundle,
                1_700_000_000L, artifacts,
            ),
        ) as PublishStatementBuildResult.Success
        val bytes = success.deterministicBytes.toByteArray()
        val signed = signer.sign(senderIdentity.signingPrivateHandle, bytes)
        return SealedCapsule(
            statementBytes = bytes,
            signature = signed.signature,
            envelopeBytes = envelopeFor(
                bytes,
                statementHash ?: sha256(bytes),
                envelopeKeyset ?: validCapsuleKeysetBytes(),
            ),
            blobs = blobsFor(bytes),
        )
    }

    private fun senderVerifyingKeyset() =
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(senderIdentity.signingPublicKeyset)

    private fun controlInput(
        capsule: SealedCapsule,
        verifyingKeyset: KeysetHandle = senderVerifyingKeyset(),
        authenticatedUser: UserId = recipientUser,
        expectedCapsule: CapsuleId = capsuleId,
        expectedSenderBundle: KeyBundleId = senderBundle,
    ): CanonicalControlInput = CanonicalControlInput(
        expectedCapsuleId = expectedCapsule,
        authenticatedUserId = authenticatedUser,
        senderVerifyingKeyset = verifyingKeyset,
        expectedSenderKeyBundleId = expectedSenderBundle,
        envelopePlaintextBytes = capsule.envelopeBytes,
        statementBytes = capsule.statementBytes,
        signature = capsule.signature,
    )

    private fun rejected(result: CanonicalControlResult): RejectionReason =
        assertIs<CanonicalControlResult.Rejected>(result).reason

    @Test
    fun consistentControlIsVerifiedAndExposesParsedStatement() {
        val capsule = sealedCapsule()
        val result = verifier.verify(controlInput(capsule))

        val verified = assertIs<CanonicalControlResult.Verified>(result)
        assertEquals(5, verified.control.statement.artifactsCount)
        assertEquals(capsule.statementBytes.toList(), verified.control.statement.toByteArray().toList())
    }

    @Test
    fun malformedEnvelopeIsRejectedBeforeAnythingElse() {
        val capsule = sealedCapsule()
        assertEquals(
            RejectionReason.MALFORMED_ENVELOPE,
            rejected(verifier.verify(controlInput(capsule).copy(envelopePlaintextBytes = ByteArray(0)))),
        )
    }

    @Test
    fun malformedStatementIsRejected() {
        val capsule = sealedCapsule()
        assertEquals(
            RejectionReason.MALFORMED_STATEMENT,
            rejected(verifier.verify(controlInput(capsule).copy(statementBytes = "garbage".toByteArray()))),
        )
    }

    @Test
    fun nonCanonicalBytesAreRejected() {
        val capsule = sealedCapsule()
        val paddedStatement = byteArrayOf(0x08, 0x01) + capsule.statementBytes
        assertEquals(
            RejectionReason.NON_CANONICAL_BYTES,
            rejected(verifier.verify(controlInput(capsule).copy(statementBytes = paddedStatement))),
        )
    }

    @Test
    fun unusableCapsuleKeysetsAreRejected() {
        val rawKeyset = TinkProtoKeysetFormat.serializeKeyset(
            KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_RAW")),
            com.google.crypto.tink.InsecureSecretKeyAccess.get(),
        )
        val foreignAlgorithm = TinkProtoKeysetFormat.serializeKeyset(
            KeysetHandle.generateNew(KeyTemplates.get("CHACHA20_POLY1305")),
            com.google.crypto.tink.InsecureSecretKeyAccess.get(),
        )
        val garbage = "not-a-tink-keyset".toByteArray()

        listOf(rawKeyset, foreignAlgorithm, garbage).forEach { keysetBytes ->
            val capsule = sealedCapsule(statementHash = null, envelopeKeyset = keysetBytes)
            assertEquals(
                RejectionReason.MALFORMED_CAPSULE_KEYSET,
                rejected(verifier.verify(controlInput(capsule))),
                "keyset variant must reject closed",
            )
        }
    }

    @Test
    fun tamperedSignatureIsRejected() {
        val capsule = sealedCapsule()
        val tampered = capsule.signature.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertEquals(
            RejectionReason.SIGNATURE_INVALID,
            rejected(verifier.verify(controlInput(capsule).copy(signature = tampered))),
        )
    }

    @Test
    fun foreignSenderVerifyingKeyIsRejected() {
        val capsule = sealedCapsule()
        val foreignKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
            AccountIdentityGenerator().generate().signingPublicKeyset,
        )
        assertEquals(
            RejectionReason.SIGNATURE_INVALID,
            rejected(verifier.verify(controlInput(capsule, verifyingKeyset = foreignKeyset))),
        )
    }

    @Test
    fun shortAndEmptySignaturesAreRejectedNotThrown() {
        val capsule = sealedCapsule()
        assertEquals(
            RejectionReason.SIGNATURE_INVALID,
            rejected(verifier.verify(controlInput(capsule).copy(signature = ByteArray(0)))),
        )
        assertEquals(
            RejectionReason.SIGNATURE_INVALID,
            rejected(verifier.verify(controlInput(capsule).copy(signature = capsule.signature.copyOf(3)))),
        )
        assertEquals(
            RejectionReason.SIGNATURE_INVALID,
            rejected(verifier.verify(controlInput(capsule).copy(signature = capsule.signature.copyOf(4)))),
        )
    }

    @Test
    fun anyIdentifierSubstitutionIsRejected() {
        val capsule = sealedCapsule()
        assertEquals(
            RejectionReason.ID_MISMATCH,
            rejected(
                verifier.verify(
                    controlInput(
                        capsule,
                        expectedCapsule = CapsuleId(UUID.fromString("9a999999-9999-4999-8999-999999999999")),
                    ),
                ),
            ),
        )
        assertEquals(
            RejectionReason.ID_MISMATCH,
            rejected(
                verifier.verify(
                    controlInput(
                        capsule,
                        authenticatedUser = UserId(UUID.fromString("8a888888-8888-4888-8888-888888888888")),
                    ),
                ),
            ),
        )
        assertEquals(
            RejectionReason.ID_MISMATCH,
            rejected(
                verifier.verify(
                    controlInput(
                        capsule,
                        expectedSenderBundle = KeyBundleId(UUID.fromString("7a777777-7777-4777-8777-777777777777")),
                    ),
                ),
            ),
        )
        val disagreeing = RecipientEnvelopePlaintext.newBuilder()
            .mergeFrom(RecipientEnvelopePlaintext.parseFrom(capsule.envelopeBytes))
            .setSenderUserId(UserId(UUID.fromString("6a666666-6666-4666-8666-666666666666")).toProtoBytes())
            .build()
            .toByteArray()
        assertEquals(
            RejectionReason.ID_MISMATCH,
            rejected(verifier.verify(controlInput(capsule).copy(envelopePlaintextBytes = disagreeing))),
        )
    }

    @Test
    fun statementHashMismatchIsRejected() {
        val staleHash = sealedCapsule(statementHash = ByteArray(32))
        assertEquals(
            RejectionReason.STATEMENT_HASH_MISMATCH,
            rejected(verifier.verify(controlInput(staleHash))),
        )
    }

    @Test
    fun layoutViolationIsRejectedEvenWhenSigned() {
        val valid = sealedCapsule()
        val reducedStatement = PublishStatement.parseFrom(valid.statementBytes)
            .toBuilder()
            .removeArtifacts(0)
            .build()
        val bytes = reducedStatement.toByteArray()
        val signed = signer.sign(senderIdentity.signingPrivateHandle, bytes)
        val reduced = SealedCapsule(
            statementBytes = bytes,
            signature = signed.signature,
            envelopeBytes = envelopeFor(bytes),
            blobs = blobsFor(bytes),
        )
        assertEquals(
            RejectionReason.LAYOUT_INVALID,
            rejected(verifier.verify(controlInput(reduced))),
        )
    }

    @Test
    fun unknownEnumKindIsRejectedAsLayoutInsteadOfThrowing() {
        val valid = sealedCapsule()
        val poisoned = PublishStatement.parseFrom(valid.statementBytes)
            .toBuilder()
            .setArtifacts(
                0,
                PublishStatement.parseFrom(valid.statementBytes).getArtifacts(0).toBuilder()
                    .setKindValue(99),
            )
            .build()
        val bytes = poisoned.toByteArray()
        val signed = signer.sign(senderIdentity.signingPrivateHandle, bytes)
        val capsule = SealedCapsule(
            statementBytes = bytes,
            signature = signed.signature,
            envelopeBytes = envelopeFor(bytes),
            blobs = blobsFor(bytes),
        )
        assertEquals(
            RejectionReason.LAYOUT_INVALID,
            rejected(verifier.verify(controlInput(capsule))),
        )
    }

    @Test
    fun malformedBlobIdInsideStatementIsRejectedInsteadOfThrowing() {
        val valid = sealedCapsule()
        val truncatedId = ByteString.copyFrom(ByteArray(15) { 0x0a })
        val binding = PublishStatement.parseFrom(valid.statementBytes).getArtifacts(0).toBuilder()
            .setBlobId(truncatedId)
            .build()
        val poisoned = PublishStatement.parseFrom(valid.statementBytes)
            .toBuilder()
            .setArtifacts(0, binding)
            .build()
        val bytes = poisoned.toByteArray()
        val signed = signer.sign(senderIdentity.signingPrivateHandle, bytes)
        val capsule = SealedCapsule(
            statementBytes = bytes,
            signature = signed.signature,
            envelopeBytes = envelopeFor(bytes),
            blobs = blobsFor(bytes),
        )
        assertEquals(
            RejectionReason.LAYOUT_INVALID,
            rejected(verifier.verify(controlInput(capsule))),
        )
    }

    @Test
    fun verifierNeverConsultsDeliveredBlobs() {
        val capsule = sealedCapsule()
        val result = verifier.verify(controlInput(capsule))
        assertNotNull(result as? CanonicalControlResult.Verified)
    }
}
