package app.postmark.memory.session

import java.io.File
import javax.crypto.KeyGenerator
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import app.postmark.memory.ui.navigation.AppDestination
import app.postmark.memory.ui.navigation.AuthUiState
import postmark.core.crypto.KekBoundary
import postmark.core.crypto.SessionTokenStore

/** Auth route-guard wiring proof for I03. */
class RootViewModelTest {

    private class SoftwareBoundary : KekBoundary {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun hasKey(alias: String): Boolean = true
        override fun createAes256GcmKey(alias: String) = Unit
        override fun loadKekAead(alias: String): com.google.crypto.tink.Aead =
            com.google.crypto.tink.subtle.AesGcmJce(key.encoded)
    }

    private class TokenPort(directory: File, saveToken: String?) : SessionTokenPort {
        private val store = SessionTokenStore(directory, SoftwareBoundary(), "session-test")

        init {
            if (saveToken != null) store.save(saveToken)
            lastSavedFile = File(directory, "token.bin").takeIf { it.exists() } ?: lastSavedFile
        }

        override fun readToken(): String? = store.load()
        override fun clearToken() = store.clear()

        companion object {
            var lastSavedFile: File? = null
        }
    }

    private fun viewModel(
        savedToken: String?,
        identity: IdentityAvailabilityPort = FakeIdentity(),
    ): RootViewModel {
        val dir = createTempDirectory("root-session").toFile().apply { deleteOnExit() }
        return RootViewModel(
            SessionBootstrap(TokenPort(dir, savedToken), identity) {
                PersistedAccountSummary("user-1", "mykola")
            },
        )
    }

    @Test
    fun coldStartWithoutSessionLandsOnAuthenticationSurface() {
        val vm = viewModel(savedToken = null)

        assertEquals(AuthUiState.SignedOut, vm.authState.value)
        assertEquals(AppDestination.Authentication, vm.destination.value)
    }

    @Test
    fun missingKeysOnColdStartSurfacesRecoveryRequired() {
        val vm = viewModel(
            savedToken = "access-token",
            identity = object : IdentityAvailabilityPort {
                override fun encryptionKeysetAvailable(): Boolean = true
                override fun signingKeysetAvailable(): Boolean = false
            },
        )

        assertEquals(AuthUiState.RecoveryRequired, vm.authState.value)
        assertEquals(AppDestination.Authentication, vm.destination.value)
    }

    @Test
    fun establishedSessionReachesHomeAsAuthenticated() {
        val vm = viewModel(savedToken = "access-token")

        vm.onSessionEstablished()

        assertEquals(AuthUiState.Authenticated(userId = "user-1", handle = "mykola"), vm.authState.value)
        assertEquals(AppDestination.Home, vm.destination.value)
    }

    @Test
    fun logoutReturnsToAuthenticationEvenIfTokenClearingFailsSilently() {
        val vm = viewModel(savedToken = null) // nothing persisted; logout is still safe

        vm.logout()

        assertEquals(AuthUiState.SignedOut, vm.authState.value)
        assertEquals(AppDestination.Authentication, vm.destination.value)
    }
}

private class FakeIdentity : IdentityAvailabilityPort {
    override fun encryptionKeysetAvailable(): Boolean = true
    override fun signingKeysetAvailable(): Boolean = true
}
