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

    private class RecordingIdentityPort(private val trace: MutableList<String>) : RegistrationIdentityPort {
        val calls = mutableListOf<String>()
        var prepared = 0
        var failWithRecovery = false

        override fun prepareIdentity(): PreparedIdentity {
            trace += "identity"
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

    private class FakeAuthApi(private val trace: MutableList<String>) : RegistrationAuthApiPort {
        val calls = mutableListOf<String>()
        var nextResult: AuthResult<dev.hryshyn.remanence.core.data.network.RegisterResponseDto>? = null

        override suspend fun register(request: dev.hryshyn.remanence.core.data.network.RegisterRequestDto): AuthResult<dev.hryshyn.remanence.core.data.network.RegisterResponseDto> {
            trace += "network"
            calls += "network"
            return nextResult ?: throw IllegalStateException("test did not enqueue a result")
        }
    }

    private class FakeAccounts(private val trace: MutableList<String>) : CurrentAccountPort {
        var recorded: Pair<dev.hryshyn.remanence.core.data.network.RegistrationUserDto, String>? = null

        override suspend fun recordCurrentAccount(user: dev.hryshyn.remanence.core.data.network.RegistrationUserDto, activeKeyBundleId: String) {
            trace += "account"
            recorded = user to activeKeyBundleId
        }
    }

    private class RecordingReplacement(private val trace: MutableList<String>) : SessionReplacementPort {
        var installCount = 0

        override fun acquireLease(): Long {
            trace += "lease"
            return 0L
        }

        override suspend fun replace(
            lease: Long,
            expectedOwner: dev.hryshyn.remanence.core.model.UserId,
            accessToken: String,
            refreshToken: String,
            commitAccount: suspend () -> Unit,
        ) {
            trace += "close"
            commitAccount()
            trace += "install"
            installCount++
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
    fun happyPathWrapsIdentityBeforeNetworkThenClosesBeforeAccountThenInstalls() = runTest {
        val trace = mutableListOf<String>()
        val identity = RecordingIdentityPort(trace)
        val api = FakeAuthApi(trace).apply { nextResult = AuthResult.Success(successResponse, 201) }
        val accounts = FakeAccounts(trace)
        val replacement = RecordingReplacement(trace)
        val useCase = RegistrationUseCase(identity, api, accounts, replacement)

        val outcome = useCase.register("private@example.com", "secret-password", "@mykola")

        assertEquals(RegistrationUseCase.Outcome.Registered("1f0a1234-5678-4abc-9def-aabbccdd1001", "mykola", "2f0a1234-5678-4abc-9def-aabbccdd2002"), outcome)
        assertEquals(listOf("identity", "lease", "network", "close", "account", "install"), trace)
        assertEquals(1, replacement.installCount)
    }

    @Test
    fun networkFailureLeavesNoAccountRecordButKeepsWrappedIdentity() = runTest {
        val trace = mutableListOf<String>()
        val identity = RecordingIdentityPort(trace)
        val api = FakeAuthApi(trace).apply { nextResult = AuthResult.Failure(dev.hryshyn.remanence.core.data.network.AuthFailure.HTTP, 409) }
        val accounts = FakeAccounts(trace)
        val replacement = RecordingReplacement(trace)
        val useCase = RegistrationUseCase(identity, api, accounts, replacement)

        val outcome = useCase.register("private@example.com", "secret-password", "@mykola")

        assertEquals(RegistrationUseCase.Outcome.Rejected(409), outcome)
        assertEquals(listOf("identity", "lease", "network"), trace)
        assertEquals(0, replacement.installCount)

        // Retry after a corrected request reuses the same wrapped identity.
        api.nextResult = AuthResult.Success(successResponse, 201)
        val second = useCase.register("private@example.com", "secret-password", "@mykola")
        assertEquals(RegistrationUseCase.Outcome.Registered("1f0a1234-5678-4abc-9def-aabbccdd1001", "mykola", "2f0a1234-5678-4abc-9def-aabbccdd2002"), second)
        assertEquals(2, identity.calls.count { it == "identity" })
    }

    @Test
    fun recoveryRequiredOutcomeStopsBeforeAnyNetworkCall() = runTest {
        val trace = mutableListOf<String>()
        val identity = RecordingIdentityPort(trace).apply { failWithRecovery = true }
        val api = FakeAuthApi(trace)
        val accounts = FakeAccounts(trace)
        val replacement = RecordingReplacement(trace)
        val useCase = RegistrationUseCase(identity, api, accounts, replacement)

        val outcome = useCase.register("private@example.com", "secret-password", "@mykola")

        assertEquals(RegistrationUseCase.Outcome.RecoveryRequired, outcome)
        assertEquals(0, api.calls.size)
        assertEquals(listOf("identity"), trace)
        assertEquals(0, replacement.installCount)
    }
}
