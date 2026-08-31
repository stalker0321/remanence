package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.core.model.UserId
import java.nio.file.Files
import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTokenStoreTest {

    private val kekAlias = "remanence.kek.session-store"
    private val ownerA = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a001")
    private val ownerB = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a002")

    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: java.nio.file.Path

    private fun newStore(): SessionTokenStore = SessionTokenStore(tempDir.toFile(), InMemoryKekBoundary(), kekAlias)

    private fun record(owner: UserId = ownerA, token: String) = SessionRefreshRecord(owner, token)

    @Test
    fun storeLoadClearRoundTrip() {
        ensureKek()
        val store = newStore()
        assertNull(store.load())
        val token = "pm_rt_example-opaque-refresh-token-value"
        store.save(record(token = token))
        val loaded = store.load()
        assertEquals(ownerA, loaded?.ownerUserId)
        assertEquals(token, loaded?.refreshToken)
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun rotationPreservesTheBoundOwner() {
        ensureKek()
        val store = newStore()
        store.save(record(token = "pm_rt_first"))
        store.save(record(ownerA, "pm_rt_second"))
        val loaded = store.load()
        assertEquals(ownerA, loaded?.ownerUserId)
        assertEquals("pm_rt_second", loaded?.refreshToken)
    }

    @Test
    fun savedBytesNeverContainPlaintextToken() {
        ensureKek()
        val store = newStore()
        val token = "pm_rt_supersecret-refresh-token"
        store.save(record(token = token))
        val rawFiles = Files.walk(tempDir).filter { it.toFile().isFile }.map { it.toFile().readBytes() }.toList()
        assertTrue(rawFiles.isNotEmpty())
        for (bytes in rawFiles) {
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains(token))
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("pm_rt"))
        }
    }

    @Test
    fun recordToStringDoesNotIncludeTheToken() {
        val token = "pm_rt_must-not-appear"
        val text = record(token = token).toString()
        assertFalse(text.contains(token))
        assertFalse(text.contains("pm_rt"))
        assertTrue(text.contains(ownerA.toRestString()))
    }

    @Test
    fun tamperedStorageFailsClosed() {
        ensureKek()
        val store = newStore()
        store.save(record(token = "pm_rt_tamper-target"))
        val file = tempDir.resolve("session.token.sealed").toFile()
        val bytes = file.readBytes()
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertFailsWith<GeneralSecurityException> { store.load() }
    }

    @Test
    fun legacyUnboundV1CiphertextFailsClosed() {
        ensureKek()
        val aead = InMemoryKekBoundary().loadKekAead(kekAlias)
        val legacyAad = "postmark/session/v1".encodeToByteArray() + 0x00 + kekAlias.encodeToByteArray()
        val sealed = aead.encrypt("pm_rt_legacy-unbound".encodeToByteArray(), legacyAad)
        tempDir.resolve("session.token.sealed").toFile().writeBytes(sealed)
        assertFailsWith<GeneralSecurityException> { newStore().load() }
    }

    @Test
    fun emptyTokenIsRejected() {
        ensureKek()
        assertFailsWith<GeneralSecurityException> { newStore().save(record(token = "")) }
    }

    @Test
    fun missingKekFailsClosedWithoutLeakingPlaintext() {
        val store = SessionTokenStore(tempDir.toFile(), InMemoryKekBoundary(), "remanence.kek.absent-session")
        assertFailsWith<GeneralSecurityException> { store.save(record(ownerB, "pm_rt_without-kek")) }
        assertNull(store.load())
    }

    private fun ensureKek() {
        val boundary = InMemoryKekBoundary()
        if (!boundary.hasKey(kekAlias)) boundary.createAes256GcmKey(kekAlias)
    }
}
