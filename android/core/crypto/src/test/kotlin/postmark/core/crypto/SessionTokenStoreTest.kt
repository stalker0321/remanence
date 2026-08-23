package postmark.core.crypto

import java.nio.file.Files
import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTokenStoreTest {

    private val kekAlias = "postmark.kek.session-store"

    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: java.nio.file.Path

    private fun newStore(): SessionTokenStore = SessionTokenStore(tempDir.toFile(), InMemoryKekBoundary(), kekAlias)

    @Test
    fun storeLoadClearRoundTrip() {
        ensureKek()
        val store = newStore()
        assertNull(store.load())
        val token = "pm_rt_example-opaque-refresh-token-value"
        store.save(token)
        assertEquals(token, store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun savedBytesNeverContainPlaintextToken() {
        ensureKek()
        val store = newStore()
        val token = "pm_rt_supersecret-refresh-token"
        store.save(token)
        val rawFiles = Files.walk(tempDir).filter { it.toFile().isFile }.map { it.toFile().readBytes() }.toList()
        assertTrue(rawFiles.isNotEmpty())
        for (bytes in rawFiles) {
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains(token))
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("pm_rt"))
        }
    }

    @Test
    fun saveReplacesPreviousToken() {
        ensureKek()
        val store = newStore()
        store.save("pm_rt_first")
        store.save("pm_rt_second")
        assertEquals("pm_rt_second", store.load())
    }

    @Test
    fun tamperedStorageFailsClosed() {
        ensureKek()
        val store = newStore()
        store.save("pm_rt_tamper-target")
        val file = tempDir.resolve("session.token.sealed").toFile()
        val bytes = file.readBytes()
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertFailsWith<GeneralSecurityException> { store.load() }
    }

    @Test
    fun missingKekFailsClosedWithoutLeakingPlaintext() {
        val store = SessionTokenStore(tempDir.toFile(), InMemoryKekBoundary(), "postmark.kek.absent-session")
        assertFailsWith<GeneralSecurityException> { store.save("pm_rt_without-kek") }
        assertNull(store.load())
    }

    private fun ensureKek() {
        val boundary = InMemoryKekBoundary()
        if (!boundary.hasKey(kekAlias)) boundary.createAes256GcmKey(kekAlias)
    }
}
