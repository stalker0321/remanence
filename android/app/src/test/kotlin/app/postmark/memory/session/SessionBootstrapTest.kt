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

/**
 * Cold-start bootstrap proof (FIX-M1-007-05): only the ROTATING REFRESH token
 * is persisted sealed; Active requires a successful live refresh; invalid or
 * corrupt material is cleared; transient failures retain the token.
 */
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
    private class TokenPort(directory: File, savedRefresh: String?) : SessionTokenPort {
        val store = SessionTokenStore(directory, SoftwareBoundary(), "session-test")
        var clearCount: Int = 0
            private set

        init {
            if (savedRefresh != null) store.save(savedRefresh)
        }

        override fun readToken(): String? = store.load()
        override fun saveToken(refreshToken: String) {
            store.save(refreshToken)
        }

        override fun clearToken() {
            clearCount++
            store.clear()
        }
    }

    private class FakeRefresher(var outcome: SessionRefreshOutcome) : SessionRefresher {
        var requestedWith: String? = null

        override suspend fun refresh(storedRefreshToken: String): SessionRefreshOutcome {
            requestedWith = storedRefreshToken
            return outcome
        }
    }

    private data class Fixture(
        val bootstrap: SessionBootstrap,
        val tokens: TokenPort,
        val refresher: FakeRefresher,
    )

    private fun fixture(
        savedRefresh: String? = "stored-refresh-token",
        refresherOutcome: SessionRefreshOutcome = SessionRefreshOutcome.Rotated("fresh-access", "fresh-refresh"),
        identity: FakeIdentity = FakeIdentity(),
        summary: PersistedAccountSummary? = PersistedAccountSummary("user-1", "mykola"),
    ): Fixture {
        val dir = createTempDirectory("session").toFile().apply { deleteOnExit() }
        val tokens = TokenPort(dir, savedRefresh)
        val refresher = FakeRefresher(refresherOutcome)
        val bootstrap = SessionBootstrap(tokens, identity, { summary }, refresher)
        return Fixture(bootstrap, tokens, refresher)
    }

    @Test
    fun coldStartWithoutTokenIsSignedOut() = runBlocking {
        assertEquals(SessionState.SignedOut, fixture(savedRefresh = null).bootstrap.bootstrap())
    }

    @Test
    fun coldStartWithCorruptSealedRecordClearsItAndReportsSignedOut() = runBlocking {
        // Raw garbage bytes where the sealed record lives defeat the AEAD.
        val dir = createTempDirectory("session-corrupt").toFile().apply { deleteOnExit() }
        File(dir, "session.token.sealed").writeBytes("definitely-not-sealed-material".toByteArray())
        val tokens = TokenPort(dir, savedRefresh = null)
        val bootstrap = SessionBootstrap(tokens, FakeIdentity(), { null }, FakeRefresher(SessionRefreshOutcome.Rejected))

        assertEquals(SessionState.SignedOut, bootstrap.bootstrap())
        assertEquals(1, tokens.clearCount)
    }

    @Test
    fun missingIdentityIsRecoveryRequiredBeforeAnyNetworkCall() = runBlocking {
        val f = fixture(identity = FakeIdentity(signing = false), refresherOutcome = SessionRefreshOutcome.Rejected)

        assertEquals(SessionState.RecoveryRequired, f.bootstrap.bootstrap())
        assertEquals(null, f.refresher.requestedWith)
    }

    @Test
    fun activeOnlyAfterLiveRefreshRotatesAndRepersistsTheNewRefreshToken() = runBlocking {
        val f = fixture()

        val state = f.bootstrap.bootstrap()

        assertEquals("stored-refresh-token", f.refresher.requestedWith)
        assertTrue(f.refresher.requestedWith != "fresh-refresh")
        val active = state as SessionState.Active
        assertEquals("user-1", active.userId)
        assertEquals("mykola", active.handle)
        assertFalse(!active.hasEncryptionKeyset || !active.hasSigningKeyset)
        // The rotated REFRESH token replaced the stored one, sealed on disk.
        assertEquals("fresh-refresh", f.tokens.readToken())
    }

    @Test
    fun rejectedRefreshClearsTheStoredTokenAndSignsOut() = runBlocking {
        val f = fixture(refresherOutcome = SessionRefreshOutcome.Rejected)

        assertEquals(SessionState.SignedOut, f.bootstrap.bootstrap())
        assertEquals(null, f.tokens.readToken())
    }

    @Test
    fun unreachableRefreshKeepsTheTokenAndAsksForConnectivity() = runBlocking {
        val f = fixture(refresherOutcome = SessionRefreshOutcome.Unreachable)

        assertEquals(SessionState.RequiresConnectivity, f.bootstrap.bootstrap())
        // The stored lineage stays intact for a later attempt.
        assertEquals("stored-refresh-token", f.tokens.readToken())
        assertEquals(0, f.tokens.clearCount)
    }

    @Test
    fun logoutClearsTheSealedTokenAndReturnsToSignedOutWhileKeysRemain() = runBlocking {
        val identity = FakeIdentity()
        val f = fixture(identity = identity)

        val afterLogout = f.bootstrap.logout()

        assertEquals(SessionState.SignedOut, afterLogout)
        assertTrue(identity.encryption && identity.signing)
        assertEquals(1, f.tokens.clearCount)
        // A subsequent cold start finds no token: signed out, keys still on disk.
        assertEquals(SessionState.SignedOut, fixture(savedRefresh = null).bootstrap.bootstrap())
    }
}
