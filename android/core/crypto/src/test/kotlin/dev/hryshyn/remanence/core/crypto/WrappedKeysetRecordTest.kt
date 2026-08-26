package dev.hryshyn.remanence.core.crypto

import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class WrappedKeysetRecordTest {

    private val alias = "remanence.kek.identity"
    private val nonce = ByteArray(12) { (it + 1).toByte() }
    private val wrapped = ByteArray(64) { (it * 3).toByte() }

    @Test
    fun serializeParseRoundTrips() {
        val record = WrappedKeysetRecord.create(alias, nonce, wrapped)
        val parsed = WrappedKeysetRecord.parse(record.serialize())
        assertEquals(record, parsed)
        assertEquals(alias, parsed.alias)
        assertContent(nonce, parsed.nonce)
        assertContent(wrapped, parsed.wrappedKeyset)
    }

    @Test
    fun serializationIsDeterministic() {
        val first = WrappedKeysetRecord.create(alias, nonce, wrapped).serialize()
        val second = WrappedKeysetRecord.create(alias, nonce.copyOf(), wrapped.copyOf()).serialize()
        assertContent(first, second)
    }

    @Test
    fun differentNonceChangesBytes() {
        val otherNonce = nonce.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val first = WrappedKeysetRecord.create(alias, nonce, wrapped).serialize()
        val second = WrappedKeysetRecord.create(alias, otherNonce, wrapped).serialize()
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun unknownFormatVersionsFailClosed() {
        val base = WrappedKeysetRecord.create(alias, nonce, wrapped).serialize()
        for (badVersion in intArrayOf(0, 2, 0x7FFFFFFF)) {
            val tampered = base.copyOf().also { writeVersion(it, badVersion) }
            assertFailsWith<GeneralSecurityException> { WrappedKeysetRecord.parse(tampered) }
        }
    }

    @Test
    fun truncatedRecordsFailClosed() {
        val base = WrappedKeysetRecord.parse(WrappedKeysetRecord.create(alias, nonce, wrapped).serialize())
        val full = base.serialize()
        for (cut in intArrayOf(0, 1, 4, 8, 12, 20, full.size - 5)) {
            if (cut >= full.size) continue
            assertFailsWith<Exception>("cut=$cut") { WrappedKeysetRecord.parse(full.copyOf(cut)) }
        }
    }

    @Test
    fun trailingBytesFailClosed() {
        val base = WrappedKeysetRecord.create(alias, nonce, wrapped).serialize()
        assertFailsWith<GeneralSecurityException> { WrappedKeysetRecord.parse(base + 0x00) }
    }

    @Test
    fun badMagicAndAliasBoundsFailClosed() {
        val base = WrappedKeysetRecord.create(alias, nonce, wrapped).serialize()
        assertFailsWith<GeneralSecurityException> {
            WrappedKeysetRecord.parse(base.copyOf().also { it[0] = 'X'.code.toByte() })
        }
        for (badAlias in listOf("", "with space", "with/slash", "unicode\u00e9", "x".repeat(129))) {
            assertFailsWith<GeneralSecurityException>("alias=$badAlias") {
                WrappedKeysetRecord.create(badAlias, nonce, wrapped)
            }
        }
        WrappedKeysetRecord.create("x".repeat(128), nonce, wrapped)
        WrappedKeysetRecord.create("a", nonce, wrapped)
        WrappedKeysetRecord.create("A9._-", nonce, wrapped)
    }

    @Test
    fun nonceAndPayloadBoundsFailClosed() {
        assertFailsWith<GeneralSecurityException> { WrappedKeysetRecord.create(alias, ByteArray(11), wrapped) }
        assertFailsWith<GeneralSecurityException> { WrappedKeysetRecord.create(alias, ByteArray(13), wrapped) }
        assertFailsWith<GeneralSecurityException> { WrappedKeysetRecord.create(alias, nonce, ByteArray(0)) }
        assertFailsWith<GeneralSecurityException> {
            WrappedKeysetRecord.create(alias, nonce, ByteArray(WrappedKeysetRecord.WRAPPED_KEYSET_MAX_BYTES + 1))
        }
        WrappedKeysetRecord.create(alias, nonce, ByteArray(WrappedKeysetRecord.WRAPPED_KEYSET_MAX_BYTES))
    }

    private fun assertContent(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }

    private fun writeVersion(target: ByteArray, version: Int) {
        target[4] = (version ushr 24).toByte()
        target[5] = (version ushr 16).toByte()
        target[6] = (version ushr 8).toByte()
        target[7] = version.toByte()
    }
}
