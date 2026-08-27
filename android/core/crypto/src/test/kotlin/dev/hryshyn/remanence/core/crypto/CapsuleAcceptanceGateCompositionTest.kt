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

/**
 * M2-P10 differential characterization: the original malformed/golden
 * corpus runs through the composed [CapsuleAcceptanceGate] (control
 * verification + full-material blob binding). Every variant pins the same
 * [RejectionReason] the old in-line gate produced. If this test ever
 * flips a result, the acceptance decision has changed.
 */
class CapsuleAcceptanceGateCompositionTest {

    private val gate = CapsuleAcceptanceGate()
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

    private fun input(
        capsule: SealedCapsule,
        verifyingKeyset: KeysetHandle = senderVerifyingKeyset(),
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

    private sealed interface Expected {
        data class Accepted(val artifactCount: Int) : Expected
        data class Rejected(val reason: RejectionReason) : Expected
    }

    private fun check(label: String, actual: CapsuleAcceptanceResult, expected: Expected) {
        when (expected) {
            is Expected.Accepted -> {
                val accepted = assertIs<CapsuleAcceptanceResult.Accepted>(
                    actual,
                    "expected Accepted for $label",
                )
                assertEquals(expected.artifactCount, accepted.statement.artifactsCount, label)
            }
            is Expected.Rejected -> {
                val rejected = assertIs<CapsuleAcceptanceResult.Rejected>(
                    actual,
                    "expected Rejected for $label",
                )
                assertEquals(expected.reason, rejected.reason, label)
            }
        }
    }

    @Test
    fun composedGatePinsEveryOldResult() {
        val golden = sealedCapsule()
        check("fullyConsistent", gate.verify(input(golden)), Expected.Accepted(artifactCount = 5))

        // ===== BLOB_SUBSTITUTION: duplicate/missing/extra/size/hash =====
        val swappedHash = golden.blobs.mapIndexed { index, blob ->
            if (index == 2) blob.copy(ciphertextSha256 = sha256("evil".toByteArray())) else blob
        }
        check("swappedHash", gate.verify(input(golden).copy(deliveredBlobs = swappedHash)), Expected.Rejected(RejectionReason.BLOB_SUBSTITUTION))

        val resized = golden.blobs.mapIndexed { index, blob ->
            if (index == 0) blob.copy(ciphertextSize = blob.ciphertextSize + 1) else blob
        }
        check("resized", gate.verify(input(golden).copy(deliveredBlobs = resized)), Expected.Rejected(RejectionReason.BLOB_SUBSTITUTION))

        check("missingLast", gate.verify(input(golden).copy(deliveredBlobs = golden.blobs.dropLast(1))), Expected.Rejected(RejectionReason.BLOB_SUBSTITUTION))
        check("extraFirst", gate.verify(input(golden).copy(deliveredBlobs = golden.blobs + golden.blobs.first())), Expected.Rejected(RejectionReason.BLOB_SUBSTITUTION))

        // ===== SIGNATURE_INVALID: tampered, foreign key, short/empty =====
        val tamperedSignature = golden.signature.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        check("tamperedSignature", gate.verify(input(golden).copy(signature = tamperedSignature)), Expected.Rejected(RejectionReason.SIGNATURE_INVALID))

        val foreignKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
            AccountIdentityGenerator().generate().signingPublicKeyset,
        )
        check("foreignKey", gate.verify(input(golden, verifyingKeyset = foreignKeyset)), Expected.Rejected(RejectionReason.SIGNATURE_INVALID))

        check("emptySignature", gate.verify(input(golden).copy(signature = ByteArray(0))), Expected.Rejected(RejectionReason.SIGNATURE_INVALID))
        check("signature3", gate.verify(input(golden).copy(signature = golden.signature.copyOf(3))), Expected.Rejected(RejectionReason.SIGNATURE_INVALID))
        check("signature4", gate.verify(input(golden).copy(signature = golden.signature.copyOf(4))), Expected.Rejected(RejectionReason.SIGNATURE_INVALID))

        // ===== ID_MISMATCH: capsule, user, sender bundle, envelope/statement =====
        check(
            "wrongExpectedCapsule",
            gate.verify(input(golden, expectedCapsule = CapsuleId(UUID.fromString("9a999999-9999-4999-8999-999999999999")))),
            Expected.Rejected(RejectionReason.ID_MISMATCH),
        )
        check(
            "wrongAuthenticatedUser",
            gate.verify(input(golden, authenticatedUser = UserId(UUID.fromString("8a888888-8888-4888-8888-888888888888")))),
            Expected.Rejected(RejectionReason.ID_MISMATCH),
        )
        check(
            "wrongSenderBundle",
            gate.verify(input(golden, expectedSenderBundle = KeyBundleId(UUID.fromString("7a777777-7777-4777-8777-777777777777")))),
            Expected.Rejected(RejectionReason.ID_MISMATCH),
        )
        val disagreeing = RecipientEnvelopePlaintext.newBuilder()
            .mergeFrom(RecipientEnvelopePlaintext.parseFrom(golden.envelopeBytes))
            .setSenderUserId(UserId(UUID.fromString("6a666666-6666-4666-8666-666666666666")).toProtoBytes())
            .build()
            .toByteArray()
        check(
            "envelopeDisagreesOnSender",
            gate.verify(input(golden).copy(envelopePlaintextBytes = disagreeing)),
            Expected.Rejected(RejectionReason.ID_MISMATCH),
        )

        // ===== STATEMENT_HASH_MISMATCH / NON_CANONICAL_BYTES =====
        val staleHash = sealedCapsule(statementHash = ByteArray(32))
        check("staleStatementHash", gate.verify(input(staleHash)), Expected.Rejected(RejectionReason.STATEMENT_HASH_MISMATCH))

        val padded = sealedCapsule().let { it.copy(statementBytes = byteArrayOf(0x08, 0x01) + it.statementBytes) }
        check("paddedStatement", gate.verify(input(padded)), Expected.Rejected(RejectionReason.NON_CANONICAL_BYTES))

        // ===== LAYOUT_INVALID: missing artifact, unknown enum kind, malformed blob id =====
        val valid = sealedCapsule()
        val reducedStatement = PublishStatement.parseFrom(valid.statementBytes)
            .toBuilder()
            .removeArtifacts(0)
            .build()
        val reducedBytes = reducedStatement.toByteArray()
        val reducedSigned = signer.sign(senderIdentity.signingPrivateHandle, reducedBytes)
        val reduced = SealedCapsule(
            statementBytes = reducedBytes,
            signature = reducedSigned.signature,
            envelopeBytes = envelopeFor(reducedBytes),
            blobs = blobsFor(reducedBytes),
        )
        check("missingArtifact", gate.verify(input(reduced)), Expected.Rejected(RejectionReason.LAYOUT_INVALID))

        val poisoned = PublishStatement.parseFrom(valid.statementBytes)
            .toBuilder()
            .setArtifacts(
                0,
                PublishStatement.parseFrom(valid.statementBytes).getArtifacts(0).toBuilder()
                    .setKindValue(99),
            )
            .build()
        val poisonedBytes = poisoned.toByteArray()
        val poisonedSigned = signer.sign(senderIdentity.signingPrivateHandle, poisonedBytes)
        val poisonedCapsule = SealedCapsule(
            statementBytes = poisonedBytes,
            signature = poisonedSigned.signature,
            envelopeBytes = envelopeFor(poisonedBytes),
            blobs = blobsFor(poisonedBytes),
        )
        check("unknownKindEnum", gate.verify(input(poisonedCapsule)), Expected.Rejected(RejectionReason.LAYOUT_INVALID))

        val truncatedId = ByteString.copyFrom(ByteArray(15) { 0x0a })
        val truncatedBinding = PublishStatement.parseFrom(valid.statementBytes).getArtifacts(0).toBuilder()
            .setBlobId(truncatedId)
            .build()
        val truncatedStatement = PublishStatement.parseFrom(valid.statementBytes)
            .toBuilder()
            .setArtifacts(0, truncatedBinding)
            .build()
        val truncatedBytes = truncatedStatement.toByteArray()
        val truncatedSigned = signer.sign(senderIdentity.signingPrivateHandle, truncatedBytes)
        val truncatedCapsule = SealedCapsule(
            statementBytes = truncatedBytes,
            signature = truncatedSigned.signature,
            envelopeBytes = envelopeFor(truncatedBytes),
            blobs = blobsFor(truncatedBytes),
        )
        check("malformedBlobId", gate.verify(input(truncatedCapsule)), Expected.Rejected(RejectionReason.LAYOUT_INVALID))

        // ===== MALFORMED_ENVELOPE / MALFORMED_STATEMENT =====
        check(
            "emptyEnvelope",
            gate.verify(input(golden).copy(envelopePlaintextBytes = ByteArray(0))),
            Expected.Rejected(RejectionReason.MALFORMED_ENVELOPE),
        )
        check(
            "garbageStatement",
            gate.verify(input(golden).copy(statementBytes = "garbage".toByteArray())),
            Expected.Rejected(RejectionReason.MALFORMED_STATEMENT),
        )

        // ===== MALFORMED_CAPSULE_KEYSET: raw, foreign, garbage =====
        val rawKeyset = TinkProtoKeysetFormat.serializeKeyset(
            KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_RAW")),
            com.google.crypto.tink.InsecureSecretKeyAccess.get(),
        )
        val foreignAlgorithm = TinkProtoKeysetFormat.serializeKeyset(
            KeysetHandle.generateNew(KeyTemplates.get("CHACHA20_POLY1305")),
            com.google.crypto.tink.InsecureSecretKeyAccess.get(),
        )
        val garbage = "not-a-tink-keyset".toByteArray()
        listOf(
            "rawKeyset" to rawKeyset,
            "foreignAlgorithm" to foreignAlgorithm,
            "garbageKeyset" to garbage,
        ).forEach { (name, bytes) ->
            val capsule = sealedCapsule(statementHash = null, envelopeKeyset = bytes)
            check(name, gate.verify(input(capsule)), Expected.Rejected(RejectionReason.MALFORMED_CAPSULE_KEYSET))
        }
    }
}
