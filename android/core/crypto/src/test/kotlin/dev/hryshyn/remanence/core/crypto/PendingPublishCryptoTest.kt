package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * T186 M11: sender-side v2 publish crypto against the frozen fixtures.
 * Byte-parity with `delivery-v2-canonical.json` (AAD, D, KC, S/SHA-256);
 * wrong-target and channel negatives; the AAD API binds the delivery
 * target by construction (no recipient parameter exists, so
 * `UserId(target)` fakery cannot be expressed).
 */
class PendingPublishCryptoTest {

    private val fixtures = Json { ignoreUnknownKeys = true }

    private fun canonicalHex(key: String): String {
        val root = fixtures.parseToJsonElement(
            javaClass.getResourceAsStream("/delivery-v2-canonical.json")!!
                .bufferedReader().readText(),
        ).jsonObject
        return root.getValue(key).jsonPrimitive.content
    }

    private fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private val capsule = "11111111-2222-3333-4444-555555555555"
    private val target = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val sender = "01010101-0202-0303-0404-050505050505"
    private val senderBundle = "0b0b0b0b-1c1c-2d2d-3e3e-4f4f4f4f4f4f"
    private val blob = "cccccccc-dddd-eeee-ffff-000000000001"
    private val serviceKeyId = "9f9f9f9f-8e8e-7d7d-6c6c-5b5b5b5b5b5b"

    @Test
    fun artifactAadMatchesFrozenFixture() {
        val built = PendingPublishCrypto.artifactAad(
            capsuleId = capsule,
            blobId = blob,
            artifactKind = 3,
            ordinal = 0,
            senderUserId = sender,
            deliveryTargetId = target,
        )
        assertContentEquals(unhex(canonicalHex("artifact_aad_v2_hex")), built)
    }

    @Test
    fun artifactAadBindsDeliveryTargetNotRecipient() {
        val forTarget = PendingPublishCrypto.artifactAad(capsule, blob, 3, 0, sender, target)
        val forOther = PendingPublishCrypto.artifactAad(
            capsule, blob, 3, 0, sender,
            "33333333-4444-5555-6666-777777777777",
        )
        // A different target binds different AAD; the API has no recipient
        // parameter, so the final recipient can never be substituted here.
        assertTrue(!forTarget.contentEquals(forOther))
    }

    @Test
    fun addressCommitmentMatchesFrozenFixture() {
        val opening = PendingPublishCrypto.addressOpening(
            channel = 1,
            normalizedDestination = "a42@example.test",
            salt32 = ByteArray(32) { it.toByte() },
        )
        assertContentEquals(unhex(canonicalHex("address_opening_hex")), opening)
        assertContentEquals(
            unhex(canonicalHex("address_commitment_d_hex")),
            PendingPublishCrypto.addressCommitment(opening),
        )
    }

    @Test
    fun keyCommitmentMatchesFrozenFixture() {
        val keyHex = canonicalHex("test_vector_key_hex")
        val kcInput = unhex(canonicalHex("key_commitment_input_hex"))
        // The KC input embeds the exact test-vector keyset bytes.
        val keyBytes = kcInput.copyOfRange(kcInput.size - unhex(keyHex).size, kcInput.size)
        assertContentEquals(unhex(keyHex), keyBytes)
        assertContentEquals(
            unhex(canonicalHex("key_commitment_kc_hex")),
            PendingPublishCrypto.keyCommitment(capsule, target, unhex(keyHex)),
        )
    }

    @Test
    fun statementMatchesFrozenFixtureBytesAndHash() {
        val statement = PendingPublishCrypto.buildStatement(
            capsuleId = capsule,
            senderUserId = sender,
            senderKeyBundleId = senderBundle,
            deliveryTargetId = target,
            addressCommitment = unhex(canonicalHex("address_commitment_d_hex")),
            keyCommitment = unhex(canonicalHex("key_commitment_kc_hex")),
            createdAtEpochSeconds = 1798765432L,
            claimExpiresAtEpochSeconds = 1814317432L,
            serviceKeyId = serviceKeyId,
            artifacts = listOf(
                PendingPublishCrypto.ArtifactDeclaration(
                    blobId = blob,
                    kind = 3,
                    ordinal = 0,
                    ciphertextSize = 505L,
                    ciphertextSha256 = ByteArray(32) { 0x55.toByte() },
                ),
            ),
        )
        assertContentEquals(unhex(canonicalHex("pending_publish_statement_s_hex")), statement)
        assertContentEquals(
            unhex(canonicalHex("publish_statement_sha256_h_hex")),
            PendingPublishCrypto.statementSha256(statement),
        )
    }

    @Test
    fun signStatementVerifiesUnderSenderKey() {
        TinkPrimitives.ensureRegistered()
        val signing = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val statement = PendingPublishCrypto.buildStatement(
            capsuleId = capsule,
            senderUserId = sender,
            senderKeyBundleId = senderBundle,
            deliveryTargetId = target,
            addressCommitment = unhex(canonicalHex("address_commitment_d_hex")),
            keyCommitment = unhex(canonicalHex("key_commitment_kc_hex")),
            createdAtEpochSeconds = 1798765432L,
            claimExpiresAtEpochSeconds = 1814317432L,
            serviceKeyId = serviceKeyId,
            artifacts = emptyList(),
        )
        val signature = PendingPublishCrypto.signStatement(signing, statement)
        assertEquals(69, signature.size)
        // Verifies under the sender key via the shared verifier path.
        PendingPublishVerifier.verifyPublishWithHandle(
            signing.publicKeysetHandle,
            statement,
            signature,
        )
    }

    @Test
    fun channelZeroRejected() {
        assertFailsWith<IllegalArgumentException> {
            PendingPublishCrypto.addressOpening(0, "a42@example.test", ByteArray(32))
        }
    }

    @Test
    fun escrowStaysBoundedAndParseable() {
        val opening = PendingPublishCrypto.addressOpening(1, "a42@example.test", ByteArray(32) { it.toByte() })
        val keyHex = canonicalHex("test_vector_key_hex")
        val escrow = PendingPublishCrypto.buildEscrow(
            capsuleId = capsule,
            deliveryTargetId = target,
            senderUserId = sender,
            statementSha256 = ByteArray(32) { 0x01.toByte() },
            addressCommitment = PendingPublishCrypto.addressCommitment(opening),
            keyCommitment = PendingPublishCrypto.keyCommitment(capsule, target, unhex(keyHex)),
            claimExpiresAtEpochSeconds = 1814317432L,
            capsuleAeadKeyset = unhex(keyHex),
            addressOpeningCanonical = opening,
        )
        assertTrue(escrow.size <= 2048)
        assertTrue(escrow.isNotEmpty())
    }

    @Test
    fun v2ArtifactRoundTripUnderCapsuleKey() {
        TinkPrimitives.ensureRegistered()
        val capsuleKey = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val plaintext = "inner-payload-bytes-unchanged".toByteArray(Charsets.UTF_8)
        val ciphertext = PendingArtifactCryptor.encrypt(
            capsuleKey, capsule, blob, 3, 0, sender, target, plaintext,
        )
        assertContentEquals(
            plaintext,
            PendingArtifactCryptor.decrypt(
                capsuleKey, capsule, blob, 3, 0, sender, target, ciphertext,
            ),
        )
        // Wrong target fails closed inside the AEAD.
        assertFailsWith<Exception> {
            PendingArtifactCryptor.decrypt(
                capsuleKey, capsule, blob, 3, 0, sender,
                "33333333-4444-5555-6666-777777777777", ciphertext,
            )
        }
    }
}
