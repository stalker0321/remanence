package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.wiring.PreparedIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.hryshyn.remanence.core.data.network.ActiveKeyBundleMetadataDto
import dev.hryshyn.remanence.core.data.network.AuthResult
import dev.hryshyn.remanence.core.data.network.LoginRequestDto
import dev.hryshyn.remanence.core.data.network.LoginResponseDto
import dev.hryshyn.remanence.core.data.network.RegistrationUserDto

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
        val outcome = LoginUseCase(identity, api, accounts, NoOpSessionReplacement)
            .login("private@example.com", "secret-password")

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
        val outcome = LoginUseCase(identity, api, accounts, NoOpSessionReplacement)
            .login("private@example.com", "secret-password")

        assertEquals(
            LoginUseCase.Outcome.RecoveryRequired(user.userId, user.handle, bundle.keyBundleId),
            outcome,
        )
        assertEquals(listOf(bundle.keyBundleId), identity.checked)
        assertEquals(1, accounts.calls)
    }

    @Test
    fun wrongPasswordRejectedAndNothingRecorded() = runTest {
        val api = FakeApi(AuthResult.Failure(dev.hryshyn.remanence.core.data.network.AuthFailure.HTTP, 401))
        val accounts = RecordingAccounts()
        val outcome = LoginUseCase(FakeIdentity(has = true), api, accounts, NoOpSessionReplacement)
            .login("private@example.com", "bad")

        assertEquals(LoginUseCase.Outcome.Rejected(401), outcome)
        assertEquals(0, accounts.calls)
    }

    @Test
    fun offlineIsNetworkUnreachable() = runTest {
        val api = FakeApi(AuthResult.Failure(dev.hryshyn.remanence.core.data.network.AuthFailure.NETWORK))
        val outcome = LoginUseCase(FakeIdentity(true), api, RecordingAccounts(), NoOpSessionReplacement)
            .login("e", "p")
        assertEquals(LoginUseCase.Outcome.NetworkUnreachable, outcome)
    }

    @Test
    fun successfulLoginAdoptsStagedCandidateForVerifiedOwner() = runTest {
        val root = java.nio.file.Files.createTempDirectory("login-adopt").toFile()
        val stores = ownerStores(root)
        val staged = stagedBundle(stores)
        val response = successResponse().copy(
            activeKeyBundle = bundle.copy(keyBundleId = staged.keyBundleId),
        )
        // Actual store-backed readiness gate, not a hardcoded fake.
        val gate = LoginIdentityPort { candidate -> stores.hasIdentityFor(candidate) }
        val api = FakeApi(AuthResult.Success(response, 200))
        val outcome = LoginUseCase(gate, api, RecordingAccounts(), NoOpSessionReplacement, ownerStores = stores)
            .login("private@example.com", "secret-password")

        assertEquals(
            LoginUseCase.Outcome.LoggedIn(user.userId, user.handle, staged.keyBundleId),
            outcome,
        )
        // Archived under the verified owner with the exact staged keys.
        val resolved = stores.resolve(user.userId, staged.keyBundleId)
        assertTrue(resolved is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available)
        val claim = stores.readClaim(java.io.File(java.io.File(root, "identity"), user.userId))
        assertEquals(user.userId, claim?.ownerUserId)
        assertEquals(staged.keyBundleId, claim?.keyBundleId)
    }

    @Test
    fun stagedOnlyLoginBindsWithoutGeneratingReplacement() = runTest {
        val root = java.nio.file.Files.createTempDirectory("login-staged-only").toFile()
        val stores = ownerStores(root)
        val staged = stagedBundle(stores)
        val response = successResponse().copy(
            activeKeyBundle = bundle.copy(keyBundleId = staged.keyBundleId),
        )
        // Nothing installed anywhere yet (lost-ACK state): the real gate
        // reports not-ready BEFORE login...
        assertFalse(stores.hasIdentityFor(staged.keyBundleId))
        val gate = LoginIdentityPort { candidate -> stores.hasIdentityFor(candidate) }
        val api = FakeApi(AuthResult.Success(response, 200))
        // ...but binding during login flips readiness WITHOUT generating
        // replacement keys, so the outcome is LoggedIn, not RecoveryRequired.
        val outcome = LoginUseCase(gate, api, RecordingAccounts(), NoOpSessionReplacement, ownerStores = stores)
            .login("private@example.com", "secret-password")

        assertEquals(
            LoginUseCase.Outcome.LoggedIn(user.userId, user.handle, staged.keyBundleId),
            outcome,
        )
        val resolved = stores.resolve(user.userId, staged.keyBundleId)
        assertTrue(resolved is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available)
        resolved as dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available
        val reread = com.google.crypto.tink.TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
            resolved.encryptionHandle.publicKeysetHandle,
        )
        assertEquals(
            staged.encryptionPublicKeysetB64Url,
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(reread),
        )
    }

    @Test
    fun corruptStagedAdoptFailureDoesNotFailLogin() = runTest {
        val root = java.nio.file.Files.createTempDirectory("login-corrupt-staged").toFile()
        val stores = ownerStores(root)
        val stagingDir = java.io.File(java.io.File(root, "identity-staging"), bundle.keyBundleId)
        stagingDir.mkdirs()
        java.io.File(stagingDir, "identity.encryption.pwks").writeBytes(byteArrayOf(9, 9, 9))
        val api = FakeApi(AuthResult.Success(successResponse(), 200))
        val outcome = LoginUseCase(FakeIdentity(has = true), api, RecordingAccounts(), NoOpSessionReplacement, ownerStores = stores)
            .login("private@example.com", "secret-password")

        // Adoption is best-effort: the login outcome stands, nothing is
        // overwritten, and the corrupt material stays staged (bounded).
        assertEquals(
            LoginUseCase.Outcome.LoggedIn(user.userId, user.handle, bundle.keyBundleId),
            outcome,
        )
        assertTrue(stagingDir.exists())
    }

    private fun ownerStores(root: java.io.File): dev.hryshyn.remanence.wiring.OwnerScopedIdentityStore {
        val kek = SoftwareKekBoundary()
        if (!kek.hasKey("t210-login-test-kek")) kek.createAes256GcmKey("t210-login-test-kek")
        return dev.hryshyn.remanence.wiring.OwnerScopedIdentityStore(
            identityRoot = java.io.File(root, "identity"),
            stagingRoot = java.io.File(root, "identity-staging"),
            wrapper = dev.hryshyn.remanence.core.crypto.KeysetKekWrapper(kek),
        )
    }

    private fun stagedBundle(
        stores: dev.hryshyn.remanence.wiring.OwnerScopedIdentityStore,
    ): dev.hryshyn.remanence.wiring.PreparedIdentity {
        val adapter = dev.hryshyn.remanence.wiring.TinkRegistrationIdentityAdapter(
            stores,
            SoftwareKekBoundary(),
            "t210-login-test-kek",
        )
        return adapter.prepareProvisional("login-binding-flow")
    }

    private class RecordingAccounts : CurrentAccountPort {
        var calls = 0

        override suspend fun recordCurrentAccount(user: RegistrationUserDto, activeKeyBundleId: String) {
            calls++
        }
    }
}
