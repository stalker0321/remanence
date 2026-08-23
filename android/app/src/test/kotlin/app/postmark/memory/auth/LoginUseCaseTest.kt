package app.postmark.memory.auth

import app.postmark.memory.wiring.PreparedIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import postmark.core.data.network.ActiveKeyBundleMetadataDto
import postmark.core.data.network.AuthResult
import postmark.core.data.network.LoginRequestDto
import postmark.core.data.network.LoginResponseDto
import postmark.core.data.network.RegistrationUserDto

class LoginUseCaseTest {

    private val user = RegistrationUserDto(
        userId = "1f0a1234-5678-4abc-9def-aabbccdd1001",
        email = "private@example.com",
        handle = "mykola",
        createdAt = "2026-08-23T03:00:00Z",
    )

    private val bundle = ActiveKeyBundleMetadataDto(
        keyBundleId = "2f0a1234-5678-4abc-9def-aabbccdd2002",
        suite = PreparedIdentity.SUITE,
        protocolVersion = 1,
        status = "ACTIVE",
    )

    private fun successResponse() = LoginResponseDto(
        user = user,
        activeKeyBundle = bundle,
        sessionId = "3f0a1234-5678-4abc-9def-aabbccdd3003",
        accessToken = "pm_at_a",
        accessExpiresAt = "2026-08-23T03:15:00Z",
        refreshToken = "pm_rt_r",
        refreshExpiresAt = "2026-09-22T03:00:00Z",
    )

    private class FakeIdentity(private val has: Boolean) : LoginIdentityPort {
        val checked = mutableListOf<String>()

        override fun hasIdentityFor(activeKeyBundleId: String): Boolean {
            checked += activeKeyBundleId
            return has
        }
    }

    private class FakeApi(var result: AuthResult<LoginResponseDto>? = null) : LoginAuthApiPort {
        var calls = 0

        override suspend fun login(request: LoginRequestDto): AuthResult<LoginResponseDto> {
            calls++
            return result ?: throw IllegalStateException("no enqueued result")
        }
    }

    @Test
    fun loggedInWhenLocalIdentityMatchesActiveBundle() = runTest {
        val identity = FakeIdentity(has = true)
        val api = FakeApi(AuthResult.Success(successResponse(), 200))
        val accounts = RecordingAccounts()
        val outcome = LoginUseCase(identity, api, accounts).login("private@example.com", "secret-password")

        assertEquals(
            LoginUseCase.Outcome.LoggedIn(user.userId, user.handle, bundle.keyBundleId),
            outcome,
        )
        assertEquals(listOf(bundle.keyBundleId), identity.checked)
        assertEquals(1, accounts.calls)
    }

    @Test
    fun missingLocalKeysYieldRecoveryRequiredWithoutGeneratingReplacement() = runTest {
        val identity = FakeIdentity(has = false)
        val api = FakeApi(AuthResult.Success(successResponse(), 200))
        val accounts = RecordingAccounts()
        val outcome = LoginUseCase(identity, api, accounts).login("private@example.com", "secret-password")

        assertEquals(
            LoginUseCase.Outcome.RecoveryRequired(user.userId, user.handle, bundle.keyBundleId),
            outcome,
        )
        assertEquals(listOf(bundle.keyBundleId), identity.checked)
        assertEquals(1, accounts.calls)
    }

    @Test
    fun wrongPasswordRejectedAndNothingRecorded() = runTest {
        val api = FakeApi(AuthResult.Failure(postmark.core.data.network.AuthFailure.HTTP, 401))
        val accounts = RecordingAccounts()
        val outcome = LoginUseCase(FakeIdentity(has = true), api, accounts).login("private@example.com", "bad")

        assertEquals(LoginUseCase.Outcome.Rejected(401), outcome)
        assertEquals(0, accounts.calls)
    }

    @Test
    fun offlineIsNetworkUnreachable() = runTest {
        val api = FakeApi(AuthResult.Failure(postmark.core.data.network.AuthFailure.NETWORK))
        val outcome = LoginUseCase(FakeIdentity(true), api, RecordingAccounts()).login("e", "p")
        assertEquals(LoginUseCase.Outcome.NetworkUnreachable, outcome)
    }

    private class RecordingAccounts : CurrentAccountPort {
        var calls = 0

        override suspend fun recordCurrentAccount(user: RegistrationUserDto, activeKeyBundleId: String) {
            calls++
        }
    }
}
