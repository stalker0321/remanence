package dev.hryshyn.remanence.create

import dev.hryshyn.remanence.protocol.v1.RecipientEnvelopePlaintext
import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.TinkProtoKeysetFormat
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.CapsuleAcceptanceGate
import dev.hryshyn.remanence.core.crypto.CapsuleAcceptanceInput
import dev.hryshyn.remanence.core.crypto.CapsuleAcceptanceResult
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.crypto.CapsuleArtifactCryptor
import dev.hryshyn.remanence.core.crypto.RecognitionManifestCodec
import dev.hryshyn.remanence.protocol.v1.RecognitionManifest
import dev.hryshyn.remanence.core.crypto.DeliveredBlob
import dev.hryshyn.remanence.core.crypto.KekBoundary
import dev.hryshyn.remanence.core.crypto.RecipientEnvelopeCryptor
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.crypto.WrappedKeysetRecord
import dev.hryshyn.remanence.core.data.outbox.OutboxArtifactKind
import dev.hryshyn.remanence.core.model.ArtifactAadInput
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.SenderRetryPurpose
import dev.hryshyn.remanence.core.model.SenderRetryWrapContextInput
import dev.hryshyn.remanence.core.model.UserId

/**
 * I08 end-to-end proof on the JVM with real crypto: publish produces a
 * ciphertext-only capsule whose envelope opens, whose statement passes the
 * acceptance gate, and whose artifacts decrypt under the envelope-carried
 * capsule keyset.
 *
 * M2-P06: the publisher constructor is now IDENTITY-PURE - the three
 * recipient-side fields are explicit, required, and never defaulted from
 * the sender. The "self-send golden bytes" fixture below is the byte-for-byte
 * check that the explicit-equal-value self-send path produces the exact
 * same statement / signature / envelope plaintext as the prior default-based
 * path, and the "distinct" suite proves that genuinely different recipient
 * IDs are independently bound into the statement, the recipient envelope
 * context/AAD, and the PreparedOutboxCapsule routing fields.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapsulePublisherTest {

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-sender-retry-${java.util.UUID.randomUUID()}"
    private lateinit var testWrapper: SenderRetryKeysetWrapper
    private lateinit var publisher: CapsulePublisher
    private val identity = AccountIdentityGenerator().generate()

    private val capsuleId = UUID.fromString("4d111111-2222-4333-8444-555555555555")
    private val userId = UUID.fromString("4d222222-3333-4444-8555-666666666666")
    private val bundleId = UUID.fromString("4d333333-4444-4555-8666-777777777777")

    /** A DIFFERENT second identity used by the cross-identity binding tests. */
    private val otherIdentity = AccountIdentityGenerator().generate()
    private val otherUserId = UUID.fromString("4daaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
    private val otherBundleId = UUID.fromString("4dbbbbbb-cccc-4ddd-8eee-ffffffffffff")
    private val otherOwnerUserId = UUID.fromString("4dccccdd-eeee-4fff-8000-111111111111")

    @Before
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        testKekBoundary.createAes256GcmKey(testAlias)
        testWrapper = SenderRetryKeysetWrapper(testKekBoundary)
        publisher = CapsulePublisher(testWrapper, testAlias)
    }

    private fun selfSendRequest() = CapsulePublishRequest(
        capsuleId = CapsuleId(capsuleId),
        senderUserId = UserId(userId),
        recipientUserId = UserId(userId),
        senderKeyBundleId = KeyBundleId(bundleId),
        recipientKeyBundleId = KeyBundleId(bundleId),
        ownerUserId = userId.toString(),
        senderHandleSnapshot = "mykola",
        createdAtEpochSeconds = 1_700_000_000L,
        photoJpegs = (0 until 3).map { "jpeg-$it".toByteArray() + ByteArray(32) { b -> b.toByte() } },
        photoWidthsPx = listOf(800, 800, 800),
        photoHeightsPx = listOf(600, 600, 600),
        noteUtf8 = "hello self",
        frontFingerprintBytes = "front-fp".toByteArray(),
        signingKeyset = identity.signingPrivateHandle,
        recipientEncryptionPublicKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
            identity.encryptionPublicKeyset,
        ),
    )

    @Test
    fun publishedCapsuleCarriesExactArtifactCardinalityAndEnvelope() {
        val prepared = publisher.publish(selfSendRequest())

        assertEquals(5, prepared.artifacts.size)
        assertEquals(
            listOf(OutboxArtifactKind.RECOGNITION_MANIFEST, OutboxArtifactKind.CONTENT_MANIFEST) +
                List(3) { OutboxArtifactKind.PHOTO },
            prepared.artifacts.map { it.kind },
        )
        assertTrue(prepared.envelopeCiphertext.size > 60)
        assertTrue(prepared.publishStatementBytes.isNotEmpty())
        assertEquals(69, prepared.publishStatementSignature.size)
    }

    @Test
    fun recognitionManifestIsInnerV2FrontOnlyWithProtocolV1Bindings() {
        val request = selfSendRequest()
        val prepared = publisher.publish(request)
        val opened = RecipientEnvelopeCryptor().open(
            identity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CapsuleId(capsuleId), UserId(userId), UserId(userId), KeyBundleId(bundleId),
            ),
            prepared.envelopeCiphertext,
        )
        val envelope = RecipientEnvelopePlaintext.parseFrom(opened)
        assertEquals(1, envelope.protocolVersion)

        val capsuleKeyset = TinkProtoKeysetFormat.parseKeyset(
            envelope.capsuleAeadKeyset.toByteArray(),
            InsecureSecretKeyAccess.get(),
        )
        val recognition = prepared.artifacts.single { it.kind == OutboxArtifactKind.RECOGNITION_MANIFEST }
        val content = RecognitionManifestCodec().decryptAndParse(
            capsuleKeyset,
            RecognitionManifestCodec.RoutingContext(
                CapsuleId(capsuleId),
                BlobId(recognition.blobId),
                UserId(userId),
                UserId(userId),
            ),
            recognition.ciphertext,
        )
        assertEquals(RecognitionManifestCodec.FORMAT_VERSION, content.manifestVersion)
        assertEquals(2, content.manifestVersion)
        assertArrayEquals(request.frontFingerprintBytes, content.frontFingerprint)

        val plaintext = CapsuleArtifactCryptor().decrypt(
            capsuleKeyset,
            ArtifactAadInput(
                CapsuleId(capsuleId),
                BlobId(recognition.blobId),
                CapsuleArtifactKind.RECOGNITION_MANIFEST,
                ordinal = -1,
                senderUserId = UserId(userId),
                recipientUserId = UserId(userId),
            ),
            recognition.ciphertext,
        )
        val manifest = RecognitionManifest.parseFrom(plaintext)
        assertEquals(2, manifest.manifestVersion)
        assertTrue(manifest.hasChooserHint())
        assertFalse(manifest.frontFingerprint.isEmpty)
        assertEquals(4, RecognitionManifest.FRONT_FINGERPRINT_FIELD_NUMBER)
        assertFalse(
            "reserved BACK field 5 must be absent from the recognition wire",
            containsProtoField(plaintext, fieldNumber = 5),
        )
    }

    private fun containsProtoField(bytes: ByteArray, fieldNumber: Int): Boolean {
        var index = 0
        while (index < bytes.size) {
            val key = readVarint(bytes, index)
            index += varintSize(bytes, index)
            val tag = key ushr 3
            if (tag == fieldNumber) return true
            when (key and 7) {
                0 -> index += varintSize(bytes, index)
                1 -> index += 8
                2 -> {
                    val length = readVarint(bytes, index)
                    index += varintSize(bytes, index) + length
                }
                5 -> index += 4
                else -> return false
            }
        }
        return false
    }

    @Test
    fun emptyFrontFingerprintIsRejectedBeforeRetryWrapping() {
        val boundary = CountingKekBoundary()
        val alias = "test-empty-front-${UUID.randomUUID()}"
        boundary.createAes256GcmKey(alias)
        val guardedPublisher = CapsulePublisher(SenderRetryKeysetWrapper(boundary), alias)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            guardedPublisher.publish(selfSendRequest().copy(frontFingerprintBytes = ByteArray(0)))
        }
        assertEquals("front fingerprint is required", failure.message)
        assertEquals(0, boundary.loadCalls)
    }

    @Test
    fun acceptanceGateVerifiesThePublishedCapsuleEndToEnd() {
        val prepared = publisher.publish(selfSendRequest())

        // Open our own envelope to recover capsule keyset + statement binding.
        val opened = RecipientEnvelopeCryptor().open(
            identity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CapsuleId(capsuleId), UserId(userId), UserId(userId), KeyBundleId(bundleId),
            ),
            prepared.envelopeCiphertext,
        )
        val envelopePlaintext = RecipientEnvelopePlaintext.parseFrom(opened)

        val result = CapsuleAcceptanceGate().verify(
            CapsuleAcceptanceInput(
                expectedCapsuleId = CapsuleId(capsuleId),
                authenticatedUserId = UserId(userId),
                senderVerifyingKeyset = com.google.crypto.tink.TinkProtoKeysetFormat.parseKeysetWithoutSecret(
                    identity.signingPublicKeyset,
                ),
                expectedSenderKeyBundleId = KeyBundleId(bundleId),
                envelopePlaintextBytes = opened,
                statementBytes = prepared.publishStatementBytes,
                signature = prepared.publishStatementSignature,
                deliveredBlobs = prepared.artifacts.map { artifact ->
                    DeliveredBlob(
                        BlobId(artifact.blobId),
                        artifact.ciphertext.size.toLong(),
                        sha256(artifact.ciphertext),
                    )
                },
            ),
        )
        val accepted = result as? CapsuleAcceptanceResult.Accepted
            ?: throw AssertionError("gate must accept own capsule: $result")
        assertEquals(5, accepted.statement.artifactsCount)
    }

    @Test
    fun envelopeCarriedKeysetDecryptsPhotoOrdinalZero() {
        val prepared = publisher.publish(selfSendRequest())

        val opened = RecipientEnvelopeCryptor().open(
            identity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CapsuleId(capsuleId), UserId(userId), UserId(userId), KeyBundleId(bundleId),
            ),
            prepared.envelopeCiphertext,
        )
        val capsuleKeyset = TinkProtoKeysetFormat.parseKeyset(
            RecipientEnvelopePlaintext.parseFrom(opened).capsuleAeadKeyset.toByteArray(),
            InsecureSecretKeyAccess.get(),
        )

        val photo = prepared.artifacts.first { it.kind == OutboxArtifactKind.PHOTO }
        val decrypted = CapsuleArtifactCryptor().decrypt(
            capsuleKeyset,
            ArtifactAadInput(
                CapsuleId(capsuleId),
                BlobId(photo.blobId),
                CapsuleArtifactKind.PHOTO,
                ordinal = 0,
                senderUserId = UserId(userId),
                recipientUserId = UserId(userId),
            ),
            photo.ciphertext,
        )
        assertTrue(String(decrypted).startsWith("jpeg-0"))
    }

    @Test
    fun fewerThanThreePhotosIsRejectedBeforeAnyWork() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { publisher.publish(selfSendRequest().copy(photoJpegs = selfSendRequest().photoJpegs.take(2))) }
        }
        Unit
    }

    @Test
    fun photoWidthsTooShortAreRejectedBeforeRetryWrapping() {
        assertPhotoMetadataCardinalityRejected(
            selfSendRequest().copy(photoWidthsPx = listOf(800, 800)),
        )
    }

    @Test
    fun photoWidthsTooLongAreRejectedBeforeRetryWrapping() {
        assertPhotoMetadataCardinalityRejected(
            selfSendRequest().copy(photoWidthsPx = listOf(800, 800, 800, 800)),
        )
    }

    @Test
    fun photoHeightsTooShortAreRejectedBeforeRetryWrapping() {
        assertPhotoMetadataCardinalityRejected(
            selfSendRequest().copy(photoHeightsPx = listOf(600, 600)),
        )
    }

    @Test
    fun photoHeightsTooLongAreRejectedBeforeRetryWrapping() {
        assertPhotoMetadataCardinalityRejected(
            selfSendRequest().copy(photoHeightsPx = listOf(600, 600, 600, 600)),
        )
    }

    private fun assertPhotoMetadataCardinalityRejected(request: CapsulePublishRequest) {
        val boundary = CountingKekBoundary()
        val alias = "test-cardinality-${UUID.randomUUID()}"
        boundary.createAes256GcmKey(alias)
        val guardedPublisher = CapsulePublisher(SenderRetryKeysetWrapper(boundary), alias)

        val failure = assertThrows(IllegalArgumentException::class.java) {
            guardedPublisher.publish(request)
        }
        assertEquals("photo metadata cardinality must match photoJpegs", failure.message)
        assertEquals("retry wrapper must not be invoked on metadata preflight failure", 0, boundary.loadCalls)
    }

    // ------------------------------------------------------------------
    // M2-P06: golden + identity-binding regressions.
    // ------------------------------------------------------------------
    // M2-P06: golden + identity-binding regressions.
    // ------------------------------------------------------------------

    private companion object {
        /**
         * M2-P06 self-send golden bytes. The publisher constructor changed:
         * [CapsulePublishRequest.recipientUserId], [CapsulePublishRequest.recipientKeyBundleId]
         * and [CapsulePublishRequest.ownerUserId] are now EXPLICIT, required,
         * and never inferred from the sender. A self-send now passes the same
         * account VALUES explicitly (no defaulting from the sender identity).
         *
         * The capsule AEAD keyset is freshly generated per call, so the
         * artifact ciphertexts (and therefore the artifact SHA-256s encoded
         * into the publish statement) and the sealed envelope ciphertext are
         * randomized. The golden fixture below stores the bytes that are
         * determined ENTIRELY by the deterministic input fields
         * (capsuleId, identities, timestamp, artifact blob IDs and kinds) so
         * the refactor is regression-tested: the cryptographic framing for a
         * self-send is byte-for-byte identical to the pre-P06 default-based
         * path, and the explicit-equal-value self-send produces the same
         * canonical bytes.
         *
         * - [GOLDEN_SELF_SEND_STATEMENT_HEADER_HEX]: the deterministic
         *   98-byte header of the publish statement (protocol_version,
         *   capsule_id, sender_user_id, recipient_user_id, sender_key_bundle_id,
         *   recipient_key_bundle_id, created_at_epoch_seconds + the tag
         *   opening the artifacts list).
         * - [GOLDEN_SELF_SEND_ENVELOPE_PLAINTEXT_HEADER_HEX]: the
         *   deterministic 92-byte header of the recipient envelope plaintext
         *   (protocol_version, capsule_id, sender_user_id, recipient_user_id,
         *   sender_key_bundle_id, recipient_key_bundle_id).
         */
        const val GOLDEN_SELF_SEND_STATEMENT_HEADER_HEX =
            "080112104d1111112222433384445555555555551a104d22222233334444855566666666666622104d2222223333444485556666666666662a104d33333344444555866677777777777732104d3333334444455586667777777777773880e2cfaa06"
        const val GOLDEN_SELF_SEND_ENVELOPE_PLAINTEXT_HEADER_HEX =
            "080112104d1111112222433384445555555555551a104d22222233334444855566666666666622104d2222223333444485556666666666662a104d33333344444555866677777777777732104d333333444445558666777777777777"
    }

    @Test
    fun selfSendGoldenDeterministicHeaderBytesAreUnchanged() {
        val prepared = publisher.publish(selfSendRequest())

        // ---- STATEMENT: the deterministic 98-byte header is byte-for-byte
        // identical to the pre-P06 golden. The remaining bytes are the
        // per-capsule artifact SHA-256s (variable per fresh capsule keyset)
        // and are covered by structural assertions below.
        assertArrayEquals(
            "self-send publish statement header bytes must be byte-for-byte identical to the pre-P06 golden",
            hexToBytes(GOLDEN_SELF_SEND_STATEMENT_HEADER_HEX),
            prepared.publishStatementBytes.copyOfRange(0, GOLDEN_SELF_SEND_STATEMENT_HEADER_HEX.length / 2),
        )

        // Signature is 69 bytes (1 prefix + 4 key-id + 64 Ed25519) - length
        // is fixed; the value itself varies with the randomized statement.
        assertEquals(
            "self-send Ed25519 signature is the protocol-v1 69-byte TINK-prefixed form",
            69,
            prepared.publishStatementSignature.size,
        )

        // The signature VERIFIES the statement (Ed25519 is deterministic
        // over the message, so the produced signature must always accept).
        val verifying = TinkProtoKeysetFormat.parseKeysetWithoutSecret(identity.signingPublicKeyset)
        dev.hryshyn.remanence.core.crypto.PublishStatementSigner().verify(
            verifying,
            dev.hryshyn.remanence.core.crypto.SignedPublishStatement(
                prepared.publishStatementBytes,
                prepared.publishStatementSignature,
            ),
        )

        // ---- ENVELOPE: the deterministic 92-byte header is byte-for-byte
        // identical to the pre-P06 golden. The remaining bytes are the
        // variable per-capsule keyset and the publishStatementSha256.
        val opened = RecipientEnvelopeCryptor().open(
            identity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CapsuleId(capsuleId), UserId(userId), UserId(userId), KeyBundleId(bundleId),
            ),
            prepared.envelopeCiphertext,
        )
        assertArrayEquals(
            "self-send recipient envelope plaintext header bytes must be byte-for-byte identical to the pre-P06 golden",
            hexToBytes(GOLDEN_SELF_SEND_ENVELOPE_PLAINTEXT_HEADER_HEX),
            opened.copyOfRange(0, GOLDEN_SELF_SEND_ENVELOPE_PLAINTEXT_HEADER_HEX.length / 2),
        )

        // The envelope plaintext also independently carries the self-send
        // sender and recipient IDs as separate but equal UUIDs.
        val envelopeProto = RecipientEnvelopePlaintext.parseFrom(opened)
        assertEquals(
            "envelope plaintext carries the sender user ID",
            userId,
            uuidFromProto(envelopeProto.senderUserId),
        )
        assertEquals(
            "envelope plaintext carries the recipient user ID (equal in self-send)",
            userId,
            uuidFromProto(envelopeProto.recipientUserId),
        )
        assertEquals(
            "envelope plaintext carries the sender key-bundle ID",
            bundleId,
            uuidFromProto(envelopeProto.senderKeyBundleId),
        )
        assertEquals(
            "envelope plaintext carries the recipient key-bundle ID (equal in self-send)",
            bundleId,
            uuidFromProto(envelopeProto.recipientKeyBundleId),
        )
        // publishStatementSha256 in the envelope equals the actual statement sha256.
        assertArrayEquals(
            "envelope binds the publish-statement SHA-256",
            sha256(prepared.publishStatementBytes),
            envelopeProto.publishStatementSha256.toByteArray(),
        )
    }

    @Test
    fun selfSendRoutingFieldsAreBoundIndependently() {
        val prepared = publisher.publish(selfSendRequest())

        // All five routing fields exist and carry the explicit (equal) values.
        assertEquals("prepared.senderUserId", userId, prepared.senderUserId)
        assertEquals("prepared.recipientUserId", userId, prepared.recipientUserId)
        assertEquals("prepared.senderKeyBundleId", bundleId, prepared.senderKeyBundleId)
        assertEquals("prepared.recipientKeyBundleId", bundleId, prepared.recipientKeyBundleId)
        assertEquals("prepared.ownerUserId", userId.toString(), prepared.ownerUserId)
    }

    @Test
    fun distinctRecipientUserIdIsBoundIntoPublishStatementAndEnvelope() {
        val selfSend = publisher.publish(selfSendRequest())
        val cross = publisher.publish(
            selfSendRequest().copy(
                recipientUserId = UserId(otherUserId),
                // Same recipient encryption keyset so the only thing that
                // changes is the canonical routing half - the statement
                // bytes must reflect that, and the envelope AAD must follow.
                recipientEncryptionPublicKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
                    otherIdentity.encryptionPublicKeyset,
                ),
            ),
        )

        // ---- STATEMENT: changing only recipientUserId changes only the
        // recipient_user_id (proto field 4) bytes; sender_user_id (field 3),
        // sender/recipient key-bundle ids, capsule_id, created_at, and every
        // artifact binding are identical to the self-send statement.
        val selfSendStatement = selfSend.publishStatementBytes
        val crossStatement = cross.publishStatementBytes
        assertNotEquals(
            "changing recipient user id must change the publish statement",
            selfSendStatement.toList(),
            crossStatement.toList(),
        )
        assertArrayEquals(
            "sender user id (proto field 3) must be byte-for-byte identical",
            extractProtoBytesField(selfSendStatement, fieldTag = 3),
            extractProtoBytesField(crossStatement, fieldTag = 3),
        )
        assertArrayEquals(
            "capsule id (proto field 2) must be byte-for-byte identical",
            extractProtoBytesField(selfSendStatement, fieldTag = 2),
            extractProtoBytesField(crossStatement, fieldTag = 2),
        )
        assertArrayEquals(
            "sender key-bundle id (proto field 5) must be byte-for-byte identical",
            extractProtoBytesField(selfSendStatement, fieldTag = 5),
            extractProtoBytesField(crossStatement, fieldTag = 5),
        )
        assertArrayEquals(
            "recipient key-bundle id (proto field 6) must be byte-for-byte identical",
            extractProtoBytesField(selfSendStatement, fieldTag = 6),
            extractProtoBytesField(crossStatement, fieldTag = 6),
        )
        // The recipient_user_id field (tag 4) is the OTHER user's 16 bytes.
        assertEquals(
            "recipient user id (proto field 4) is independently bound to the other account",
            otherUserId,
            uuidFromBytes(extractProtoBytesField(crossStatement, fieldTag = 4)),
        )
        // The signature changes because the message changed - Ed25519 is
        // deterministic over the message, so the new signature must differ.
        assertNotEquals(
            "Ed25519 signature must reflect the new statement bytes",
            selfSend.publishStatementSignature.toList(),
            cross.publishStatementSignature.toList(),
        )

        // ---- ROUTING: PreparedOutboxCapsule carries the distinct recipient
        // user id, the same sender, and the same owner.
        assertEquals(userId, cross.senderUserId)
        assertEquals(otherUserId, cross.recipientUserId)
        assertNotEquals(cross.senderUserId, cross.recipientUserId)
        assertEquals(bundleId, cross.senderKeyBundleId)
        assertEquals(bundleId, cross.recipientKeyBundleId)
        assertEquals(userId.toString(), cross.ownerUserId)
    }

    @Test
    fun distinctRecipientKeyBundleIdIsBoundIntoPublishStatement() {
        val selfSend = publisher.publish(selfSendRequest())
        val cross = publisher.publish(
            selfSendRequest().copy(
                recipientKeyBundleId = KeyBundleId(otherBundleId),
                // Keep recipient identity the same so the test isolates the
                // recipient key-bundle binding in the statement.
            ),
        )

        val selfSendStatement = selfSend.publishStatementBytes
        val crossStatement = cross.publishStatementBytes
        assertNotEquals(
            "changing recipient key-bundle id must change the publish statement",
            selfSendStatement.toList(),
            crossStatement.toList(),
        )
        // The recipient_key_bundle_id field (tag 6) is the OTHER bundle's 16 bytes.
        assertEquals(
            "recipient key-bundle id (proto field 6) is independently bound to the other bundle",
            otherBundleId,
            uuidFromBytes(extractProtoBytesField(crossStatement, fieldTag = 6)),
        )
        // The recipient_user_id (tag 4) is unchanged in this scenario.
        assertArrayEquals(
            "recipient user id (proto field 4) must remain the self-send value",
            extractProtoBytesField(selfSendStatement, fieldTag = 4),
            extractProtoBytesField(crossStatement, fieldTag = 4),
        )
        // The signature changes because the message changed.
        assertNotEquals(
            "Ed25519 signature must reflect the new statement bytes",
            selfSend.publishStatementSignature.toList(),
            cross.publishStatementSignature.toList(),
        )

        // Routing field is updated too.
        assertEquals(bundleId, cross.senderKeyBundleId)
        assertEquals(otherBundleId, cross.recipientKeyBundleId)
        assertNotEquals(cross.senderKeyBundleId, cross.recipientKeyBundleId)
    }

    @Test
    fun distinctOwnerUserIdRefusedBeforeCryptoBecauseSenderOwnsRetryKey() {
        // M2-P08: ownerUserId must equal senderUserId; a different owner
        // is refused before any crypto work begins because the current
        // sender owns the retry key.
        assertThrows(IllegalArgumentException::class.java) {
            publisher.publish(
                selfSendRequest().copy(
                    ownerUserId = otherOwnerUserId.toString(),
                ),
            )
        }
    }

    @Test
    fun distinctRecipientIdentityBindsEnvelopeAadSoOnlyTheBoundRecipientCanOpen() {
        val cross = publisher.publish(
            selfSendRequest().copy(
                recipientUserId = UserId(otherUserId),
                recipientEncryptionPublicKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
                    otherIdentity.encryptionPublicKeyset,
                ),
            ),
        )

        // The bound RECIPIENT's private key + the distinct recipient
        // context opens the envelope.
        val opened = RecipientEnvelopeCryptor().open(
            otherIdentity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CapsuleId(capsuleId), UserId(userId), UserId(otherUserId), KeyBundleId(bundleId),
            ),
            cross.envelopeCiphertext,
        )
        val env = RecipientEnvelopePlaintext.parseFrom(opened)
        assertEquals(
            "envelope plaintext reflects the distinct recipient user id",
            otherUserId,
            uuidFromProto(env.recipientUserId),
        )
        assertEquals(
            "envelope plaintext reflects the distinct sender user id",
            userId,
            uuidFromProto(env.senderUserId),
        )
        assertNotEquals(
            "envelope plaintext sender and recipient are separate",
            env.senderUserId,
            env.recipientUserId,
        )

        // The SENDER's private key, asked to open the recipient-bound
        // envelope, FAILS - the recipient identity is genuinely bound into
        // the envelope AAD, not conflated with the sender.
        val wrongOpen = runCatching {
            RecipientEnvelopeCryptor().open(
                identity.encryptionPrivateHandle,
                dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                    CapsuleId(capsuleId), UserId(userId), UserId(otherUserId), KeyBundleId(bundleId),
                ),
                cross.envelopeCiphertext,
            )
        }
        assertTrue(
            "sender's private key must NOT open an envelope addressed to another user",
            wrongOpen.isFailure,
        )

        // Same recipient, but with the WRONG context (claiming the user-id
        // matches the sender) also fails - the recipient identity is bound
        // independently into the AAD, not the sender's.
        val wrongContext = runCatching {
            RecipientEnvelopeCryptor().open(
                otherIdentity.encryptionPrivateHandle,
                dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                    CapsuleId(capsuleId), UserId(userId), UserId(userId), KeyBundleId(bundleId),
                ),
                cross.envelopeCiphertext,
            )
        }
        assertTrue(
            "envelope addressed to otherUserId must reject a context claiming self-send",
            wrongContext.isFailure,
        )
    }

    @Test
    fun distinctRecipientIdentityBindsArtifactAadSoOnlyTheBoundRecipientCanDecrypt() {
        val cross = publisher.publish(
            selfSendRequest().copy(
                recipientUserId = UserId(otherUserId),
                recipientEncryptionPublicKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
                    otherIdentity.encryptionPublicKeyset,
                ),
            ),
        )

        // Open the recipient envelope to get the capsule AEAD keyset.
        val opened = RecipientEnvelopeCryptor().open(
            otherIdentity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CapsuleId(capsuleId), UserId(userId), UserId(otherUserId), KeyBundleId(bundleId),
            ),
            cross.envelopeCiphertext,
        )
        val capsuleKeyset = TinkProtoKeysetFormat.parseKeyset(
            RecipientEnvelopePlaintext.parseFrom(opened).capsuleAeadKeyset.toByteArray(),
            InsecureSecretKeyAccess.get(),
        )

        val photo = cross.artifacts.first { it.kind == OutboxArtifactKind.PHOTO }

        // Right recipient identity + right keyset = decrypts.
        val decrypted = CapsuleArtifactCryptor().decrypt(
            capsuleKeyset,
            ArtifactAadInput(
                CapsuleId(capsuleId),
                BlobId(photo.blobId),
                CapsuleArtifactKind.PHOTO,
                ordinal = 0,
                senderUserId = UserId(userId),
                recipientUserId = UserId(otherUserId),
            ),
            photo.ciphertext,
        )
        assertTrue(String(decrypted).startsWith("jpeg-0"))

        // Self-send context (claiming the recipient is the sender) MUST
        // fail: the artifact AAD independently binds the recipient user id.
        val wrongAad = runCatching {
            CapsuleArtifactCryptor().decrypt(
                capsuleKeyset,
                ArtifactAadInput(
                    CapsuleId(capsuleId),
                    BlobId(photo.blobId),
                    CapsuleArtifactKind.PHOTO,
                    ordinal = 0,
                    senderUserId = UserId(userId),
                    recipientUserId = UserId(userId),
                ),
                photo.ciphertext,
            )
        }
        assertTrue(
            "artifact AAD with a self-send context must not decrypt a cross-identity ciphertext",
            wrongAad.isFailure,
        )
    }

    @Test
    fun distinctRecipientIdentityAcceptedByAcceptanceGateWhileSelfSendContextIsRejected() {
        val cross = publisher.publish(
            selfSendRequest().copy(
                recipientUserId = UserId(otherUserId),
                recipientEncryptionPublicKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
                    otherIdentity.encryptionPublicKeyset,
                ),
            ),
        )

        val opened = RecipientEnvelopeCryptor().open(
            otherIdentity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CapsuleId(capsuleId), UserId(userId), UserId(otherUserId), KeyBundleId(bundleId),
            ),
            cross.envelopeCiphertext,
        )

        val distinctResult = CapsuleAcceptanceGate().verify(
            CapsuleAcceptanceInput(
                expectedCapsuleId = CapsuleId(capsuleId),
                authenticatedUserId = UserId(otherUserId),
                senderVerifyingKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
                    identity.signingPublicKeyset,
                ),
                expectedSenderKeyBundleId = KeyBundleId(bundleId),
                envelopePlaintextBytes = opened,
                statementBytes = cross.publishStatementBytes,
                signature = cross.publishStatementSignature,
                deliveredBlobs = cross.artifacts.map { artifact ->
                    DeliveredBlob(
                        BlobId(artifact.blobId),
                        artifact.ciphertext.size.toLong(),
                        sha256(artifact.ciphertext),
                    )
                },
            ),
        )
        assertTrue(
            "acceptance gate accepts the distinct recipient identity when the bound IDs are presented",
            distinctResult is CapsuleAcceptanceResult.Accepted,
        )

        // Same opened envelope + same blobs but the gate is asked to verify
        // as if the recipient were the sender: the distinct statement bytes
        // and signature cannot satisfy a self-send (sender==recipient) claim.
        val selfClaim = runCatching {
            CapsuleAcceptanceGate().verify(
                CapsuleAcceptanceInput(
                    expectedCapsuleId = CapsuleId(capsuleId),
                    authenticatedUserId = UserId(userId),
                    senderVerifyingKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
                        identity.signingPublicKeyset,
                    ),
                    expectedSenderKeyBundleId = KeyBundleId(bundleId),
                    envelopePlaintextBytes = opened,
                    statementBytes = cross.publishStatementBytes,
                    signature = cross.publishStatementSignature,
                    deliveredBlobs = cross.artifacts.map { artifact ->
                        DeliveredBlob(
                            BlobId(artifact.blobId),
                            artifact.ciphertext.size.toLong(),
                            sha256(artifact.ciphertext),
                        )
                    },
                ),
            )
        }
        assertTrue(
            "self-send claim must be rejected for a cross-identity publication",
            selfClaim.isFailure || selfClaim.getOrNull() is CapsuleAcceptanceResult.Rejected,
        )
    }

    @Test
    fun recipientIdentityIsRequiredAndCannotBeOmittedAtConstruction() {
        // M2-P06: the data-class toString() prints every property name with
        // its value, so the explicit-equal self-send MUST show all three
        // recipient-side fields as named properties (not null, not blank).
        // The compile-time enforcement is the constructor itself: every
        // field is declared `val name: Type` with no `= default` clause, so
        // any caller that omits one fails to compile. The runtime assertion
        // here is the textual anchor that confirms the construction
        // surfaced the three fields as independent values.
        val asString = selfSendRequest().toString()
        assertTrue(
            "CapsulePublishRequest.toString must surface recipientUserId as a named property",
            "recipientUserId=" in asString,
        )
        assertTrue(
            "CapsulePublishRequest.toString must surface recipientKeyBundleId as a named property",
            "recipientKeyBundleId=" in asString,
        )
        assertTrue(
            "CapsulePublishRequest.toString must surface ownerUserId as a named property",
            "ownerUserId=" in asString,
        )
        assertTrue(
            "CapsulePublishRequest.toString must surface frontFingerprintBytes as a named property",
            "frontFingerprintBytes=" in asString,
        )
        assertFalse(
            "CapsulePublishRequest must not carry a back fingerprint field",
            "backFingerprint" in asString,
        )
        // Sanity: the request was actually constructed with explicit values.
        assertEquals(UserId(userId), selfSendRequest().recipientUserId)
        assertEquals(KeyBundleId(bundleId), selfSendRequest().recipientKeyBundleId)
        assertEquals(userId.toString(), selfSendRequest().ownerUserId)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex must have even length" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    /**
     * Decodes the first occurrence of a length-delimited proto field with the
     * given tag from a serialized protobuf. This is a minimal decoder scoped
     * to the deterministic publish statement used by the publisher; it
     * tolerates neither nested submessages nor any field type other than
     * length-delimited bytes.
     */
    private fun extractProtoBytesField(bytes: ByteArray, fieldTag: Int): ByteArray {
        val expectedKey = (fieldTag shl 3) or 2 // wire type 2 = length-delimited
        var index = 0
        while (index < bytes.size) {
            val key = readVarint(bytes, index)
            val keySize = varintSize(bytes, index)
            val wireType = key and 0x7
            val fieldNumber = key ushr 3
            index += keySize
            if (fieldNumber == fieldTag && wireType == 2) {
                val length = readVarint(bytes, index)
                val lengthSize = varintSize(bytes, index)
                val start = index + lengthSize
                return bytes.copyOfRange(start, start + length)
            }
            when (wireType) {
                0 -> {
                    val consumed = varintSize(bytes, index)
                    index += consumed
                }
                1 -> index += 8
                2 -> {
                    val length = readVarint(bytes, index)
                    index += varintSize(bytes, index) + length
                }
                5 -> index += 4
                else -> fail("unsupported wire type $wireType in publish statement")
            }
        }
        fail("field $fieldTag not found in publish statement of size ${bytes.size}")
        error("unreachable")
    }

    private fun readVarint(bytes: ByteArray, offset: Int): Int {
        var result = 0
        var shift = 0
        var i = offset
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xff
            result = result or ((b and 0x7f) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
            i += 1
        }
        fail("unterminated varint in publish statement")
        error("unreachable")
    }

    private fun varintSize(bytes: ByteArray, offset: Int): Int {
        var i = offset
        while (i < bytes.size) {
            if ((bytes[i].toInt() and 0x80) == 0) return i - offset + 1
            i += 1
        }
        fail("unterminated varint in publish statement")
        error("unreachable")
    }

    private fun uuidFromProto(bytes: com.google.protobuf.ByteString): UUID {
        val raw = bytes.toByteArray()
        require(raw.size == 16) { "expected 16-byte UUID proto, got ${raw.size} bytes" }
        // The protocol encodes UUIDs in BIG-ENDIAN for both msb and lsb
        // (ProtocolUuid.toProtoBytes). Standard Java UUID binary layout is
        // mixed-endian, so use the protocol's readBigEndian rather than
        // assembling msb/lsb from low-byte-first.
        val msb = ((raw[0].toLong() and 0xff) shl 56) or
            ((raw[1].toLong() and 0xff) shl 48) or
            ((raw[2].toLong() and 0xff) shl 40) or
            ((raw[3].toLong() and 0xff) shl 32) or
            ((raw[4].toLong() and 0xff) shl 24) or
            ((raw[5].toLong() and 0xff) shl 16) or
            ((raw[6].toLong() and 0xff) shl 8) or
            (raw[7].toLong() and 0xff)
        val lsb = ((raw[8].toLong() and 0xff) shl 56) or
            ((raw[9].toLong() and 0xff) shl 48) or
            ((raw[10].toLong() and 0xff) shl 40) or
            ((raw[11].toLong() and 0xff) shl 32) or
            ((raw[12].toLong() and 0xff) shl 24) or
            ((raw[13].toLong() and 0xff) shl 16) or
            ((raw[14].toLong() and 0xff) shl 8) or
            (raw[15].toLong() and 0xff)
        return UUID(msb, lsb)
    }

    private fun uuidFromBytes(bytes: ByteArray): UUID {
        require(bytes.size == 16) { "expected 16-byte UUID, got ${bytes.size} bytes" }
        return uuidFromProto(com.google.protobuf.ByteString.copyFrom(bytes))
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    // ── M2-P08 sender-retry wrapping focused tests ──────────────────────

    /**
     * M2-P08: the publisher produces a non-null, parseable
     * WrappedKeysetRecord in senderRetryWrappedKeysetBytes. The record
     * carries the correct alias, a 12-byte nonce, and non-empty wrapped
     * keyset ciphertext.
     */
    @Test
    fun retryWrappedRecordIsNonNullAndParseable() {
        val prepared = publisher.publish(selfSendRequest())

        assertNotNull("senderRetryWrappedKeysetBytes must be non-null", prepared.senderRetryWrappedKeysetBytes)
        val bytes = prepared.senderRetryWrappedKeysetBytes!!
        assertTrue("retry bytes must be non-empty", bytes.isNotEmpty())

        val record = WrappedKeysetRecord.parse(bytes)
        assertEquals(testAlias, record.alias)
        assertEquals(WrappedKeysetRecord.FORMAT_VERSION_1, record.formatVersion)
        assertEquals(WrappedKeysetRecord.NONCE_SIZE, record.nonce.size)
        assertTrue("wrapped keyset payload must be non-empty", record.wrappedKeyset.isNotEmpty())
    }

    /**
     * M2-P08: unwrap recovers the EXACT capsule keyset used for the
     * artifacts. The unwrapped keyset must be byte-identical to the one
     * the publisher generated — same key material, same operations.
     */
    @Test
    fun unwrapRecoversExactCapsuleKeyUsedForArtifacts() {
        val prepared = publisher.publish(selfSendRequest())

        val record = WrappedKeysetRecord.parse(prepared.senderRetryWrappedKeysetBytes!!)
        val context = SenderRetryWrapContextInput(
            ownerUserId = UserId(userId),
            capsuleId = CapsuleId(capsuleId),
            senderKeyBundleId = KeyBundleId(bundleId),
            purpose = SenderRetryPurpose.RECIPIENT_KEY_STALE_REWRAP,
        )
        val unwrappedKeyset = testWrapper.unwrap(record, context)

        // The unwrapped keyset must decrypt artifact photo-0, proving
        // it is the exact same key material the publisher used.
        val photo = prepared.artifacts.first { it.kind == OutboxArtifactKind.PHOTO }
        val decrypted = CapsuleArtifactCryptor().decrypt(
            unwrappedKeyset,
            ArtifactAadInput(
                CapsuleId(capsuleId),
                BlobId(photo.blobId),
                CapsuleArtifactKind.PHOTO,
                ordinal = 0,
                senderUserId = UserId(userId),
                recipientUserId = UserId(userId),
            ),
            photo.ciphertext,
        )
        assertTrue(String(decrypted).startsWith("jpeg-0"))
    }

    /**
     * M2-P08: wrong context (wrong owner / wrong capsule / wrong sender
     * bundle) must fail unwrap. The AAD binding prevents any cross-
     * context unwrap.
     */
    @Test
    fun unwrapWithWrongContextFails() {
        val prepared = publisher.publish(selfSendRequest())
        val record = WrappedKeysetRecord.parse(prepared.senderRetryWrappedKeysetBytes!!)
        val correctContext = SenderRetryWrapContextInput(
            ownerUserId = UserId(userId),
            capsuleId = CapsuleId(capsuleId),
            senderKeyBundleId = KeyBundleId(bundleId),
            purpose = SenderRetryPurpose.RECIPIENT_KEY_STALE_REWRAP,
        )

        // Wrong owner.
        val wrongOwner = runCatching {
            testWrapper.unwrap(
                record,
                correctContext.copy(ownerUserId = UserId(otherUserId)),
            )
        }
        assertTrue("unwrap must fail with wrong owner", wrongOwner.isFailure)

        // Wrong capsule.
        val wrongCapsule = runCatching {
            testWrapper.unwrap(
                record,
                correctContext.copy(capsuleId = CapsuleId(UUID.randomUUID())),
            )
        }
        assertTrue("unwrap must fail with wrong capsule", wrongCapsule.isFailure)

        // Wrong sender bundle.
        val wrongBundle = runCatching {
            testWrapper.unwrap(
                record,
                correctContext.copy(senderKeyBundleId = KeyBundleId(otherBundleId)),
            )
        }
        assertTrue("unwrap must fail with wrong sender bundle", wrongBundle.isFailure)
    }

    /**
     * M2-P08: self-send golden artifact/envelope bytes remain unchanged
     * except for the nondeterministic retry record. The deterministic
     * statement header and envelope plaintext header are byte-for-byte
     * identical to the pre-P08 golden.
     */
    @Test
    fun selfSendGoldenBytesUnchangedExceptRetryRecord() {
        val prepared = publisher.publish(selfSendRequest())

        // Statement header is unchanged.
        assertArrayEquals(
            hexToBytes(GOLDEN_SELF_SEND_STATEMENT_HEADER_HEX),
            prepared.publishStatementBytes.copyOfRange(0, GOLDEN_SELF_SEND_STATEMENT_HEADER_HEX.length / 2),
        )

        // Envelope plaintext header is unchanged.
        val opened = RecipientEnvelopeCryptor().open(
            identity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CapsuleId(capsuleId), UserId(userId), UserId(userId), KeyBundleId(bundleId),
            ),
            prepared.envelopeCiphertext,
        )
        assertArrayEquals(
            hexToBytes(GOLDEN_SELF_SEND_ENVELOPE_PLAINTEXT_HEADER_HEX),
            opened.copyOfRange(0, GOLDEN_SELF_SEND_ENVELOPE_PLAINTEXT_HEADER_HEX.length / 2),
        )

        // Retry record is present and non-null.
        assertNotNull("retry record must be present in golden self-send", prepared.senderRetryWrappedKeysetBytes)
        assertTrue(prepared.senderRetryWrappedKeysetBytes!!.isNotEmpty())
    }

    /**
     * M2-P08: distinct recipient works — the retry record is bound to
     * the sender's own context and is independent of the recipient.
     */
    @Test
    fun distinctRecipientProducesValidRetryRecord() {
        val prepared = publisher.publish(
            selfSendRequest().copy(
                recipientUserId = UserId(otherUserId),
                recipientEncryptionPublicKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
                    otherIdentity.encryptionPublicKeyset,
                ),
            ),
        )

        assertNotNull(prepared.senderRetryWrappedKeysetBytes)
        val record = WrappedKeysetRecord.parse(prepared.senderRetryWrappedKeysetBytes!!)
        assertEquals(testAlias, record.alias)

        // Unwrap with the sender's context still succeeds.
        val context = SenderRetryWrapContextInput(
            ownerUserId = UserId(userId),
            capsuleId = CapsuleId(capsuleId),
            senderKeyBundleId = KeyBundleId(bundleId),
            purpose = SenderRetryPurpose.RECIPIENT_KEY_STALE_REWRAP,
        )
        val unwrapped = testWrapper.unwrap(record, context)
        // The unwrapped keyset must decrypt the cross-recipient artifact.
        val photo = prepared.artifacts.first { it.kind == OutboxArtifactKind.PHOTO }
        val decrypted = CapsuleArtifactCryptor().decrypt(
            unwrapped,
            ArtifactAadInput(
                CapsuleId(capsuleId),
                BlobId(photo.blobId),
                CapsuleArtifactKind.PHOTO,
                ordinal = 0,
                senderUserId = UserId(userId),
                recipientUserId = UserId(otherUserId),
            ),
            photo.ciphertext,
        )
        assertTrue(String(decrypted).startsWith("jpeg-0"))
    }

    /**
     * M2-P08: no raw serialized capsule keyset occurs in the prepared
     * output — the retry record is the ONLY place the key material
     * appears (outside the envelope), and it is wrapped. A raw byte
     * search of all prepared bytes must not find the serialized keyset.
     */
    @Test
    fun noRawCapsuleKeysetInPreparedOutput() {
        val prepared = publisher.publish(selfSendRequest())

        // Serialize the actual capsule keyset (the same one the publisher
        // generated) and search for it in every output surface.
        // We can't access the publisher's internal capsuleKeyset, but we
        // can open the envelope to recover it and verify it does NOT
        // appear as a substring in any artifact.
        val opened = RecipientEnvelopeCryptor().open(
            identity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CapsuleId(capsuleId), UserId(userId), UserId(userId), KeyBundleId(bundleId),
            ),
            prepared.envelopeCiphertext,
        )
        val capsuleKeysetBytes = RecipientEnvelopePlaintext.parseFrom(opened).capsuleAeadKeyset.toByteArray()

        // The raw capsule keyset must NOT appear in any artifact ciphertext.
        for (artifact in prepared.artifacts) {
            val idx = indexOf(artifact.ciphertext, capsuleKeysetBytes)
            assertTrue(
                "raw capsule keyset must not appear in artifact ${artifact.kind}",
                idx < 0,
            )
        }
        // The raw capsule keyset must NOT appear in the statement bytes.
        val stmtIdx = indexOf(prepared.publishStatementBytes, capsuleKeysetBytes)
        assertTrue("raw capsule keyset must not appear in statement", stmtIdx < 0)
        // The raw capsule keyset must NOT appear in the envelope ciphertext
        // (it's inside the envelope, but the ciphertext itself shouldn't
        // contain the plaintext keyset bytes).
        val envIdx = indexOf(prepared.envelopeCiphertext, capsuleKeysetBytes)
        assertTrue("raw capsule keyset must not appear in envelope ciphertext", envIdx < 0)
        // M2-P08: the sender retry wrapped keyset record is the primary
        // new storage surface for the capsule keyset material. The raw
        // serialized keyset must NOT appear as a substring of the opaque
        // retry bytes — only the wrapped ciphertext may be present.
        val retryIdx = indexOf(prepared.senderRetryWrappedKeysetBytes!!, capsuleKeysetBytes)
        assertTrue(
            "raw capsule keyset must not appear in senderRetryWrappedKeysetBytes",
            retryIdx < 0,
        )
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private class CountingKekBoundary : KekBoundary {
        private val delegate = SoftwareKekBoundary()
        var loadCalls: Int = 0
            private set

        override fun hasKey(alias: String): Boolean = delegate.hasKey(alias)

        override fun createAes256GcmKey(alias: String) {
            delegate.createAes256GcmKey(alias)
        }

        override fun loadKekAead(alias: String): Aead {
            loadCalls++
            return delegate.loadKekAead(alias)
        }
    }

    /**
     * M2-P08 security canary: publish a real capsule through
     * CapsulePublisher, stage it through CapsuleOutboxStager, read the
     * actual .pwks file referenced by senderRetryKeysetPath, and prove
     * the raw serialized capsule keyset is absent from those persisted
     * bytes. The retry record is the primary new storage surface; the
     * canary ensures no plaintext keyset material leaks into it on
     * disk.
     */
    @Test
    fun persistedRetryFileContainsNoRawCapsuleKeyset() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val stagingDir = java.io.File(context.filesDir, "retry-canary-staging-${System.nanoTime()}")
        var database: dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase? = null
        try {
            stagingDir.mkdirs()
            val roots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingDir)
            val retryStore = dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore(roots)

            // In-memory DB: no on-disk file to mis-path; the only durable
            // artefact under test is the .pwks file on the staging roots.
            database = androidx.room.Room.inMemoryDatabaseBuilder(
                context,
                dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase::class.java,
            ).allowMainThreadQueries().build()

            val stager = dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager(database, roots, retryStore)

            val prepared = publisher.publish(selfSendRequest())
            kotlinx.coroutines.runBlocking { stager.stage(prepared) }

            // Recover the raw capsule keyset from the envelope.
            val opened = RecipientEnvelopeCryptor().open(
                identity.encryptionPrivateHandle,
                dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                    CapsuleId(capsuleId), UserId(userId), UserId(userId), KeyBundleId(bundleId),
                ),
                prepared.envelopeCiphertext,
            )
            val capsuleKeysetBytes = RecipientEnvelopePlaintext.parseFrom(opened).capsuleAeadKeyset.toByteArray()

            // Read the entity and the actual .pwks file on disk.
            val entity = kotlinx.coroutines.runBlocking {
                database.outboxCapsuleDao()
                    .getByCapsuleIdAndOwner(capsuleId.toString(), userId.toString())
            }!!
            assertNotNull("senderRetryKeysetPath must be set", entity.senderRetryKeysetPath)
            val pwksFile = java.io.File(entity.senderRetryKeysetPath!!)
            assertTrue("retry .pwks file must exist on disk", pwksFile.exists())
            val persistedBytes = pwksFile.readBytes()

            // The raw serialized capsule keyset must NOT be a substring of
            // the persisted .pwks bytes — only the KEK-wrapped ciphertext
            // may be present.
            val idx = indexOf(persistedBytes, capsuleKeysetBytes)
            assertTrue(
                "raw capsule keyset must not appear in persisted .pwks file (found at offset $idx)",
                idx < 0,
            )
        } finally {
            database?.close()
            stagingDir.deleteRecursively()
        }
    }
}
