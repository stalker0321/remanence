package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.wiring.PreparedIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import dev.hryshyn.remanence.core.data.network.AuthResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.data.network.RegistrationProblemCode

class RegistrationUseCaseOrderingTest {

    private class RecordingIdentityPort(private val trace: MutableList<String>) : RegistrationIdentityPort {
        val calls = mutableListOf<String>()
        /** Actual flow keys per prepare call (calls holds trace literals). */
        val flowKeys = mutableListOf<String>()
        var prepared = 0
        var failWithRecovery = false

        override fun prepareProvisional(flowKey: String): PreparedIdentity {
            trace += "identity"
            calls += "identity"
            flowKeys += flowKey
            if (failWithRecovery) throw IdentityRecoveryRequiredException()
            prepared++
            return PreparedIdentity(
                keyBundleId = "2f0a1234-5678-4abc-9def-aabbccdd2002",
                encryptionPublicKeysetB64Url = "CIenc",
                signingPublicKeysetB64Url = "CJsig",
            )
        }

        override fun bindStagedToOwner(bundleId: String, ownerId: String): Boolean {
            trace += "adopt"
            calls += "adopt"
            return true
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
        assertEquals(listOf("identity", "lease", "network", "close", "account", "install", "adopt"), trace)
        assertEquals(1, replacement.installCount)
    }

    @Test
    fun sameFlowRetryReusesButSuccessAndNewFlowRotateEpoch() = runTest {
        val trace = mutableListOf<String>()
        val identity = RecordingIdentityPort(trace)
        val api = FakeAuthApi(trace).apply {
            nextResult = AuthResult.Failure(
                reason = dev.hryshyn.remanence.core.data.network.AuthFailure.HTTP,
                httpStatus = 409,
                registrationProblemCode = RegistrationProblemCode.HANDLE_UNAVAILABLE,
            )
        }
        val useCase = RegistrationUseCase(identity, api, FakeAccounts(trace), RecordingReplacement(trace))

        // Same-flow 409 retry (edited handle, same target): one flow key.
        useCase.register("private@example.com", "secret-password", "@mykola")
        useCase.register("private@example.com", "secret-password", "@mykola2")
        assertEquals(2, identity.flowKeys.size)
        assertEquals(identity.flowKeys[0], identity.flowKeys[1])

        // Success rotates the epoch THROUGH the real register() path:
        // the next registration for the SAME target mints independent
        // keys instead of reusing the completed flow's candidate.
        api.nextResult = AuthResult.Success(successResponse, 201)
        useCase.register("private@example.com", "secret-password", "@mykola")
        useCase.register("private@example.com", "secret-password", "@mykola")
        assertEquals(4, identity.flowKeys.size)
        assertTrue(identity.flowKeys[2] != identity.flowKeys[0])
        assertEquals(identity.flowKeys[2], identity.flowKeys[3])

        // Explicit new flow (logout/screen boundary) also mints
        // independent keys for the same target.
        useCase.startNewFlow()
        useCase.register("private@example.com", "secret-password", "@mykola")
        assertEquals(5, identity.flowKeys.size)
        assertEquals(4, identity.flowKeys.toSet().size)

        // Same flow, different target: independent keys as well.
        useCase.register("other@example.com", "secret-password", "@mykola")
        assertEquals(6, identity.flowKeys.size)
        assertEquals(5, identity.flowKeys.toSet().size)
    }

    @Test
    fun networkFailureLeavesNoAccountRecordButKeepsWrappedIdentity() = runTest {
        val trace = mutableListOf<String>()
        val identity = RecordingIdentityPort(trace)
        val api = FakeAuthApi(trace).apply {
            nextResult = AuthResult.Failure(
                reason = dev.hryshyn.remanence.core.data.network.AuthFailure.HTTP,
                httpStatus = 409,
                registrationProblemCode = RegistrationProblemCode.HANDLE_UNAVAILABLE,
            )
        }
        val accounts = FakeAccounts(trace)
        val replacement = RecordingReplacement(trace)
        val useCase = RegistrationUseCase(identity, api, accounts, replacement)

        val outcome = useCase.register("private@example.com", "secret-password", "@mykola")

        assertEquals(
            RegistrationUseCase.Outcome.Rejected(409, RegistrationProblemCode.HANDLE_UNAVAILABLE),
            outcome,
        )
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
