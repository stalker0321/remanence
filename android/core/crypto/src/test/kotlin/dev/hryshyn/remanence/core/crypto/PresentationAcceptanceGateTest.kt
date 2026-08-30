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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * M2-P12 presentation acceptance: verify that [PresentationAcceptanceGate]
 * accepts a valid envelope+statement+content manifest+photos, propagates
 * the [CanonicalControlVerifier] rejection reasons unchanged, rejects
 * every failure class byte-for-byte without throwing, and that the
 * public [PresentationAcceptanceResult.Verified] surface contains
 * only the verified [PublishStatement].
 */
class PresentationAcceptanceGateTest {

    private val gate = PresentationAcceptanceGate()
    private val signer = PublishStatementSigner()
    private val senderIdentity = AccountIdentityGenerator().generate()
    private val contentManifestCodec = ContentManifestCodec()
    private val recognitionCodec = RecognitionManifestCodec()
    private val photoEncryptor = PhotoArtifactEncryptor()

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
    private val photo4BlobId = BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000006"))
    private val photo5BlobId = BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000007"))

    private data class PresentationCapsule(
        val keyset: KeysetHandle,
        val statementBytes: ByteArray,
        val signature: ByteArray,
        val envelopeBytes: ByteArray,
        val recognitionCiphertext: ByteArray,
        val contentCiphertext: ByteArray,
        val photoCiphertexts: List<DeliveredCiphertext>,
    )

    @BeforeTest
    fun setUp() {
        TinkPrimitives.ensureRegistered()
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun serializeKeyset(keyset: KeysetHandle): ByteArray =
        TinkProtoKeysetFormat.serializeKeyset(keyset, InsecureSecretKeyAccess.get())

    private fun envelopeFor(
        statementBytes: ByteArray,
        keysetBytes: ByteArray,
        statementHash: ByteArray,
    ): ByteArray {
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

    private fun routing(): RecognitionManifestCodec.RoutingContext =
        RecognitionManifestCodec.RoutingContext(
            capsuleId = capsuleId,
            blobId = contentBlobId,
            senderUserId = senderUser,
            recipientUserId = recipientUser,
        )

    private fun photosForOrdinals(ordinals: List<Int>): List<ManifestPhoto> = ordinals.map { ordinal ->
        val blobId = when (ordinal) {
            0 -> photo1BlobId
            1 -> photo2BlobId
            2 -> photo3BlobId
            3 -> photo4BlobId
            4 -> photo5BlobId
            else -> error("unexpected ordinal $ordinal")
        }
        ManifestPhoto(
            blobId = blobId.value,
            ordinal = ordinal,
            width = 2560,
            height = 1600,
        )
    }

    private fun buildPresentationCapsule(
        photoCount: Int = 3,
    ): PresentationCapsule {
        val keyset = CapsuleKeysetGenerator().generate()
        val keysetBytes = serializeKeyset(keyset)

        // 1. Build recognition ciphertext (required for full coverage).
        val recognitionRouting = RecognitionManifestCodec.RoutingContext(
            capsuleId = capsuleId,
            blobId = recognitionBlobId,
            senderUserId = senderUser,
            recipientUserId = recipientUser,
        )
        val recognitionCiphertext = recognitionCodec.buildAndEncrypt(
            capsuleKeyset = keyset,
            routingContext = recognitionRouting,
            senderHandleSnapshot = "mykola",
            createdAtEpochSeconds = 1_700_000_000L,
            placeLabel = null,
            frontFingerprint = ByteArray(96) { (it * 3).toByte() },
            backFingerprint = ByteArray(96) { (it * 7 + 1).toByte() },
        )

        // 2. Build photo ciphertexts.
        val photoCiphertexts = mutableListOf<DeliveredCiphertext>()
        val photoSizes = mutableListOf<Long>()
        val photoHashes = mutableListOf<ByteArray>()
        for (ordinal in 0 until photoCount) {
            val blobId = when (ordinal) {
                0 -> photo1BlobId
                1 -> photo2BlobId
                2 -> photo3BlobId
                3 -> photo4BlobId
                4 -> photo5BlobId
                else -> error("unexpected ordinal $ordinal")
            }
            val perPhotoRouting = RecognitionManifestCodec.RoutingContext(
                capsuleId = capsuleId,
                blobId = blobId,
                senderUserId = senderUser,
                recipientUserId = recipientUser,
            )
            val encrypted = photoEncryptor.encryptPhoto(
                capsuleKeyset = keyset,
                routingContext = perPhotoRouting,
                ordinal = ordinal,
                normalizedJpeg = ByteArray(1024) { (ordinal * 37 + it).toByte() },
            )
            photoCiphertexts += DeliveredCiphertext(blobId, encrypted.ciphertext)
            photoSizes += encrypted.sizeBytes
            photoHashes += encrypted.ciphertextSha256
        }

        // 3. Build content manifest ciphertext with matching photo descriptors.
        val manifest = contentManifestCodec.buildAndEncrypt(
            capsuleKeyset = keyset,
            routingContext = routing(),
            photos = photosForOrdinals((0 until photoCount).toList()),
            note = "С днём рождения!",
        )

        // 4. Build publish statement with all five artifacts (recognition,
        // content, photos). Layout-valid by construction.
        val artifacts = mutableListOf<PublishArtifact>()
        artifacts += PublishArtifact(
            slot = ArtifactSlot(recognitionBlobId, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1),
            ciphertextSize = recognitionCiphertext.size.toLong(),
            ciphertextSha256 = ByteString.copyFrom(sha256(recognitionCiphertext)),
        )
        artifacts += PublishArtifact(
            slot = ArtifactSlot(contentBlobId, CapsuleArtifactKind.CONTENT_MANIFEST, -1),
            ciphertextSize = manifest.size.toLong(),
            ciphertextSha256 = ByteString.copyFrom(sha256(manifest)),
        )
        for (ordinal in 0 until photoCount) {
            val blobId = when (ordinal) {
                0 -> photo1BlobId
                1 -> photo2BlobId
                2 -> photo3BlobId
                3 -> photo4BlobId
                4 -> photo5BlobId
                else -> error("unexpected ordinal $ordinal")
            }
            artifacts += PublishArtifact(
                slot = ArtifactSlot(blobId, CapsuleArtifactKind.PHOTO, ordinal),
                ciphertextSize = photoSizes[ordinal],
                ciphertextSha256 = ByteString.copyFrom(photoHashes[ordinal]),
            )
        }
        val success = PublishStatementBuilder.build(
            PublishStatementInput(
                capsuleId, senderUser, recipientUser, senderBundle, recipientBundle,
                1_700_000_000L, artifacts,
            ),
        ) as PublishStatementBuildResult.Success
        val bytes = success.deterministicBytes.toByteArray()
        val signed = signer.sign(senderIdentity.signingPrivateHandle, bytes)
        val envelopeBytes = envelopeFor(bytes, keysetBytes, sha256(bytes))

        return PresentationCapsule(
            keyset = keyset,
            statementBytes = bytes,
            signature = signed.signature,
            envelopeBytes = envelopeBytes,
            recognitionCiphertext = recognitionCiphertext,
            contentCiphertext = manifest,
            photoCiphertexts = photoCiphertexts,
        )
    }

    private fun senderVerifyingKeyset() =
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(senderIdentity.signingPublicKeyset)

    private fun input(
        capsule: PresentationCapsule,
        envelopeOverride: ByteArray? = null,
        statementOverride: ByteArray? = null,
        signatureOverride: ByteArray? = null,
        deliveredOverride: List<DeliveredCiphertext>? = null,
        expectedCapsule: CapsuleId = capsuleId,
        authenticatedUser: UserId = recipientUser,
        expectedSenderBundle: KeyBundleId = senderBundle,
    ): PresentationAcceptanceInput = PresentationAcceptanceInput(
        expectedCapsuleId = expectedCapsule,
        authenticatedUserId = authenticatedUser,
        senderVerifyingKeyset = senderVerifyingKeyset(),
        expectedSenderKeyBundleId = expectedSenderBundle,
        envelopePlaintextBytes = envelopeOverride ?: capsule.envelopeBytes,
        statementBytes = statementOverride ?: capsule.statementBytes,
        signature = signatureOverride ?: capsule.signature,
        deliveredCiphertexts = deliveredOverride ?: run {
            listOf(DeliveredCiphertext(recognitionBlobId, capsule.recognitionCiphertext)) +
                listOf(DeliveredCiphertext(contentBlobId, capsule.contentCiphertext)) +
                capsule.photoCiphertexts
        },
    )

    private fun rejected(result: PresentationAcceptanceResult): RejectionReason =
        assertIs<PresentationAcceptanceResult.Rejected>(result).reason

    @Test
    fun validThreePhotoPresentationSucceeds() {
        val capsule = buildPresentationCapsule(photoCount = 3)
        val result = gate.verify(input(capsule))
        val verified = assertIs<PresentationAcceptanceResult.Verified>(result)
        assertEquals(5, verified.statement.artifactsCount)
    }

    @Test
    fun validFivePhotoPresentationSucceeds() {
        val capsule = buildPresentationCapsule(photoCount = 5)
        val result = gate.verify(input(capsule))
        val verified = assertIs<PresentationAcceptanceResult.Verified>(result)
        assertEquals(7, verified.statement.artifactsCount)
    }

    @Test
    fun prepareRetainsExactMaterialUntilCloseAndWipesCiphertextSnapshots() {
        val capsule = buildPresentationCapsule(photoCount = 3)
        val prepared = assertIs<PresentationAcceptancePreparationResult.Prepared>(
            gate.prepare(input(capsule)),
        ).material
        assertEquals(3, prepared.photoCount)
        val photo = prepared.loadPhoto(0)
        try {
            assertEquals(1024, photo.size)
            assertEquals(0, photo[0].toInt())
        } finally {
            photo.fill(0)
        }
        prepared.close()
        assertTrue(prepared.isClosedForTesting())
        assertFailsWith<IllegalStateException> { prepared.loadPhoto(0) }
    }

    @Test
    fun missingDeliveredCiphertextRejects() {
        val capsule = buildPresentationCapsule(photoCount = 3)
        val full = listOf(DeliveredCiphertext(recognitionBlobId, capsule.recognitionCiphertext)) +
            listOf(DeliveredCiphertext(contentBlobId, capsule.contentCiphertext)) +
            capsule.photoCiphertexts
        val missing = full.dropLast(1)
        val result = gate.verify(input(capsule, deliveredOverride = missing))
        assertEquals(RejectionReason.BLOB_SUBSTITUTION, rejected(result))
    }

    @Test
    fun extraDeliveredCiphertextRejects() {
        val capsule = buildPresentationCapsule(photoCount = 3)
        val full = listOf(DeliveredCiphertext(recognitionBlobId, capsule.recognitionCiphertext)) +
            listOf(DeliveredCiphertext(contentBlobId, capsule.contentCiphertext)) +
            capsule.photoCiphertexts
        val extra = full + DeliveredCiphertext(
            BlobId(UUID.fromString("a1000000-0000-4000-8000-000000000099")),
            ByteArray(64) { 0x55 },
        )
        val result = gate.verify(input(capsule, deliveredOverride = extra))
        assertEquals(RejectionReason.BLOB_SUBSTITUTION, rejected(result))
    }

    @Test
    fun substitutedCiphertextRejects() {
        val capsule = buildPresentationCapsule(photoCount = 3)
        // Replace the content ciphertext with a different byte sequence of
        // the same size but a different SHA-256. This is the classic
        // AEAD-valid-but-swap pattern: the signed binding's size+SHA still
        // pin the original, so the substitution fails at the full-material
        // binding check before the content manifest is touched.
        val swapped = capsule.contentCiphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val delivered = listOf(DeliveredCiphertext(recognitionBlobId, capsule.recognitionCiphertext)) +
            listOf(DeliveredCiphertext(contentBlobId, swapped)) +
            capsule.photoCiphertexts
        val result = gate.verify(input(capsule, deliveredOverride = delivered))
        assertEquals(RejectionReason.BLOB_SUBSTITUTION, rejected(result))
    }

    @Test
    fun aeadValidContentCiphertextUnderWrongKeysetRejects() {
        // Encrypt a real content manifest with a different keyset, then
        // present it with the same envelope. The AEAD must fail.
        val capsule = buildPresentationCapsule(photoCount = 3)
        val otherKeyset = CapsuleKeysetGenerator().generate()
        val otherManifest = contentManifestCodec.buildAndEncrypt(
            capsuleKeyset = otherKeyset,
            routingContext = routing(),
            photos = photosForOrdinals(listOf(0, 1, 2)),
            note = null,
        )
        // Rebuild the statement so the signed content binding matches the
        // wrong-keyset content manifest's size and SHA-256; this lets the
        // gate reach the content AEAD check instead of stopping at
        // full-material coverage.
        val rebuilt = rebuildWithContentManifest(capsule, otherManifest)
        val result = gate.verify(
            PresentationAcceptanceInput(
                expectedCapsuleId = rebuilt.expectedCapsuleId,
                authenticatedUserId = rebuilt.authenticatedUserId,
                senderVerifyingKeyset = rebuilt.senderVerifyingKeyset,
                expectedSenderKeyBundleId = rebuilt.expectedSenderKeyBundleId,
                envelopePlaintextBytes = rebuilt.envelopeBytes,
                statementBytes = rebuilt.statementBytes,
                signature = rebuilt.signature,
                deliveredCiphertexts = rebuilt.deliveredCiphertexts,
            ),
        )
        assertEquals(RejectionReason.CONTENT_AEAD_INVALID, rejected(result))
    }

    @Test
    fun wrongInnerCapsuleIdRejects() {
        val capsule = buildPresentationCapsule(photoCount = 3)
        // Encrypt a content manifest under a different capsuleId, using the
        // SAME keyset. The content AAD includes the routing capsuleId, so
        // the gate must rebuild the AAD from the verified envelope's
        // capsuleId and reject with CONTENT_AEAD_INVALID.
        val wrongRouting = RecognitionManifestCodec.RoutingContext(
            capsuleId = CapsuleId(UUID.fromString("9a999999-9999-4999-8999-999999999999")),
            blobId = contentBlobId,
            senderUserId = senderUser,
            recipientUserId = recipientUser,
        )
        val wrongManifest = contentManifestCodec.buildAndEncrypt(
            capsuleKeyset = capsule.keyset,
            routingContext = wrongRouting,
            photos = photosForOrdinals(listOf(0, 1, 2)),
            note = null,
        )
        val rebuilt = rebuildWithContentManifest(capsule, wrongManifest)
        val result = gate.verify(
            PresentationAcceptanceInput(
                expectedCapsuleId = rebuilt.expectedCapsuleId,
                authenticatedUserId = rebuilt.authenticatedUserId,
                senderVerifyingKeyset = rebuilt.senderVerifyingKeyset,
                expectedSenderKeyBundleId = rebuilt.expectedSenderKeyBundleId,
                envelopePlaintextBytes = rebuilt.envelopeBytes,
                statementBytes = rebuilt.statementBytes,
                signature = rebuilt.signature,
                deliveredCiphertexts = rebuilt.deliveredCiphertexts,
            ),
        )
        assertEquals(RejectionReason.CONTENT_AEAD_INVALID, rejected(result))
    }

    @Test
    fun mismatchedPhotoBlobIdRejects() {
        val capsule = buildPresentationCapsule(photoCount = 3)
        // Build a content manifest whose photo[0] blob id is a different
        // UUID than the signed PHOTO[0] binding. Encrypt under the
        // correct key+routing so the AEAD passes; the gate must reject at
        // the descriptor-vs-signed-binding check.
        val wrongPhoto = ManifestPhoto(
            blobId = UUID.fromString("b1000000-0000-4000-8000-000000000099"),
            ordinal = 0,
            width = 2560,
            height = 1600,
        )
        val photos = listOf(wrongPhoto) + photosForOrdinals(listOf(1, 2))
        val wrongManifest = contentManifestCodec.buildAndEncrypt(
            capsuleKeyset = capsule.keyset,
            routingContext = routing(),
            photos = photos,
            note = null,
        )
        val rebuilt = rebuildWithContentManifest(capsule, wrongManifest)
        val result = gate.verify(
            PresentationAcceptanceInput(
                expectedCapsuleId = rebuilt.expectedCapsuleId,
                authenticatedUserId = rebuilt.authenticatedUserId,
                senderVerifyingKeyset = rebuilt.senderVerifyingKeyset,
                expectedSenderKeyBundleId = rebuilt.expectedSenderKeyBundleId,
                envelopePlaintextBytes = rebuilt.envelopeBytes,
                statementBytes = rebuilt.statementBytes,
                signature = rebuilt.signature,
                deliveredCiphertexts = rebuilt.deliveredCiphertexts,
            ),
        )
        assertEquals(RejectionReason.CONTENT_DESCRIPTORS_MISMATCH, rejected(result))
    }

    @Test
    fun mismatchedPhotoOrdinalRejects() {
        val capsule = buildPresentationCapsule(photoCount = 3)
        // Swap the ordinals in the content manifest's photo descriptors
        // while keeping the blob ids the same. The descriptor ordinal no
        // longer matches the signed PHOTO binding's ordinal.
        val swapped = listOf(
            ManifestPhoto(blobId = photo1BlobId.value, ordinal = 1, width = 2560, height = 1600),
            ManifestPhoto(blobId = photo2BlobId.value, ordinal = 0, width = 2560, height = 1600),
            ManifestPhoto(blobId = photo3BlobId.value, ordinal = 2, width = 2560, height = 1600),
        )
        val wrongManifest = contentManifestCodec.buildAndEncrypt(
            capsuleKeyset = capsule.keyset,
            routingContext = routing(),
            photos = swapped,
            note = null,
        )
        val rebuilt = rebuildWithContentManifest(capsule, wrongManifest)
        val result = gate.verify(
            PresentationAcceptanceInput(
                expectedCapsuleId = rebuilt.expectedCapsuleId,
                authenticatedUserId = rebuilt.authenticatedUserId,
                senderVerifyingKeyset = rebuilt.senderVerifyingKeyset,
                expectedSenderKeyBundleId = rebuilt.expectedSenderKeyBundleId,
                envelopePlaintextBytes = rebuilt.envelopeBytes,
                statementBytes = rebuilt.statementBytes,
                signature = rebuilt.signature,
                deliveredCiphertexts = rebuilt.deliveredCiphertexts,
            ),
        )
        assertEquals(RejectionReason.CONTENT_DESCRIPTORS_MISMATCH, rejected(result))
    }

    @Test
    fun malformedSignedSenderIdRejects() {
        val capsule = buildPresentationCapsule(photoCount = 3)
        val parsed = PublishStatement.parseFrom(capsule.statementBytes)
        val forged = parsed.toBuilder()
            .setSenderUserId(ByteString.copyFrom(ByteArray(15) { 0x0a }))
            .build()
        val bytes = deterministicBytes(forged)
        val signed = signer.sign(senderIdentity.signingPrivateHandle, bytes)
        val envelopeBytes = envelopeFor(bytes, serializeKeyset(capsule.keyset), sha256(bytes))
        val result = gate.verify(
            input(
                capsule,
                envelopeOverride = envelopeBytes,
                statementOverride = bytes,
                signatureOverride = signed.signature,
            ),
        )
        // The malformed signed ID cannot be converted into a typed
        // RoutingContext; the gate catches IllegalArgumentException and
        // returns Rejected. The reason may be ID_MISMATCH (if the
        // canonical verifier passes) or a canonical-control reason if
        // the envelope/statement IDs disagree.
        assertIs<PresentationAcceptanceResult.Rejected>(result)
    }

    @Test
    fun publicVerifiedResultExposesOnlyStatement() {
        val capsule = buildPresentationCapsule(photoCount = 3)
        val result = gate.verify(input(capsule))
        val verified = assertIs<PresentationAcceptanceResult.Verified>(result)
        // The public Verified surface must carry only the verified
        // PublishStatement. It must NOT expose any of: the manifest,
        // photo descriptors, note, keyset, ciphertext bytes, or
        // recognition plaintext.
        val fields = verified::class.java.declaredFields.map { it.name }
        assertEquals(listOf("statement"), fields)
        // Sanity: the statement parses to the expected capsule id.
        val parsedStatement = PublishStatement.parseFrom(capsule.statementBytes)
        assertEquals(parsedStatement.capsuleId, verified.statement.capsuleId)
        assertEquals(parsedStatement.artifactsCount, verified.statement.artifactsCount)
    }

    @Test
    fun canonicalControlRejectionsArePropagatedUnchanged() {
        val capsule = buildPresentationCapsule(photoCount = 3)
        // Empty envelope => canonical control rejects MALFORMED_ENVELOPE.
        val result = gate.verify(input(capsule, envelopeOverride = ByteArray(0)))
        assertEquals(RejectionReason.MALFORMED_ENVELOPE, rejected(result))
    }

    @Test
    fun wrongAuthenticatedUserRejects() {
        val capsule = buildPresentationCapsule(photoCount = 3)
        val result = gate.verify(
            input(
                capsule,
                authenticatedUser = UserId(UUID.fromString("8a888888-8888-4888-8888-888888888888")),
            ),
        )
        assertEquals(RejectionReason.ID_MISMATCH, rejected(result))
    }

    @Test
    fun gateNeverThrowsForAnyInput() {
        // Stress: every override that should produce a Rejected must
        // produce a Rejected, never a thrown exception.
        val capsule = buildPresentationCapsule(photoCount = 3)
        val scenarios: List<Pair<String, () -> PresentationAcceptanceResult>> = listOf(
            "empty envelope" to { gate.verify(input(capsule, envelopeOverride = ByteArray(0))) },
            "empty statement" to { gate.verify(input(capsule, statementOverride = ByteArray(0))) },
            "garbage signature" to { gate.verify(input(capsule, signatureOverride = ByteArray(8))) },
            "wrong user" to {
                gate.verify(
                    input(
                        capsule,
                        authenticatedUser = UserId(UUID.fromString("8a888888-8888-4888-8888-888888888888")),
                    ),
                )
            },
        )
        for ((label, action) in scenarios) {
            assertIs<PresentationAcceptanceResult.Rejected>(action(), "scenario: $label")
        }
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

    /**
     * Rebuilds the publish statement and envelope so the signed content
     * binding matches a custom content manifest's size and SHA-256. The
     * recognition and photo ciphertexts are reused unchanged. This lets a
     * test exercise a failure that occurs after the full-material coverage
     * check (i.e. content AEAD or descriptor mismatch) rather than stopping
     * at the coverage check.
     */
    private data class Rebuilt(
        val expectedCapsuleId: CapsuleId,
        val authenticatedUserId: UserId,
        val senderVerifyingKeyset: KeysetHandle,
        val expectedSenderKeyBundleId: KeyBundleId,
        val envelopeBytes: ByteArray,
        val statementBytes: ByteArray,
        val signature: ByteArray,
        val deliveredCiphertexts: List<DeliveredCiphertext>,
    )

    private fun rebuildWithContentManifest(
        capsule: PresentationCapsule,
        newContentManifest: ByteArray,
    ): Rebuilt {
        val photoCount = capsule.photoCiphertexts.size
        val artifacts = mutableListOf<PublishArtifact>()
        artifacts += PublishArtifact(
            slot = ArtifactSlot(recognitionBlobId, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1),
            ciphertextSize = capsule.recognitionCiphertext.size.toLong(),
            ciphertextSha256 = ByteString.copyFrom(sha256(capsule.recognitionCiphertext)),
        )
        artifacts += PublishArtifact(
            slot = ArtifactSlot(contentBlobId, CapsuleArtifactKind.CONTENT_MANIFEST, -1),
            ciphertextSize = newContentManifest.size.toLong(),
            ciphertextSha256 = ByteString.copyFrom(sha256(newContentManifest)),
        )
        for (dc in capsule.photoCiphertexts) {
            val ordinal = dc.blobId.toProtoBytes().let { proto ->
                when (proto) {
                    photo1BlobId.toProtoBytes() -> 0
                    photo2BlobId.toProtoBytes() -> 1
                    photo3BlobId.toProtoBytes() -> 2
                    photo4BlobId.toProtoBytes() -> 3
                    photo5BlobId.toProtoBytes() -> 4
                    else -> error("unexpected photo blob id")
                }
            }
            artifacts += PublishArtifact(
                slot = ArtifactSlot(dc.blobId, CapsuleArtifactKind.PHOTO, ordinal),
                ciphertextSize = dc.ciphertext.size.toLong(),
                ciphertextSha256 = ByteString.copyFrom(sha256(dc.ciphertext)),
            )
        }
        val success = PublishStatementBuilder.build(
            PublishStatementInput(
                capsuleId, senderUser, recipientUser, senderBundle, recipientBundle,
                1_700_000_000L, artifacts,
            ),
        ) as PublishStatementBuildResult.Success
        val bytes = success.deterministicBytes.toByteArray()
        val signed = signer.sign(senderIdentity.signingPrivateHandle, bytes)
        val envelopeBytes = envelopeFor(bytes, serializeKeyset(capsule.keyset), sha256(bytes))
        val delivered = listOf(DeliveredCiphertext(recognitionBlobId, capsule.recognitionCiphertext)) +
            listOf(DeliveredCiphertext(contentBlobId, newContentManifest)) +
            capsule.photoCiphertexts
        return Rebuilt(
            expectedCapsuleId = capsuleId,
            authenticatedUserId = recipientUser,
            senderVerifyingKeyset = senderVerifyingKeyset(),
            expectedSenderKeyBundleId = senderBundle,
            envelopeBytes = envelopeBytes,
            statementBytes = bytes,
            signature = signed.signature,
            deliveredCiphertexts = delivered,
        )
    }
}
