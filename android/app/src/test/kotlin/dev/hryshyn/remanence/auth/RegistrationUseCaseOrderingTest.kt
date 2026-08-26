package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.wiring.PreparedIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import dev.hryshyn.remanence.core.data.network.AuthResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class RegistrationUseCaseOrderingTest {

    private class RecordingIdentityPort : RegistrationIdentityPort {
        val calls = mutableListOf<String>()
        var prepared = 0
        var failWithRecovery = false

        override fun prepareIdentity(): PreparedIdentity {
            calls += "identity"
            if (failWithRecovery) throw IdentityRecoveryRequiredException()
            prepared++
            return PreparedIdentity(
                keyBundleId = "2f0a1234-5678-4abc-9def-aabbccdd2002",
                encryptionPublicKeysetB64Url = "CIenc",
                signingPublicKeysetB64Url = "CJsig",
            )
        }
    }

    private class FakeAuthApi : RegistrationAuthApiPort {
        val calls = mutableListOf<String>()
        var nextResult: AuthResult<dev.hryshyn.remanence.core.data.network.RegisterResponseDto>? = null

        override suspend fun register(request: dev.hryshyn.remanence.core.data.network.RegisterRequestDto): AuthResult<dev.hryshyn.remanence.core.data.network.RegisterResponseDto> {
            calls += "network"
            return nextResult ?: throw IllegalStateException("test did not enqueue a result")
        }
    }

    private class FakeAccounts : CurrentAccountPort {
        val calls = mutableListOf<String>()
        var recorded: Pair<dev.hryshyn.remanence.core.data.network.RegistrationUserDto, String>? = null

        override suspend fun recordCurrentAccount(user: dev.hryshyn.remanence.core.data.network.RegistrationUserDto, activeKeyBundleId: String) {
            calls += "account"
            recorded = user to activeKeyBundleId
        }
    }

    private val successResponse = dev.hryshyn.remanence.core.data.network.RegisterResponseDto(
        user = dev.hryshyn.remanence.core.data.network.RegistrationUserDto(
            userId = "1f0a1234-5678-4abc-9def-aabbccdd1001",
            email = "private@example.com",
            handle = "mykola",
            createdAt = "2026-08-23T03:00:00Z",
        ),
        activeKeyBundleId = "2f0a1234-5678-4abc-9def-aabbccdd2002",
        accessToken = "pm_at_a",
        accessExpiresAt = "2026-08-23T03:15:00Z",
        refreshToken = "pm_rt_r",
        refreshExpiresAt = "2026-09-22T03:00:00Z",
    )

    @Test
    fun happyPathWrapsIdentityBeforeNetworkAndRecordsAccountLast() = runTest {
        val identity = RecordingIdentityPort()
        val api = FakeAuthApi().apply { nextResult = AuthResult.Success(successResponse, 201) }
        val accounts = FakeAccounts()
        val useCase = RegistrationUseCase(identity, api, accounts)

        val outcome = useCase.register("private@example.com", "secret-password", "@mykola")

        assertEquals(RegistrationUseCase.Outcome.Registered("1f0a1234-5678-4abc-9def-aabbccdd1001", "mykola", "2f0a1234-5678-4abc-9def-aabbccdd2002"), outcome)
        assertEquals(listOf("identity", "network", "account"), identity.calls + api.calls + accounts.calls)
    }

    @Test
    fun networkFailureLeavesNoAccountRecordButKeepsWrappedIdentity() = runTest {
        val identity = RecordingIdentityPort()
        val api = FakeAuthApi().apply { nextResult = AuthResult.Failure(dev.hryshyn.remanence.core.data.network.AuthFailure.HTTP, 409) }
        val accounts = FakeAccounts()
        val useCase = RegistrationUseCase(identity, api, accounts)

        val outcome = useCase.register("private@example.com", "secret-password", "@mykola")

        assertEquals(RegistrationUseCase.Outcome.Rejected(409), outcome)
        assertEquals(listOf("identity", "network"), identity.calls + api.calls + accounts.calls)

        // Retry after a corrected request reuses the same wrapped identity.
        api.nextResult = AuthResult.Success(successResponse, 201)
        val second = useCase.register("private@example.com", "secret-password", "@mykola")
        assertEquals(RegistrationUseCase.Outcome.Registered("1f0a1234-5678-4abc-9def-aabbccdd1001", "mykola", "2f0a1234-5678-4abc-9def-aabbccdd2002"), second)
        assertEquals(2, identity.calls.count { it == "identity" })
    }

    @Test
    fun recoveryRequiredOutcomeStopsBeforeAnyNetworkCall() = runTest {
        val identity = RecordingIdentityPort().apply { failWithRecovery = true }
        val api = FakeAuthApi()
        val accounts = FakeAccounts()
        val useCase = RegistrationUseCase(identity, api, accounts)

        val outcome = useCase.register("private@example.com", "secret-password", "@mykola")

        assertEquals(RegistrationUseCase.Outcome.RecoveryRequired, outcome)
        assertEquals(0, api.calls.size)
        assertEquals(0, accounts.calls.size)
    }
}
