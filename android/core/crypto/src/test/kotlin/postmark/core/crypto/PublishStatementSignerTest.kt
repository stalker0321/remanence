package postmark.core.crypto

import com.google.crypto.tink.TinkProtoKeysetFormat
import java.security.GeneralSecurityException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Golden proof for M1-C11: the signer consumes the frozen deterministic
 * statement bytes from `protocol/fixtures/publish-statement-v1.json` and the
 * exact `"postmark/publish/v1" || bytes` input; tampering fails closed.
 */
class PublishStatementSignerTest {

    private val signer = PublishStatementSigner()
    private val identity = AccountIdentityGenerator().generate()

    private fun loadFixture(): JsonObject =
        Json.parseToJsonElement(
            requireNotNull(
                this::class.java.classLoader.getResourceAsStream("publish-statement-v1.json")
                    ?.readBytes()?.decodeToString(),
            ) { "publish-statement-v1.json fixture missing on test classpath" },
        ).jsonObject

    private fun goldenStatementBytes(): ByteArray {
        val expected = loadFixture()["expected_deterministic_hex"]!!.jsonPrimitive.content
        return expected.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun publicVerifyingHandle() =
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(identity.signingPublicKeyset)

    @Test
    fun signsGoldenDeterministicBytesAndVerifiesWithPublicHalf() {
        val statementBytes = goldenStatementBytes()

        val signed = signer.sign(identity.signingPrivateHandle, statementBytes)

        assertContentEquals(statementBytes, signed.deterministicStatementBytes)
        assertTrue(signed.signature.size >= 64) // Ed25519 R||S plus optional Tink output prefix
        signer.verify(publicVerifyingHandle(), signed)
    }

    @Test
    fun signingIsDeterministicForFixedInput() {
        val statementBytes = goldenStatementBytes()

        val first = signer.sign(identity.signingPrivateHandle, statementBytes)
        val second = signer.sign(identity.signingPrivateHandle, statementBytes)

        assertContentEquals(first.signature, second.signature)
    }

    @Test
    fun bitFlipInStatementBytesFailsVerification() {
        val signed = signer.sign(identity.signingPrivateHandle, goldenStatementBytes())
        val tampered = signed.deterministicStatementBytes.copyOf()
        tampered[tampered.size / 2] = (tampered[tampered.size / 2].toInt() xor 0x01).toByte()

        assertFailsWith<GeneralSecurityException> {
            signer.verify(publicVerifyingHandle(), SignedPublishStatement(tampered, signed.signature))
        }
    }

    @Test
    fun truncatedSignatureAndForeignSignatureFailClosed() {
        val statementBytes = goldenStatementBytes()
        val signed = signer.sign(identity.signingPrivateHandle, statementBytes)

        val truncated = signed.signature.copyOf(signed.signature.size - 1)
        assertFailsWith<GeneralSecurityException> {
            signer.verify(publicVerifyingHandle(), SignedPublishStatement(statementBytes, truncated))
        }

        val otherIdentity = AccountIdentityGenerator().generate()
        val foreign = signer.sign(otherIdentity.signingPrivateHandle, statementBytes)
        assertFailsWith<GeneralSecurityException> {
            signer.verify(publicVerifyingHandle(), SignedPublishStatement(statementBytes, foreign.signature))
        }
    }

    @Test
    fun doublePrefixAttemptFailsVerification() {
        // The domain prefix is added exactly once by the signer; a payload that
        // already embeds it must never verify as a statement.
        val statementBytes = goldenStatementBytes()
        val signed = signer.sign(identity.signingPrivateHandle, statementBytes)
        val prefixed = PublishStatementSigner.DOMAIN_PREFIX.toByteArray(Charsets.US_ASCII) + statementBytes

        assertFailsWith<GeneralSecurityException> {
            signer.verify(publicVerifyingHandle(), SignedPublishStatement(prefixed, signed.signature))
        }
    }

    @Test
    fun emptyStatementRejectedBeforeSigningOrVerifying() {
        assertFailsWith<IllegalArgumentException> { signer.sign(identity.signingPrivateHandle, ByteArray(0)) }
        assertFailsWith<GeneralSecurityException> {
            signer.verify(identity.signingPrivateHandle, SignedPublishStatement(ByteArray(0), ByteArray(64)))
        }
    }

    @Test
    fun builderOutputFeedsSignerEndToEnd() {
        val json = loadFixture()
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

        val signed = signer.sign(identity.signingPrivateHandle, success.deterministicBytes.toByteArray())
        signer.verify(publicVerifyingHandle(), signed)
    }

    private fun hexToByteString(hex: String): com.google.protobuf.ByteString =
        com.google.protobuf.ByteString.copyFrom(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
}
