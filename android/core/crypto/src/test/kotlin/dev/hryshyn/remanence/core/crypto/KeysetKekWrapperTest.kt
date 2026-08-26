package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KeysetKekWrapperTest {

    private fun newEd25519Keyset(): KeysetHandle {
        TinkPrimitives.ensureRegistered()
        return KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
    }

    @Test
    fun wrapProducesPersistableRecordWithoutRawKeysetBytes() {
        val boundary = InMemoryKekBoundary()
        boundary.createAes256GcmKey("remanence.kek.wrap")
        val keyset = newEd25519Keyset()
        val record = KeysetKekWrapper(boundary).wrap("remanence.kek.wrap", keyset)
        assertEquals("remanence.kek.wrap", record.alias)
        assertEquals(12, record.nonce.size)
        assertTrue(record.wrappedKeyset.isNotEmpty())
        val rawSerialized = com.google.crypto.tink.TinkProtoKeysetFormat.serializeKeyset(
            keyset,
            com.google.crypto.tink.InsecureSecretKeyAccess.get(),
        )
        assertNotEquals(rawSerialized.toList(), record.wrappedKeyset.toList())
    }

    @Test
    fun unwrapRoundTripsSameProcess() {
        val boundary = InMemoryKekBoundary()
        boundary.createAes256GcmKey("remanence.kek.roundtrip")
        val wrapper = KeysetKekWrapper(boundary)
        val keyset = newEd25519Keyset()
        val record = wrapper.wrap("remanence.kek.roundtrip", keyset)
        assertTrue(keyset.equalsKeyset(wrapper.unwrap(record)))
    }

    @Test
    fun unwrapSurvivesSimulatedProcessReloadThroughRecordSerialization() {
        val firstBoundary = InMemoryKekBoundary()
        firstBoundary.createAes256GcmKey("remanence.kek.reload")
        val firstWrapper = KeysetKekWrapper(firstBoundary)
        val keyset = newEd25519Keyset()
        val rawBefore = com.google.crypto.tink.TinkProtoKeysetFormat.serializeKeyset(
            keyset,
            com.google.crypto.tink.InsecureSecretKeyAccess.get(),
        )
        val persisted = firstWrapper.wrap("remanence.kek.reload", keyset).serialize()

        val secondBoundary = InMemoryKekBoundary()
        val reloaded = WrappedKeysetRecord.parse(persisted)
        val unwrapped = KeysetKekWrapper(secondBoundary).unwrap(reloaded)
        val rawAfter = com.google.crypto.tink.TinkProtoKeysetFormat.serializeKeyset(
            unwrapped,
            com.google.crypto.tink.InsecureSecretKeyAccess.get(),
        )
        assertEquals("remanence.kek.reload", reloaded.alias)
        assertContentEquals(rawBefore, rawAfter)
    }

    @Test
    fun tamperedCiphertextNonceAndAliasFailClosed() {
        val boundary = InMemoryKekBoundary()
        boundary.createAes256GcmKey("remanence.kek.tamper")
        val wrapper = KeysetKekWrapper(boundary)
        val record = wrapper.wrap("remanence.kek.tamper", newEd25519Keyset())

        val flippedPayload = WrappedKeysetRecord.create(
            record.alias,
            record.nonce,
            record.wrappedKeyset.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
        )
        assertFailsWith<Exception> { wrapper.unwrap(flippedPayload) }

        val flippedNonce = WrappedKeysetRecord.create(
            record.alias,
            record.nonce.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
            record.wrappedKeyset,
        )
        assertFailsWith<Exception> { wrapper.unwrap(flippedNonce) }
    }

    @Test
    fun wrongKekOrWrongAssociatedDataFailsClosed() {
        val boundary = InMemoryKekBoundary()
        boundary.createAes256GcmKey("remanence.kek.one")
        boundary.createAes256GcmKey("remanence.kek.two")
        val wrapper = KeysetKekWrapper(boundary)
        val record = wrapper.wrap("remanence.kek.one", newEd25519Keyset())

        assertFailsWith<Exception> { wrapper.unwrap(WrappedKeysetRecord.create("remanence.kek.two", record.nonce, record.wrappedKeyset)) }
        assertFailsWith<GeneralSecurityException> {
            wrapper.unwrap(WrappedKeysetRecord.create("remanence.kek.missing", record.nonce, record.wrappedKeyset))
        }
    }

    @Test
    fun wrappingWithoutKekFailsClosed() {
        val wrapper = KeysetKekWrapper(InMemoryKekBoundary())
        assertFailsWith<GeneralSecurityException> { wrapper.wrap("remanence.kek.absent", newEd25519Keyset()) }
    }

    @Test
    fun associatedDataIsDeterministicAndVersionBound() {
        val aad = KeysetKekWrapper.associatedData(1, "remanence.kek.aad")
        assertContentEquals(aad, KeysetKekWrapper.associatedData(1, "remanence.kek.aad"))
        assertNotEquals(aad.toList(), KeysetKekWrapper.associatedData(2, "remanence.kek.aad").toList())
        assertNotEquals(aad.toList(), KeysetKekWrapper.associatedData(1, "other.alias").toList())
        val expectedPrefix = "postmark/kek/wrap/v1".toByteArray(Charsets.UTF_8) + 0x00 + byteArrayOf(0, 0, 0, 1)
        assertContentEquals(expectedPrefix + "remanence.kek.aad".encodeToByteArray(), aad)
    }
}
