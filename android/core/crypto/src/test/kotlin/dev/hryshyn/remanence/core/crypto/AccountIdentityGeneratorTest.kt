package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.proto.Ed25519PrivateKey
import com.google.crypto.tink.proto.KeyData
import com.google.crypto.tink.proto.Keyset
import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AccountIdentityGeneratorTest {

    private val generator = AccountIdentityGenerator()

    private fun serializePrivate(handle: com.google.crypto.tink.KeysetHandle): ByteArray =
        TinkProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get())

    private fun parseKeyset(serialized: ByteArray): Keyset = Keyset.parseFrom(serialized)

    @Test
    fun encryptionAndSigningKeysetsAreIndependent() {
        val identity = generator.generate()
        assertNotEquals(
            serializePrivate(identity.encryptionPrivateHandle).toList(),
            serializePrivate(identity.signingPrivateHandle).toList(),
        )
    }

    @Test
    fun generatedIdentitiesDifferBetweenCalls() {
        val first = generator.generate()
        val second = generator.generate()
        assertNotEquals(first.encryptionPublicKeyset.toList(), second.encryptionPublicKeyset.toList())
        assertNotEquals(first.signingPublicKeyset.toList(), second.signingPublicKeyset.toList())
    }

    @Test
    fun keysetsUseExactDocumentedSuites() {
        val identity = generator.generate()

        val encryption = parseKeyset(serializePrivate(identity.encryptionPrivateHandle))
        assertEquals(1, encryption.keyList.size)
        assertTrue(encryption.primaryKeyId == encryption.keyList[0].keyId)
        val encryptionKey = encryption.keyList[0]
        assertEquals(KeyData.KeyMaterialType.ASYMMETRIC_PRIVATE, encryptionKey.keyData.keyMaterialType)
        assertEquals(AccountIdentityGenerator.HPKE_KEY_TYPE_URL, encryptionKey.keyData.typeUrl)
        val hpkePrivate = com.google.crypto.tink.proto.HpkePrivateKey.parseFrom(encryptionKey.keyData.value)
        val hpkeParams = hpkePrivate.publicKey.params
        assertEquals(com.google.crypto.tink.proto.HpkeKem.DHKEM_X25519_HKDF_SHA256, hpkeParams.kem)
        assertEquals(com.google.crypto.tink.proto.HpkeKdf.HKDF_SHA256, hpkeParams.kdf)
        assertEquals(com.google.crypto.tink.proto.HpkeAead.AES_256_GCM, hpkeParams.aead)

        val signing = parseKeyset(serializePrivate(identity.signingPrivateHandle))
        assertEquals(1, signing.keyList.size)
        assertTrue(signing.primaryKeyId == signing.keyList[0].keyId)
        val signingKey = signing.keyList[0]
        assertEquals(KeyData.KeyMaterialType.ASYMMETRIC_PRIVATE, signingKey.keyData.keyMaterialType)
        assertEquals(AccountIdentityGenerator.ED25519_KEY_TYPE_URL, signingKey.keyData.typeUrl)
        Ed25519PrivateKey.parseFrom(signingKey.keyData.value)
    }

    @Test
    fun publicExportsContainOnlyPublicKeyMaterial() {
        val identity = generator.generate()

        val encryptionExport = parseKeyset(identity.encryptionPublicKeyset)
        assertEquals(1, encryptionExport.keyList.size)
        assertEquals(KeyData.KeyMaterialType.ASYMMETRIC_PUBLIC, encryptionExport.keyList[0].keyData.keyMaterialType)
        assertEquals("type.googleapis.com/google.crypto.tink.HpkePublicKey", encryptionExport.keyList[0].keyData.typeUrl)
        com.google.crypto.tink.proto.HpkePublicKey.parseFrom(encryptionExport.keyList[0].keyData.value)

        val signingExport = parseKeyset(identity.signingPublicKeyset)
        assertEquals(1, signingExport.keyList.size)
        assertEquals(KeyData.KeyMaterialType.ASYMMETRIC_PUBLIC, signingExport.keyList[0].keyData.keyMaterialType)
        assertEquals("type.googleapis.com/google.crypto.tink.Ed25519PublicKey", signingExport.keyList[0].keyData.typeUrl)
        com.google.crypto.tink.proto.Ed25519PublicKey.parseFrom(signingExport.keyList[0].keyData.value)

        val serializedPrivate = serializePrivate(identity.encryptionPrivateHandle).toList()
        assertNotEquals(serializedPrivate, identity.encryptionPublicKeyset.toList())
    }

    @Test
    fun publicExportsRoundTripUsablePrimitives() {
        val identity = generator.generate()

        val recipientEncryptionPublic = TinkProtoKeysetFormat.parseKeysetWithoutSecret(identity.encryptionPublicKeyset)
        val encrypt: HybridEncrypt = recipientEncryptionPublic.getPrimitive(HybridEncrypt::class.java)
        val decrypt: HybridDecrypt = identity.encryptionPrivateHandle.getPrimitive(HybridDecrypt::class.java)
        val contextInfo = "postmark/envelope/v1".toByteArray(Charsets.UTF_8)
        val plaintext = "envelope body".toByteArray(Charsets.UTF_8)
        val envelopeCiphertext = encrypt.encrypt(plaintext, contextInfo)
        assertContentEquals(plaintext, decrypt.decrypt(envelopeCiphertext, contextInfo))

        val signingPublic = TinkProtoKeysetFormat.parseKeysetWithoutSecret(identity.signingPublicKeyset)
        val signer: PublicKeySign = identity.signingPrivateHandle.getPrimitive(PublicKeySign::class.java)
        val verifier: PublicKeyVerify = signingPublic.getPrimitive(PublicKeyVerify::class.java)
        val message = "postmark/publish/v1".toByteArray(Charsets.UTF_8)
        verifier.verify(signer.sign(message), message)
        assertFailsWith<GeneralSecurityException> { verifier.verify(signer.sign(message), "tampered".toByteArray()) }
    }
}
