package postmark.core.crypto

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.TinkProtoKeysetFormat
import java.security.GeneralSecurityException
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import postmark.core.model.CapsuleId
import postmark.core.model.CryptoContextEncoder
import postmark.core.model.KeyBundleId
import postmark.core.model.RecipientEnvelopeContextInput
import postmark.core.model.UserId

/**
 * Golden proof for M1-C12 + ADR-006: the canonical envelope context_info is
 * the committed bytes from `protocol/fixtures/recipient-envelope-v1.json`,
 * and the Android HPKE implementation opens the exact tink-python-sealed
 * golden ciphertext to the committed plaintext. Sealing is randomized, so
 * reproducibility is proven on the context_info and interop on the ciphertext.
 */
class RecipientEnvelopeCryptorGoldenTest {

    private val cryptor = RecipientEnvelopeCryptor()
    private val golden = loadFixture()
    private val context = RecipientEnvelopeContextInput(
        capsuleId = CapsuleId(UUID.fromString(contextJson()["capsule_id"]!!.jsonPrimitive.content)),
        senderUserId = UserId(UUID.fromString(contextJson()["sender_user_id"]!!.jsonPrimitive.content)),
        recipientUserId = UserId(UUID.fromString(contextJson()["recipient_user_id"]!!.jsonPrimitive.content)),
        recipientKeyBundleId = KeyBundleId(
            UUID.fromString(contextJson()["recipient_key_bundle_id"]!!.jsonPrimitive.content),
        ),
    )

    private fun loadFixture(): JsonObject =
        Json.parseToJsonElement(
            requireNotNull(
                this::class.java.classLoader.getResourceAsStream("recipient-envelope-v1.json")
                    ?.readBytes()?.decodeToString(),
            ) { "recipient-envelope-v1.json fixture missing on test classpath" },
        ).jsonObject

    private fun contextJson() = golden["context"]!!.jsonObject

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun goldenPlaintext(): ByteArray =
        hexToBytes(golden["envelope_plaintext_hex"]!!.jsonPrimitive.content)

    @BeforeTest
    fun setUp() {
        TinkPrimitives.ensureRegistered()
    }

    private fun privateHandle() = TinkJsonProtoKeysetFormat.parseKeyset(
        golden["fixed_private_keyset_json"]!!.jsonObject.toString(),
        InsecureSecretKeyAccess.get(),
    )

    private fun publicHandle() = TinkJsonProtoKeysetFormat.parseKeysetWithoutSecret(
        golden["fixed_public_keyset_json"]!!.jsonObject.toString(),
    )

    @Test
    fun canonicalContextInfoEncodingMatchesTheCommittedBytes() {
        val info = CryptoContextEncoder.recipientEnvelopeInfo(context).toByteArray()

        assertContentEquals(hexToBytes(golden["expected_context_info_hex"]!!.jsonPrimitive.content), info)
        assertEquals("postmark/envelope/v1", String(info, 0, 20, Charsets.US_ASCII))
        assertEquals(0x00, info[20])
    }

    @Test
    fun opensTheCommittedCrossPlatformCiphertextToTheCommittedPlaintext() {
        val opened = cryptor.open(privateHandle(), context, hexToBytes(golden["golden_ciphertext_hex"]!!.jsonPrimitive.content))

        assertContentEquals(goldenPlaintext(), opened)
    }

    @Test
    fun sealRoundtripsAndCarriesTheProtocolV1WireFraming() {
        val sealed = cryptor.seal(publicHandle(), context, goldenPlaintext())

        assertContentEquals(goldenPlaintext(), cryptor.open(privateHandle(), context, sealed))
        assertEquals(RecipientEnvelopeCryptor.TINK_PREFIX_TYPE_BYTE, sealed[0].toInt())
        assertEquals(1907335810, readEmbeddedKeyId(sealed))
    }

    @Test
    fun wrongRecipientKeysetAndWrongContextFailClosed() {
        val otherPublic = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
            AccountIdentityGenerator().generate().encryptionPublicKeyset,
        )

        // Sealed for a different recipient cannot be opened with this keyset.
        val foreignSealed = cryptor.seal(otherPublic, context, goldenPlaintext())
        assertFailsWith<GeneralSecurityException> {
            cryptor.open(privateHandle(), context, foreignSealed)
        }
        // Committed ciphertext fails under any altered context field.
        val wrongContext = context.copy(capsuleId = CapsuleId(UUID.fromString("22222222-2222-4333-8444-555555555555")))
        assertFailsWith<GeneralSecurityException> {
            cryptor.open(privateHandle(), wrongContext, hexToBytes(golden["golden_ciphertext_hex"]!!.jsonPrimitive.content))
        }
    }

    @Test
    fun strippedPrefixAndTruncatedCiphertextFailClosedBeforeAnyPrimitiveWork() {
        val golden = hexToBytes(this.golden["golden_ciphertext_hex"]!!.jsonPrimitive.content)

        assertFailsWith<GeneralSecurityException> {
            cryptor.open(privateHandle(), context, golden.copyOfRange(5, golden.size))
        }
        assertFailsWith<GeneralSecurityException> {
            cryptor.open(privateHandle(), context, golden.copyOf(golden.size - 1))
        }
        assertFailsWith<IllegalArgumentException> {
            cryptor.seal(publicHandle(), context, ByteArray(0))
        }
    }

    private fun readEmbeddedKeyId(signature: ByteArray): Int =
        ((signature[1].toInt() and 0xFF) shl 24) or ((signature[2].toInt() and 0xFF) shl 16) or
            ((signature[3].toInt() and 0xFF) shl 8) or (signature[4].toInt() and 0xFF)
}
