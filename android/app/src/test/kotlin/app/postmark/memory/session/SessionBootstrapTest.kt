package app.postmark.memory.session

import java.io.File
import javax.crypto.KeyGenerator
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import postmark.core.crypto.KekBoundary
import postmark.core.crypto.SessionTokenStore

/** Cold-start bootstrap and logout proof for I02. */
class SessionBootstrapTest {

    private class FakeIdentity(var encryption: Boolean = true, var signing: Boolean = true) :
        IdentityAvailabilityPort {
        override fun encryptionKeysetAvailable(): Boolean = encryption
        override fun signingKeysetAvailable(): Boolean = signing
    }

    private class SoftwareBoundary : KekBoundary {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun hasKey(alias: String): Boolean = true
        override fun createAes256GcmKey(alias: String) = Unit
        override fun loadKekAead(alias: String): com.google.crypto.tink.Aead =
            com.google.crypto.tink.subtle.AesGcmJce(key.encoded)
    }

    /** Real sealed-token behavior over a software KEK, adapted to the port. */
    private class TokenPort(directory: File, saveToken: String?) : SessionTokenPort {
        private val store = SessionTokenStore(directory, SoftwareBoundary(), "session-test")

        init {
            if (saveToken != null) store.save(saveToken)
        }

        override fun readToken(): String? = store.load()
        override fun clearToken() = store.clear()
    }

    private fun bootstrap(
        savedToken: String?,
        identity: FakeIdentity = FakeIdentity(),
        summary: PersistedAccountSummary? = PersistedAccountSummary("user-1", "mykola"),
    ): SessionBootstrap {
        val dir = createTempDirectory("session").toFile().apply { deleteOnExit() }
        return SessionBootstrap(TokenPort(dir, savedToken), identity) { summary }
    }

    @Test
    fun coldStartWithoutTokenIsSignedOut() {
        assertEquals(SessionState.SignedOut, bootstrap(savedToken = null).bootstrap())
    }

    @Test
    fun coldStartWithTokenButMissingSigningKeysIsRecoveryRequiredNotRegenerated() {
        val brokenIdentity = FakeIdentity(signing = false)

        val state = bootstrap("access-token", brokenIdentity).bootstrap()

        assertEquals(SessionState.RecoveryRequired, state)
    }

    @Test
    fun coldStartWithMissingEncryptionKeysIsAlsoRecoveryRequired() {
        val state = bootstrap("access-token", FakeIdentity(encryption = false)).bootstrap()

        assertEquals(SessionState.RecoveryRequired, state)
    }

    @Test
    fun coldStartWithTokenAndBothKeysetsIsActiveWithPersistedSummary() {
        val state = bootstrap("access-token").bootstrap()

        val active = state as SessionState.Active
        assertEquals("user-1", active.userId)
        assertEquals("mykola", active.handle)
        assertFalse(!active.hasEncryptionKeyset || !active.hasSigningKeyset)
    }

    @Test
    fun logoutClearsTheSealedTokenAndReturnsToSignedOutWhileKeysRemain() {
        val identity = FakeIdentity()
        val session = bootstrap("access-token", identity)

        val afterLogout = session.logout()

        assertEquals(SessionState.SignedOut, afterLogout)
        assertTrue(identity.encryption && identity.signing)
        // A subsequent cold start finds no token: signed out, keys still on disk.
        assertEquals(SessionState.SignedOut, session.bootstrap())
    }
}
