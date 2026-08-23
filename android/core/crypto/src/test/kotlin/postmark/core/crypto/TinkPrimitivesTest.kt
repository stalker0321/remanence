package postmark.core.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class TinkPrimitivesTest {

    private val plaintext = "postmark capsule payload".toByteArray(Charsets.UTF_8)

    @Test
    fun aeadPrimitiveRoundTrips() {
        TinkPrimitives.ensureRegistered()
        val aead: Aead = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(Aead::class.java)
        val ciphertext = aead.encrypt(plaintext, byteArrayOf(1, 2, 3))
        assertContentEquals(plaintext, aead.decrypt(ciphertext, byteArrayOf(1, 2, 3)))
        assertFailsWith<Exception> { aead.decrypt(ciphertext, byteArrayOf(9, 9, 9)) }
    }

    @Test
    fun hpkeHybridPrimitiveRoundTrips() {
        TinkPrimitives.ensureRegistered()
        val recipient: KeysetHandle = KeysetHandle.generateNew(KeyTemplates.get(TinkPrimitives.HPKE_TEMPLATE))
        val encrypt: HybridEncrypt = recipient.publicKeysetHandle.getPrimitive(HybridEncrypt::class.java)
        val decrypt: HybridDecrypt = recipient.getPrimitive(HybridDecrypt::class.java)
        val contextInfo = "postmark/envelope/v1".toByteArray(Charsets.UTF_8)
        val ciphertext = encrypt.encrypt(plaintext, contextInfo)
        assertContentEquals(plaintext, decrypt.decrypt(ciphertext, contextInfo))
        assertFailsWith<Exception> { decrypt.decrypt(ciphertext, "wrong".toByteArray()) }
    }

    @Test
    fun ed25519SignaturePrimitiveVerifiesAndRejectsTampering() {
        TinkPrimitives.ensureRegistered()
        val handle = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val signer: PublicKeySign = handle.getPrimitive(PublicKeySign::class.java)
        val verifier: PublicKeyVerify = handle.publicKeysetHandle.getPrimitive(PublicKeyVerify::class.java)
        val data = "postmark/publish/v1".toByteArray(Charsets.UTF_8)
        verifier.verify(signer.sign(data), data)
        assertFailsWith<Exception> { verifier.verify(signer.sign(data), "other".toByteArray()) }
    }
}
