package dev.hryshyn.remanence.ui.auth

import dev.hryshyn.remanence.auth.CurrentAccountPort
import dev.hryshyn.remanence.auth.LoginIdentityPort
import dev.hryshyn.remanence.auth.LoginUseCase
import dev.hryshyn.remanence.auth.NoOpSessionReplacement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.hryshyn.remanence.core.data.network.ActiveKeyBundleMetadataDto
import dev.hryshyn.remanence.core.data.network.AuthResult
import dev.hryshyn.remanence.core.data.network.LoginResponseDto
import dev.hryshyn.remanence.core.data.network.RegistrationUserDto

class LoginViewModelTest {

    private val user = RegistrationUserDto(
        userId = "1f0a1234-5678-4abc-9def-aabbccdd1001",
        email = "private@example.com",
        handle = "mykola",
        createdAt = "2026-08-23T03:00:00Z",
    )

    private val bundle = ActiveKeyBundleMetadataDto(
        keyBundleId = "2f0a1234-5678-4abc-9def-aabbccdd2002",
        suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocolVersion = 1,
        status = "ACTIVE",
    )

    private fun response() = LoginResponseDto(
        user = user,
        activeKeyBundle = bundle,
        sessionId = "3f0a1234-5678-4abc-9def-aabbccdd3003",
        accessToken = "pm_at_a",
        accessExpiresAt = "2026-08-23T03:15:00Z",
        refreshToken = "pm_rt_r",
        refreshExpiresAt = "2026-09-22T03:00:00Z",
    )

    private class RecordingAccounts : CurrentAccountPort {
        var calls = 0

        override suspend fun recordCurrentAccount(userDto: RegistrationUserDto, activeKeyBundleId: String) {
            calls++
        }
    }

    private class RecordingIdentity(private val has: Boolean) : LoginIdentityPort {
        val checked = mutableListOf<String>()

        override fun hasIdentityFor(activeKeyBundleId: String): Boolean {
            checked += activeKeyBundleId
            return has
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.buildVm(
        apiResult: AuthResult<LoginResponseDto>,
        hasLocalIdentity: Boolean,
    ): Pair<LoginViewModel, RecordingIdentity> {
        val identity = RecordingIdentity(hasLocalIdentity)
        val useCase = LoginUseCase(
            identity = identity,
            authApi = { apiResult },
            accounts = RecordingAccounts(),
            sessionReplacement = NoOpSessionReplacement,
        )
        return LoginViewModel(useCase, CoroutineScope(UnconfinedTestDispatcher(testScheduler))) to identity
    }

    @Test
    fun successfulLoginWithLocalKeysReportsLoggedIn() = runTest {
        val (vm, _) = buildVm(AuthResult.Success(response(), 200), hasLocalIdentity = true)
        vm.onEmailChange("private@example.com")
        vm.onPasswordChange("secret-password")

        vm.submit()

        assertEquals(LoginSubmitState.LoggedIn(user.userId, user.handle), vm.submitState.value)
    }

    @Test
    fun loginWithoutLocalKeysSurfacesRecoveryRequiredState() = runTest {
        val (vm, identity) = buildVm(AuthResult.Success(response(), 200), hasLocalIdentity = false)
        vm.onEmailChange("private@example.com")
        vm.onPasswordChange("secret-password")

        vm.submit()

        assertEquals(LoginSubmitState.RecoveryRequired, vm.submitState.value)
        assertEquals(listOf(bundle.keyBundleId), identity.checked)
    }

    @Test
    fun wrongPasswordShowsGenericRedactedMessage() = runTest {
        val (vm, _) = buildVm(AuthResult.Failure(dev.hryshyn.remanence.core.data.network.AuthFailure.HTTP, 401), true)
        vm.onEmailChange("private@example.com")
        vm.onPasswordChange("wrong-password")

        vm.submit()

        val failed = vm.submitState.value as LoginSubmitState.Failed
        assertEquals("Incorrect email or password.", failed.message)
    }

    @Test
    fun emptyFormCannotSubmit() = runTest {
        val (vm, _) = buildVm(AuthResult.Success(response(), 200), true)
        assertFalse(LoginFormValidator.canSubmit(vm.form.value))
        vm.submit()
        assertEquals(LoginSubmitState.Idle, vm.submitState.value)
    }

}
