package dev.hryshyn.remanence.ui.auth

import dev.hryshyn.remanence.auth.CurrentAccountPort
import dev.hryshyn.remanence.auth.RegistrationAuthApiPort
import dev.hryshyn.remanence.auth.RegistrationIdentityPort
import dev.hryshyn.remanence.auth.NoOpSessionReplacement
import dev.hryshyn.remanence.auth.RegistrationUseCase
import dev.hryshyn.remanence.wiring.PreparedIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import dev.hryshyn.remanence.core.data.network.AuthResult
import dev.hryshyn.remanence.core.data.network.RegisterRequestDto
import dev.hryshyn.remanence.core.data.network.RegisterResponseDto
import dev.hryshyn.remanence.core.data.network.RegistrationUserDto

class RegistrationViewModelTest {

    private val successResponse = RegisterResponseDto(
        user = RegistrationUserDto(
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

    private class FakeAccounts : CurrentAccountPort {
        override suspend fun recordCurrentAccount(user: RegistrationUserDto, activeKeyBundleId: String) = Unit
    }

    private fun validForm(): RegistrationFormState = RegistrationFormState(
        email = "private@example.com",
        password = "long-enough-password",
        handle = "@mykola",
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun submitCompletesOnSuccessfulRegistration() = runTest {
        val api = object : RegistrationAuthApiPort {
            var requests = 0

            override suspend fun register(request: RegisterRequestDto): AuthResult<RegisterResponseDto> {
                requests++
                assertEquals("@mykola", request.handle)
                return AuthResult.Success(successResponse, 201)
            }
        }
        val vm = RegistrationViewModel(
            useCase = RegistrationUseCase(identityPort(), api, FakeAccounts(), NoOpSessionReplacement),
            scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.onFieldChange(RegistrationField.EMAIL, validForm().email)
        vm.onFieldChange(RegistrationField.PASSWORD, validForm().password)
        vm.onFieldChange(RegistrationField.HANDLE, validForm().handle)

        vm.submit()
        advanceUntilIdle()

        assertEquals(RegistrationSubmitState.Completed, vm.submitState.value)
        assertEquals(1, api.requests)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun rejectedSubmissionShowsRedactedMessageAndAllowsRetry() = runTest {
        val api = object : RegistrationAuthApiPort {
            var requests = 0

            override suspend fun register(request: RegisterRequestDto): AuthResult<RegisterResponseDto> =
                when (++requests) {
                    1 -> AuthResult.Failure(dev.hryshyn.remanence.core.data.network.AuthFailure.HTTP, 409)
                    else -> AuthResult.Success(successResponse, 201)
                }
        }
        val vm = RegistrationViewModel(
            useCase = RegistrationUseCase(identityPort(), api, FakeAccounts(), NoOpSessionReplacement),
            scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        applyValidForm(vm)

        vm.submit()
        advanceUntilIdle()
        val failed = vm.submitState.value as RegistrationSubmitState.Failed
        assertEquals("Email or handle is unavailable.", failed.message)

        vm.submit()
        advanceUntilIdle()
        assertEquals(RegistrationSubmitState.Completed, vm.submitState.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun doubleSubmitWhilePendingIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = object : RegistrationAuthApiPort {
            var requests = 0

            override suspend fun register(request: RegisterRequestDto): AuthResult<RegisterResponseDto> {
                requests++
                gate.await()
                return AuthResult.Success(successResponse, 201)
            }
        }
        val vm = RegistrationViewModel(
            useCase = RegistrationUseCase(identityPort(), api, FakeAccounts(), NoOpSessionReplacement),
            scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        applyValidForm(vm)

        vm.submit()
        vm.submit()
        vm.submit()
        assertEquals(1, api.requests)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(RegistrationSubmitState.Completed, vm.submitState.value)
    }

    private fun identityPort() = RegistrationIdentityPort {
        PreparedIdentity(
            keyBundleId = "2f0a1234-5678-4abc-9def-aabbccdd2002",
            encryptionPublicKeysetB64Url = "CIenc",
            signingPublicKeysetB64Url = "CJsig",
        )
    }

    private fun applyValidForm(vm: RegistrationViewModel) {
        vm.onFieldChange(RegistrationField.EMAIL, validForm().email)
        vm.onFieldChange(RegistrationField.PASSWORD, validForm().password)
        vm.onFieldChange(RegistrationField.HANDLE, validForm().handle)
    }
}
