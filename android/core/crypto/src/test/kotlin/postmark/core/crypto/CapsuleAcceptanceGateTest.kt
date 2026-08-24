package postmark.core.crypto

import app.postmark.protocol.v1.PublishStatement
import app.postmark.protocol.v1.RecipientEnvelopePlaintext
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.protobuf.ByteString
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import postmark.core.model.ArtifactSlot
import postmark.core.model.BlobId
import postmark.core.model.CapsuleArtifactKind
import postmark.core.model.CapsuleId
import postmark.core.model.KeyBundleId
import postmark.core.model.PublishArtifact
import postmark.core.model.PublishStatementBuildResult
import postmark.core.model.PublishStatementBuilder
import postmark.core.model.PublishStatementInput
import postmark.core.model.UserId

/**
 * M1-C13 substitution matrix: every check runs before any artifact decrypt,
 * and each tampering class is rejected closed with its reason.
 */
class CapsuleAcceptanceGateTest {

    private val gate = CapsuleAcceptanceGate()
    private val signer = PublishStatementSigner()
    private val senderIdentity = AccountIdentityGenerator().generate()

    private val capsuleId = CapsuleId(UUID.fromString("1a111111-2222-4333-8444-555555555555"))
    private val senderUser = UserId(UUID.fromString("2a222222-3333-4444-8555-666666666666"))
    private val recipientUser = UserId(UUID.fromString("3a333333-4444-4555-8666-777777777777"))
    private val senderBundle = KeyBundleId(UUID.fromString("4a444444-5555-4666-8777-888888888888"))
    private val recipientBundle = KeyBundleId(UUID.fromString("5a555555-6666-4777-8888-999999999999"))

    /** A complete consistent capsule: signed statement + agreeing envelope + delivered blobs. */
    private data class SealedCapsule(
        val statementBytes: ByteArray,
        val signature: ByteArray,
        val envelopeBytes: ByteArray,
        val blobs: List<DeliveredBlob>,
    ) {
        val statement: PublishStatement get() = PublishStatement.parseFrom(statementBytes)
    }

    @BeforeTest
    fun setUp() {
        TinkPrimitives.ensureRegistered()
    }

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

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun envelopeFor(statementBytes: ByteArray, statementHash: ByteArray = sha256(statementBytes)): ByteArray {
        val statement = PublishStatement.parseFrom(statementBytes)
        return RecipientEnvelopePlaintext.newBuilder()
            .setProtocolVersion(1)
            .setCapsuleId(statement.capsuleId)
            .setSenderUserId(statement.senderUserId)
            .setRecipientUserId(statement.recipientUserId)
            .setSenderKeyBundleId(statement.senderKeyBundleId)
            .setRecipientKeyBundleId(statement.recipientKeyBundleId)
            .setCapsuleAeadKeyset(ByteString.copyFrom("wrapped-capsule-keyset-material".toByteArray()))
            .setPublishStatementSha256(ByteString.copyFrom(statementHash))
            .build()
            .toByteArray()
    }

    private fun blobsFor(statementBytes: ByteArray): List<DeliveredBlob> =
        PublishStatement.parseFrom(statementBytes).artifactsList.map { binding ->
            DeliveredBlob(
                blobId = BlobId.fromProtoBytes(binding.blobId),
                ciphertextSize = binding.ciphertextSize,
                ciphertextSha256 = binding.ciphertextSha256.toByteArray(),
            )
        }

    private fun sealedCapsule(
        artifacts: List<PublishArtifact> = artifacts(),
        statementHash: ByteArray? = null,
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
            envelopeBytes = envelopeFor(bytes, statementHash ?: sha256(bytes)),
            blobs = blobsFor(bytes),
        )
    }

    private fun senderVerifyingKeyset() =
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(senderIdentity.signingPublicKeyset)

    private fun input(
        capsule: SealedCapsule,
        verifyingKeyset: com.google.crypto.tink.KeysetHandle = senderVerifyingKeyset(),
        authenticatedUser: UserId = recipientUser,
        expectedCapsule: CapsuleId = capsuleId,
        expectedSenderBundle: KeyBundleId = senderBundle,
    ): CapsuleAcceptanceInput = CapsuleAcceptanceInput(
        expectedCapsuleId = expectedCapsule,
        authenticatedUserId = authenticatedUser,
        senderVerifyingKeyset = verifyingKeyset,
        expectedSenderKeyBundleId = expectedSenderBundle,
        envelopePlaintextBytes = capsule.envelopeBytes,
        statementBytes = capsule.statementBytes,
        signature = capsule.signature,
        deliveredBlobs = capsule.blobs,
    )

    private fun rejected(result: CapsuleAcceptanceResult): RejectionReason =
        assertIs<CapsuleAcceptanceResult.Rejected>(result).reason

    @Test
    fun fullyConsistentCapsuleIsAccepted() {
        val result = gate.verify(input(sealedCapsule()))

        val accepted = assertIs<CapsuleAcceptanceResult.Accepted>(result)
        assertEquals(5, accepted.statement.artifactsCount)
    }

    @Test
    fun substitutedBlobHashSizeOrSetIsRejectedBeforeDecrypt() {
        val capsule = sealedCapsule()

        val swappedHash = capsule.blobs.mapIndexed { index, blob ->
            if (index == 2) blob.copy(ciphertextSha256 = sha256("evil".toByteArray())) else blob
        }
        assertEquals(
            RejectionReason.BLOB_SUBSTITUTION,
            rejected(gate.verify(input(capsule).copy(deliveredBlobs = swappedHash))),
        )

        val resized = capsule.blobs.mapIndexed { index, blob ->
            if (index == 0) blob.copy(ciphertextSize = blob.ciphertextSize + 1) else blob
        }
        assertEquals(
            RejectionReason.BLOB_SUBSTITUTION,
            rejected(gate.verify(input(capsule).copy(deliveredBlobs = resized))),
        )

        assertEquals(
            RejectionReason.BLOB_SUBSTITUTION,
            rejected(gate.verify(input(capsule).copy(deliveredBlobs = capsule.blobs.dropLast(1)))),
        )
        assertEquals(
            RejectionReason.BLOB_SUBSTITUTION,
            rejected(gate.verify(input(capsule).copy(deliveredBlobs = capsule.blobs + capsule.blobs.first()))),
        )
    }

    @Test
    fun tamperedSignatureAndForeignSenderKeyAreRejected() {
        val capsule = sealedCapsule()
        val tamperedSignature = capsule.signature.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertEquals(
            RejectionReason.SIGNATURE_INVALID,
            rejected(gate.verify(input(capsule).copy(signature = tamperedSignature))),
        )

        val foreignKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
            AccountIdentityGenerator().generate().signingPublicKeyset,
        )
        assertEquals(
            RejectionReason.SIGNATURE_INVALID,
            rejected(gate.verify(input(capsule, verifyingKeyset = foreignKeyset))),
        )
    }

    @Test
    fun anyIdentifierSubstitutionIsRejected() {
        val capsule = sealedCapsule()

        assertEquals(
            RejectionReason.ID_MISMATCH,
            rejected(
                gate.verify(
                    input(
                        capsule,
                        expectedCapsule = CapsuleId(UUID.fromString("9a999999-9999-4999-8999-999999999999")),
                    ),
                ),
            ),
        )
        assertEquals(
            RejectionReason.ID_MISMATCH,
            rejected(
                gate.verify(
                    input(
                        capsule,
                        authenticatedUser = UserId(UUID.fromString("8a888888-8888-4888-8888-888888888888")),
                    ),
                ),
            ),
        )
        assertEquals(
            RejectionReason.ID_MISMATCH,
            rejected(
                gate.verify(
                    input(
                        capsule,
                        expectedSenderBundle = KeyBundleId(UUID.fromString("7a777777-7777-4777-8777-777777777777")),
                    ),
                ),
            ),
        )

        // Envelope that disagrees with the statement on the sender user.
        val disagreeing = RecipientEnvelopePlaintext.newBuilder()
            .mergeFrom(RecipientEnvelopePlaintext.parseFrom(capsule.envelopeBytes))
            .setSenderUserId(UserId(UUID.fromString("6a666666-6666-4666-8666-666666666666")).toProtoBytes())
            .build()
            .toByteArray()
        assertEquals(
            RejectionReason.ID_MISMATCH,
            rejected(gate.verify(input(capsule).copy(envelopePlaintextBytes = disagreeing))),
        )
    }

    @Test
    fun statementHashMismatchAndNonCanonicalBytesAreRejected() {
        val staleHash = sealedCapsule(statementHash = ByteArray(32))
        assertEquals(
            RejectionReason.STATEMENT_HASH_MISMATCH,
            rejected(gate.verify(input(staleHash))),
        )

        // A duplicated leading version field parses but is not canonical v1 bytes.
        val padded = sealedCapsule().let {
            it.copy(statementBytes = byteArrayOf(0x08, 0x01) + it.statementBytes)
        }
        assertEquals(
            RejectionReason.NON_CANONICAL_BYTES,
            rejected(gate.verify(input(padded))),
        )
    }

    @Test
    fun layoutViolationIsRejectedEvenWhenSigned() {
        // Drop the recognition manifest binding directly from the proto and
        // re-sign: signature is valid, but artifact cardinality violates v1.
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

        assertEquals(RejectionReason.LAYOUT_INVALID, rejected(gate.verify(input(reduced))))
    }

    @Test
    fun malformedEnvelopeAndEmptyStatementAreRejected() {
        val capsule = sealedCapsule()
        assertEquals(
            RejectionReason.MALFORMED_ENVELOPE,
            rejected(gate.verify(input(capsule).copy(envelopePlaintextBytes = ByteArray(0)))),
        )
        assertEquals(
            RejectionReason.MALFORMED_STATEMENT,
            rejected(gate.verify(input(capsule).copy(statementBytes = "garbage".toByteArray()))),
        )
    }
}
