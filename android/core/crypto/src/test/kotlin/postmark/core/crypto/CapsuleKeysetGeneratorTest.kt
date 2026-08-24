package postmark.core.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.proto.OutputPrefixType
import com.google.crypto.tink.proto.Keyset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CapsuleKeysetGeneratorTest {

    private val generator = CapsuleKeysetGenerator()

    private fun serialize(handle: com.google.crypto.tink.KeysetHandle): ByteArray =
        TinkProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get())

    @Test
    fun generatedCapsuleKeysAreUnique() {
        val first = serialize(generator.generate())
        val second = serialize(generator.generate())
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun primaryUsesTinkOutputPrefixAndAes256Gcm() {
        val keyset = Keyset.parseFrom(serialize(generator.generate()))
        assertEquals(1, keyset.keyList.size)
        assertEquals(keyset.primaryKeyId, keyset.keyList[0].keyId)
        assertEquals(OutputPrefixType.TINK, keyset.keyList[0].outputPrefixType)
        assertTrue(keyset.keyList[0].keyData.typeUrl.endsWith("AesGcmKey"))
        val aesKey = com.google.crypto.tink.proto.AesGcmKey.parseFrom(keyset.keyList[0].keyData.value)
        // 256-bit key material.
        assertEquals(32, aesKey.keyValue.size())
    }

    @Test
    fun serializedCapsuleKeysetRoundTripsAsWorkingAead() {
        TinkPrimitives.ensureRegistered()
        val handle = generator.generate()
        val restored = TinkProtoKeysetFormat.parseKeyset(serialize(handle), InsecureSecretKeyAccess.get())
        val aead: Aead = restored.getPrimitive(Aead::class.java)
        val plaintext = "capsule manifest bytes".toByteArray()
        val aad = "postmark/artifact/v1".toByteArray()
        assertContentEquals(plaintext, aead.decrypt(aead.encrypt(plaintext, aad), aad))
    }

}
