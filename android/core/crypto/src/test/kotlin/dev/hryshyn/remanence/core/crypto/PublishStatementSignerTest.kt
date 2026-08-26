package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.TinkProtoKeysetFormat
import java.security.GeneralSecurityException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/**
 * Golden proof for M1-C11 + ADR-007: the signer consumes the frozen
 * deterministic statement bytes from `protocol/fixtures/publish-statement-v1.json`
 * and reproduces the exact committed 69-byte TINK-prefixed Ed25519 signature
 * from `protocol/fixtures/publish-signature-v1.json` using the checked-in
 * non-secret keysets. Tampering fails closed.
 */
class PublishStatementSignerTest {

    private val signer = PublishStatementSigner()
    private val golden = loadGoldenFixture()

    @kotlin.test.BeforeTest
    fun setUp() {
        TinkPrimitives.ensureRegistered()
    }

    private fun loadGoldenFixture(): JsonObject =
        Json.parseToJsonElement(
            requireNotNull(
                this::class.java.classLoader.getResourceAsStream("publish-signature-v1.json")
                    ?.readBytes()?.decodeToString(),
            ) { "publish-signature-v1.json fixture missing on test classpath" },
        ).jsonObject

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun goldenStatementBytes(): ByteArray =
        hexToBytes(golden["expected_deterministic_hex"]!!.jsonPrimitive.content)

    private fun goldenSignatureBytes(): ByteArray =
        hexToBytes(golden["expected_signature_hex"]!!.jsonPrimitive.content)

    private fun fixedSigningKeysetId(): Int = golden["signing_key_id"]!!.jsonPrimitive.content.toInt()

    /** The checked-in non-secret private Ed25519 test keyset (TINK prefix). */
    private fun fixedSigningHandle() = TinkJsonProtoKeysetFormat.parseKeyset(
        golden["fixed_private_keyset_json"]!!.jsonObject.toString(),
        InsecureSecretKeyAccess.get(),
    )

    private fun fixedVerifyingHandle() = TinkJsonProtoKeysetFormat.parseKeysetWithoutSecret(
        golden["fixed_public_keyset_json"]!!.jsonObject.toString(),
    )

    @Test
    fun signsFixedStatementToTheExactCommittedSixtyNineByteVector() {
        val signed = signer.sign(fixedSigningHandle(), goldenStatementBytes())

        assertContentEquals(goldenSignatureBytes(), signed.signature)
        assertEquals(PublishStatementSigner.SIGNATURE_LENGTH, signed.signature.size)
        assertEquals(PublishStatementSigner.TINK_PREFIX_TYPE_BYTE, signed.signature[0])
        assertEquals(fixedSigningKeysetId(), readEmbeddedKeyId(signed.signature))
        assertContentEquals(goldenStatementBytes(), signed.deterministicStatementBytes)
        signer.verify(fixedVerifyingHandle(), signed)
    }

    @Test
    fun verificationAcceptsOnlyTheCommittedVectorForTheFixedStatement() {
        val statementBytes = goldenStatementBytes()
        val signed = signer.sign(fixedSigningHandle(), statementBytes)

        signer.verify(fixedVerifyingHandle(), signed)
        // Re-verification of the committed bytes is stable and deterministic.
        assertContentEquals(goldenSignatureBytes(), signed.signature)
        val second = signer.sign(fixedSigningHandle(), statementBytes)
        assertContentEquals(signed.signature, second.signature)
    }

    @Test
    fun generatedIdentityProducesProtocolV1TinkPrefixedSignatures() {
        val identity = AccountIdentityGenerator().generate()

        val signed = signer.sign(identity.signingPrivateHandle, goldenStatementBytes())

        assertEquals(PublishStatementSigner.SIGNATURE_LENGTH, signed.signature.size)
        assertEquals(PublishStatementSigner.TINK_PREFIX_TYPE_BYTE, signed.signature[0])
        assertEquals(
            identity.signingPrivateHandle.keysetInfo.primaryKeyId,
            readEmbeddedKeyId(signed.signature),
        )
        val verifying = TinkProtoKeysetFormat.parseKeysetWithoutSecret(identity.signingPublicKeyset)
        signer.verify(verifying, signed)
    }

    @Test
    fun bitFlipInStatementBytesFailsVerification() {
        val signed = signer.sign(fixedSigningHandle(), goldenStatementBytes())
        val tampered = signed.deterministicStatementBytes.copyOf()
        tampered[tampered.size / 2] = (tampered[tampered.size / 2].toInt() xor 0x01).toByte()

        assertFailsWith<GeneralSecurityException> {
            signer.verify(fixedVerifyingHandle(), SignedPublishStatement(tampered, signed.signature))
        }
    }

    @Test
    fun truncatedStrippedAndForeignSignaturesFailClosed() {
        val statementBytes = goldenStatementBytes()
        val signed = signer.sign(fixedSigningHandle(), statementBytes)

        // Stripping the 5-byte TINK prefix is not protocol v1; RAW fails closed.
        val stripped = signed.signature.copyOfRange(5, signed.signature.size)
        assertFailsWith<GeneralSecurityException> {
            signer.verify(fixedVerifyingHandle(), SignedPublishStatement(statementBytes, stripped))
        }
        // Truncated TINK output fails closed.
        val truncated = signed.signature.copyOf(signed.signature.size - 1)
        assertFailsWith<GeneralSecurityException> {
            signer.verify(fixedVerifyingHandle(), SignedPublishStatement(statementBytes, truncated))
        }
        // Well-formed length but wrong bytes fails closed.
        val foreign = goldenSignatureBytes().copyOf().also { it[10] = (it[10].toInt() xor 0x01).toByte() }
        assertFailsWith<GeneralSecurityException> {
            signer.verify(fixedVerifyingHandle(), SignedPublishStatement(statementBytes, foreign))
        }
    }

    @Test
    fun subHeaderSignaturesFailClosedAsSecurityExceptionsNotCrashes() {
        val statementBytes = goldenStatementBytes()
        val signed = signer.sign(fixedSigningHandle(), statementBytes)

        // Any signature shorter than the 5-byte TINK header must fail closed
        // with GeneralSecurityException; the structural guard checks length
        // BEFORE indexing the embedded key id (ADR-007).
        (0..4).forEach { size ->
            val exception = assertFailsWith<GeneralSecurityException> {
                signer.verify(fixedVerifyingHandle(), SignedPublishStatement(statementBytes, signed.signature.copyOf(size)))
            }
            assertEquals(
                "signature is not protocol-v1 69-byte TINK-prefixed Ed25519",
                exception.message,
            )
        }
        // Empty signature likewise.
        assertFailsWith<GeneralSecurityException> {
            signer.verify(fixedVerifyingHandle(), SignedPublishStatement(statementBytes, ByteArray(0)))
        }
    }

    @Test
    fun doublePrefixAttemptFailsVerification() {
        // The domain prefix is added exactly once by the signer; a payload that
        // already embeds it must never verify as a statement.
        val statementBytes = goldenStatementBytes()
        val signed = signer.sign(fixedSigningHandle(), statementBytes)
        val prefixed = PublishStatementSigner.DOMAIN_PREFIX.toByteArray(Charsets.US_ASCII) + statementBytes

        assertFailsWith<GeneralSecurityException> {
            signer.verify(fixedVerifyingHandle(), SignedPublishStatement(prefixed, signed.signature))
        }
    }

    @Test
    fun emptyStatementRejectedBeforeSigningOrVerifying() {
        assertFailsWith<IllegalArgumentException> { signer.sign(fixedSigningHandle(), ByteArray(0)) }
        assertFailsWith<GeneralSecurityException> {
            signer.verify(fixedVerifyingHandle(), SignedPublishStatement(ByteArray(0), goldenSignatureBytes()))
        }
    }

    @Test
    fun builderOutputFeedsSignerEndToEndOnTheGoldenPath() {
        val json = Json.parseToJsonElement(
            requireNotNull(
                this::class.java.classLoader.getResourceAsStream("publish-statement-v1.json")
                    ?.readBytes()?.decodeToString(),
            ),
        ).jsonObject
        val capsuleId = CapsuleId(UUID.fromString(json["capsule_id"]!!.jsonPrimitive.content))
        val sender = UserId(UUID.fromString(json["sender_user_id"]!!.jsonPrimitive.content))
        val recipient = UserId(UUID.fromString(json["recipient_user_id"]!!.jsonPrimitive.content))
        val senderBundle = KeyBundleId(UUID.fromString(json["sender_key_bundle_id"]!!.jsonPrimitive.content))
        val recipientBundle = KeyBundleId(UUID.fromString(json["recipient_key_bundle_id"]!!.jsonPrimitive.content))
        val artifacts = json["artifacts"]!!.jsonArray.map { element ->
            val obj = element.jsonObject
            val kind = when (obj["kind"]!!.jsonPrimitive.content) {
                "RECOGNITION_MANIFEST" -> CapsuleArtifactKind.RECOGNITION_MANIFEST
                "CONTENT_MANIFEST" -> CapsuleArtifactKind.CONTENT_MANIFEST
                else -> CapsuleArtifactKind.PHOTO
            }
            val blobHex = obj["blob_id"]!!.jsonPrimitive.content.replace("-", "")
            val blobBytes = blobHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            PublishArtifact(
                slot = ArtifactSlot(
                    blobId = BlobId.fromProtoBytes(com.google.protobuf.ByteString.copyFrom(blobBytes)),
                    kind = kind,
                    ordinal = obj["ordinal"]!!.jsonPrimitive.content.toInt(),
                ),
                ciphertextSize = obj["ciphertext_size"]!!.jsonPrimitive.content.toLong(),
                ciphertextSha256 = hexToByteString(obj["ciphertext_sha256_hex"]!!.jsonPrimitive.content),
            )
        }

        val success = PublishStatementBuilder.build(
            PublishStatementInput(capsuleId, sender, recipient, senderBundle, recipientBundle, 1_700_000_000L, artifacts),
        ) as PublishStatementBuildResult.Success

        assertContentEquals(
            goldenStatementBytes(),
            success.deterministicBytes.toByteArray(),
        )
        val signed = signer.sign(fixedSigningHandle(), success.deterministicBytes.toByteArray())
        assertContentEquals(goldenSignatureBytes(), signed.signature)
        signer.verify(fixedVerifyingHandle(), signed)
    }

    private fun readEmbeddedKeyId(signature: ByteArray): Int =
        ((signature[1].toInt() and 0xFF) shl 24) or ((signature[2].toInt() and 0xFF) shl 16) or
            ((signature[3].toInt() and 0xFF) shl 8) or (signature[4].toInt() and 0xFF)

    private fun hexToByteString(hex: String): com.google.protobuf.ByteString =
        com.google.protobuf.ByteString.copyFrom(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
}
