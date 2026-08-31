package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.core.data.network.ActiveKeyBundleMetadataDto
import dev.hryshyn.remanence.core.data.network.ApiBaseUrl
import dev.hryshyn.remanence.core.data.network.AuthResult
import dev.hryshyn.remanence.core.data.network.AuthTokenHolder
import dev.hryshyn.remanence.core.data.network.BearerAuthInterceptor
import dev.hryshyn.remanence.core.data.network.BoundRefreshCredential
import dev.hryshyn.remanence.core.data.network.CoordinatedRefreshOutcome
import dev.hryshyn.remanence.core.data.network.LoginResponseDto
import dev.hryshyn.remanence.core.data.network.ProductionApiStack
import dev.hryshyn.remanence.core.data.network.RefreshTokenReader
import dev.hryshyn.remanence.core.data.network.RegisterResponseDto
import dev.hryshyn.remanence.core.data.network.RegistrationUserDto
import dev.hryshyn.remanence.core.data.network.SessionRefreshCoordinator
import dev.hryshyn.remanence.core.data.network.SessionRotationSink
import dev.hryshyn.remanence.core.crypto.SessionRefreshRecord
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.session.SessionTokenPort
import dev.hryshyn.remanence.wiring.PreparedIdentity
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Account-boundary contract: non-closing lease before server I/O, mutex
 * serialization of B/C, atomic credential publish+open, ordinary bearer only
 * from an open domain.
 */
class SessionReplacementOrderingTest {

    private val ownerA = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a001")
    private val ownerB = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a002")
    private val ownerC = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a003")
    private val bundleB = "0198f0a0-0000-7000-8000-00000000b002"
    private val bundleC = "0198f0a0-0000-7000-8000-00000000b003"

    private fun loginResponseB() = LoginResponseDto(
        user = RegistrationUserDto(
            userId = ownerB.toRestString(),
            email = "b@example.com",
            handle = "owner-b",
            createdAt = "2026-08-23T03:00:00Z",
        ),
        activeKeyBundle = ActiveKeyBundleMetadataDto(
            keyBundleId = bundleB,
            suite = PreparedIdentity.SUITE,
            protocolVersion = 1,
            status = "ACTIVE",
        ),
        sessionId = "0198f0a0-0000-7000-8000-00000000se02",
        accessToken = "pm_at_b",
        accessExpiresAt = "2026-08-23T03:15:00Z",
        refreshToken = "pm_rt_b",
        refreshExpiresAt = "2026-09-22T03:00:00Z",
    )

    private fun registerResponseC() = RegisterResponseDto(
        user = RegistrationUserDto(
            userId = ownerC.toRestString(),
            email = "c@example.com",
            handle = "owner-c",
            createdAt = "2026-08-23T03:00:00Z",
        ),
        activeKeyBundleId = bundleC,
        accessToken = "pm_at_c",
        accessExpiresAt = "2026-08-23T03:15:00Z",
        refreshToken = "pm_rt_c",
        refreshExpiresAt = "2026-09-22T03:00:00Z",
    )

    private class TrackingReplacement(
        private val inner: SessionReplacementPort,
    ) : SessionReplacementPort {
        var installCount = 0
        var lastInstalled: UserId? = null
        val entries = AtomicInteger(0)

        override fun acquireLease(): Long = inner.acquireLease()

        override suspend fun replace(
            lease: Long,
            expectedOwner: UserId,
            accessToken: String,
            refreshToken: String,
            commitAccount: suspend () -> Unit,
        ) {
            entries.incrementAndGet()
            inner.replace(lease, expectedOwner, accessToken, refreshToken, commitAccount)
            installCount++
            lastInstalled = expectedOwner
        }
    }

    private fun stack(
        stored: AtomicReference<BoundRefreshCredential?>,
        tokens: AuthTokenHolder,
    ): ProductionApiStack = ProductionApiStack.create(
        baseUrl = ApiBaseUrl.parse("http://127.0.0.1:1/"),
        tokens = tokens,
        refreshTokenReader = RefreshTokenReader { stored.get() },
        rotationSink = object : SessionRotationSink {
            override fun rotate(accessToken: String, refreshToken: String, ownerUserId: UserId) {
                stored.set(BoundRefreshCredential(ownerUserId, refreshToken))
                tokens.updateTokens(accessToken, refreshToken)
            }

            override fun clear() {
                stored.set(null)
                tokens.clearSession()
            }
        },
    )

    private fun replacement(
        stack: ProductionApiStack,
        accountOwner: AtomicReference<UserId?>,
    ) = BoundSessionReplacement(
        coordinator = stack.sessionRefreshCoordinator,
        currentAccountOwner = { accountOwner.get() },
    )

    private fun attachedAuthorization(coordinator: SessionRefreshCoordinator): String? {
        var captured: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor { coordinator.openDomainAccessToken() })
            .addInterceptor { chain ->
                captured = chain.request().header("Authorization")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ByteArray(0).toResponseBody())
                    .build()
            }
            .build()
        client.newCall(
            Request.Builder().url("http://127.0.0.1:1/v1/capsules").build(),
        ).execute().close()
        return captured
    }

    private suspend fun assertDomainClosedNoOrdinaryBearer(
        apiStack: ProductionApiStack,
    ) {
        val coordinator = apiStack.sessionRefreshCoordinator
        assertEquals(
            CoordinatedRefreshOutcome.Invalidated,
            coordinator.refreshForBootstrap(ownerB),
        )
        assertEquals(
            CoordinatedRefreshOutcome.Invalidated,
            coordinator.refreshForBootstrap(ownerC),
        )
        assertNull(coordinator.refreshForAuthenticator("pm_at_a"))
        assertNull(coordinator.refreshForAuthenticator("pm_at_b"))
        assertNull(coordinator.openDomainAccessToken())
        assertNull(attachedAuthorization(coordinator))
    }

    private fun logoutUseCase(
        apiStack: ProductionApiStack,
        tokens: AuthTokenHolder,
        stored: AtomicReference<BoundRefreshCredential?>,
        accountOwner: AtomicReference<UserId?>,
        onServerLogout: (String) -> Unit = {},
    ) = LogoutUseCase(
        serverLogout = { access ->
            onServerLogout(access)
            AuthResult.Success(Unit, 204)
        },
        accessToken = { apiStack.sessionRefreshCoordinator.rawAccessToken() },
        tokens = object : SessionTokenPort {
            override fun readToken(): String? = stored.get()?.refreshToken
            override fun readRecord() = null
            override fun saveToken(refreshToken: String) = Unit
            override fun saveRecord(record: SessionRefreshRecord) = Unit
            override fun clearToken() {
                stored.set(null)
            }
        },
        credentialSink = LogoutCredentialSink { tokens.clearSession() },
        accounts = { accountOwner.set(null) },
        grants = {},
        invalidateSessionLease = { apiStack.sessionRefreshCoordinator.invalidate() },
    )

    @Test
    fun pauseAfterServerSuccessBeforeAccountCommitKeepsDomainClosedAndDoesNotInstall() = runBlocking {
        val stored = AtomicReference(BoundRefreshCredential(ownerA, "pm_rt_a"))
        val accountOwner = AtomicReference(ownerA)
        val tokens = AuthTokenHolder("pm_at_a", "pm_rt_a")
        val apiStack = stack(stored, tokens)
        val tracking = TrackingReplacement(replacement(apiStack, accountOwner))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val useCase = LoginUseCase(
            identity = { true },
            authApi = { AuthResult.Success(loginResponseB(), 200) },
            accounts = object : CurrentAccountPort {
                override suspend fun recordCurrentAccount(
                    user: RegistrationUserDto,
                    activeKeyBundleId: String,
                ) {
                    entered.complete(Unit)
                    release.await()
                    accountOwner.set(UserId.parseRest(user.userId))
                }
            },
            sessionReplacement = tracking,
        )

        val login = async(Dispatchers.Default) { useCase.login("b@example.com", "secret") }
        entered.await()

        assertDomainClosedNoOrdinaryBearer(apiStack)
        assertEquals(0, tracking.installCount)
        assertNull(tracking.lastInstalled)
        assertEquals(ownerA, accountOwner.get())
        assertEquals(ownerA, stored.get()?.ownerUserId)

        release.complete(Unit)
        assertEquals(
            LoginUseCase.Outcome.LoggedIn(ownerB.toRestString(), "owner-b", bundleB),
            login.await(),
        )
        assertEquals(1, tracking.installCount)
        assertEquals(ownerB, tracking.lastInstalled)
        assertEquals(ownerB, stored.get()?.ownerUserId)
        assertEquals("pm_rt_b", stored.get()?.refreshToken)
        assertEquals("pm_at_b", tokens.accessToken)
        assertEquals(
            CoordinatedRefreshOutcome.Unreachable,
            apiStack.sessionRefreshCoordinator.refreshForBootstrap(ownerB),
        )
        assertEquals("Bearer pm_at_b", attachedAuthorization(apiStack.sessionRefreshCoordinator))
    }

    @Test
    fun accountWriteFailureKeepsDomainClosedAndNeverInstalls() = runBlocking {
        val stored = AtomicReference(BoundRefreshCredential(ownerA, "pm_rt_a"))
        val accountOwner = AtomicReference(ownerA)
        val tokens = AuthTokenHolder("pm_at_a", "pm_rt_a")
        val apiStack = stack(stored, tokens)
        val tracking = TrackingReplacement(replacement(apiStack, accountOwner))
        val useCase = LoginUseCase(
            identity = { true },
            authApi = { AuthResult.Success(loginResponseB(), 200) },
            accounts = object : CurrentAccountPort {
                override suspend fun recordCurrentAccount(
                    user: RegistrationUserDto,
                    activeKeyBundleId: String,
                ) {
                    error("account write failed")
                }
            },
            sessionReplacement = tracking,
        )

        assertEquals(LoginUseCase.Outcome.InvalidResponse, useCase.login("b@example.com", "secret"))
        assertDomainClosedNoOrdinaryBearer(apiStack)
        assertEquals(0, tracking.installCount)
        assertNull(tracking.lastInstalled)
        assertNull(tokens.accessToken)
        assertNull(stored.get())
        assertEquals(ownerA, accountOwner.get())
    }

    @Test
    fun loginServerResponseAfterLogoutDoesNotCommitOrReopen() = runBlocking {
        val stored = AtomicReference(BoundRefreshCredential(ownerA, "pm_rt_a"))
        val accountOwner = AtomicReference(ownerA)
        val tokens = AuthTokenHolder("pm_at_a", "pm_rt_a")
        val apiStack = stack(stored, tokens)
        val tracking = TrackingReplacement(replacement(apiStack, accountOwner))
        val enteredServer = CompletableDeferred<Unit>()
        val releaseServer = CompletableDeferred<Unit>()
        val useCase = LoginUseCase(
            identity = { true },
            authApi = {
                enteredServer.complete(Unit)
                releaseServer.await()
                AuthResult.Success(loginResponseB(), 200)
            },
            accounts = object : CurrentAccountPort {
                override suspend fun recordCurrentAccount(
                    user: RegistrationUserDto,
                    activeKeyBundleId: String,
                ) {
                    accountOwner.set(UserId.parseRest(user.userId))
                }
            },
            sessionReplacement = tracking,
        )

        val login = async(Dispatchers.Default) { useCase.login("b@example.com", "secret") }
        enteredServer.await()
        logoutUseCase(apiStack, tokens, stored, accountOwner).logout()
        releaseServer.complete(Unit)

        assertEquals(LoginUseCase.Outcome.InvalidResponse, login.await())
        assertEquals(0, tracking.installCount)
        assertDomainClosedNoOrdinaryBearer(apiStack)
        assertNull(tokens.accessToken)
        assertNull(stored.get())
    }

    @Test
    fun registrationServerResponseAfterLogoutDoesNotCommitOrReopen() = runBlocking {
        val stored = AtomicReference(BoundRefreshCredential(ownerA, "pm_rt_a"))
        val accountOwner = AtomicReference(ownerA)
        val tokens = AuthTokenHolder("pm_at_a", "pm_rt_a")
        val apiStack = stack(stored, tokens)
        val tracking = TrackingReplacement(replacement(apiStack, accountOwner))
        val enteredServer = CompletableDeferred<Unit>()
        val releaseServer = CompletableDeferred<Unit>()
        val useCase = RegistrationUseCase(
            identity = {
                PreparedIdentity(
                    keyBundleId = bundleC,
                    encryptionPublicKeysetB64Url = "CIenc",
                    signingPublicKeysetB64Url = "CJsig",
                )
            },
            authApi = object : RegistrationAuthApiPort {
                override suspend fun register(
                    request: dev.hryshyn.remanence.core.data.network.RegisterRequestDto,
                ): AuthResult<RegisterResponseDto> {
                    enteredServer.complete(Unit)
                    releaseServer.await()
                    return AuthResult.Success(registerResponseC(), 201)
                }
            },
            accounts = object : CurrentAccountPort {
                override suspend fun recordCurrentAccount(
                    user: RegistrationUserDto,
                    activeKeyBundleId: String,
                ) {
                    accountOwner.set(UserId.parseRest(user.userId))
                }
            },
            sessionReplacement = tracking,
        )

        val register = async(Dispatchers.Default) {
            useCase.register("c@example.com", "secret", "owner-c")
        }
        enteredServer.await()
        logoutUseCase(apiStack, tokens, stored, accountOwner).logout()
        releaseServer.complete(Unit)

        assertEquals(RegistrationUseCase.Outcome.InvalidResponse, register.await())
        assertEquals(0, tracking.installCount)
        assertDomainClosedNoOrdinaryBearer(apiStack)
        assertNull(tokens.accessToken)
        assertNull(stored.get())
    }

    @Test
    fun logoutRevocationWindowOrdinaryRequestHasNoHeaderWhileRawBearerRemains() = runBlocking {
        val stored = AtomicReference(BoundRefreshCredential(ownerA, "pm_rt_a"))
        val tokens = AuthTokenHolder("pm_at_a", "pm_rt_a")
        val apiStack = stack(stored, tokens)
        val accountOwner = AtomicReference(ownerA)
        var revokedWith: String? = null
        val enteredRevocation = CompletableDeferred<Unit>()
        val releaseRevocation = CompletableDeferred<Unit>()
        val logout = logoutUseCase(apiStack, tokens, stored, accountOwner) { access ->
            revokedWith = access
            enteredRevocation.complete(Unit)
            runBlocking { releaseRevocation.await() }
        }

        assertEquals("Bearer pm_at_a", attachedAuthorization(apiStack.sessionRefreshCoordinator))
        val job = async(Dispatchers.Default) { logout.logout() }
        enteredRevocation.await()
        assertEquals("pm_at_a", revokedWith)
        assertEquals("pm_at_a", apiStack.sessionRefreshCoordinator.rawAccessToken())
        assertNull(apiStack.sessionRefreshCoordinator.openDomainAccessToken())
        assertNull(attachedAuthorization(apiStack.sessionRefreshCoordinator))
        releaseRevocation.complete(Unit)
        job.await()
        assertNull(tokens.accessToken)
        assertNull(attachedAuthorization(apiStack.sessionRefreshCoordinator))
    }

    @Test
    fun successfulOrderOpensExactlyOwnerB() = runBlocking {
        val stored = AtomicReference(BoundRefreshCredential(ownerA, "pm_rt_a"))
        val accountOwner = AtomicReference(ownerA)
        val tokens = AuthTokenHolder("pm_at_a", "pm_rt_a")
        val apiStack = stack(stored, tokens)
        val tracking = TrackingReplacement(replacement(apiStack, accountOwner))
        val useCase = LoginUseCase(
            identity = { true },
            authApi = { AuthResult.Success(loginResponseB(), 200) },
            accounts = object : CurrentAccountPort {
                override suspend fun recordCurrentAccount(
                    user: RegistrationUserDto,
                    activeKeyBundleId: String,
                ) {
                    accountOwner.set(UserId.parseRest(user.userId))
                }
            },
            sessionReplacement = tracking,
        )

        assertEquals(
            LoginUseCase.Outcome.LoggedIn(ownerB.toRestString(), "owner-b", bundleB),
            useCase.login("b@example.com", "secret"),
        )
        assertEquals(1, tracking.installCount)
        assertEquals(ownerB, tracking.lastInstalled)
        assertEquals(ownerB, stored.get()?.ownerUserId)
        assertEquals(ownerB, accountOwner.get())
        assertEquals("pm_rt_b", stored.get()?.refreshToken)
        assertEquals("pm_at_b", tokens.accessToken)
        assertEquals("Bearer pm_at_b", attachedAuthorization(apiStack.sessionRefreshCoordinator))
        assertEquals(
            CoordinatedRefreshOutcome.Unreachable,
            apiStack.sessionRefreshCoordinator.refreshForBootstrap(ownerB),
        )
        assertEquals(
            CoordinatedRefreshOutcome.Invalidated,
            apiStack.sessionRefreshCoordinator.refreshForBootstrap(ownerA),
        )
    }

    @Test
    fun concurrentLoginBAndRegistrationCSerializeToConsistentWinner() = runBlocking {
        val stored = AtomicReference(BoundRefreshCredential(ownerA, "pm_rt_a"))
        val accountOwner = AtomicReference(ownerA)
        val tokens = AuthTokenHolder("pm_at_a", "pm_rt_a")
        val apiStack = stack(stored, tokens)
        val tracking = TrackingReplacement(replacement(apiStack, accountOwner))
        val bEntered = CompletableDeferred<Unit>()
        val bRelease = CompletableDeferred<Unit>()
        val cEntered = CompletableDeferred<Unit>()
        val cRelease = CompletableDeferred<Unit>()

        val loginB = LoginUseCase(
            identity = { true },
            authApi = { AuthResult.Success(loginResponseB(), 200) },
            accounts = object : CurrentAccountPort {
                override suspend fun recordCurrentAccount(
                    user: RegistrationUserDto,
                    activeKeyBundleId: String,
                ) {
                    bEntered.complete(Unit)
                    bRelease.await()
                    accountOwner.set(UserId.parseRest(user.userId))
                }
            },
            sessionReplacement = tracking,
        )
        val registerC = RegistrationUseCase(
            identity = {
                PreparedIdentity(
                    keyBundleId = bundleC,
                    encryptionPublicKeysetB64Url = "CIenc",
                    signingPublicKeysetB64Url = "CJsig",
                )
            },
            authApi = object : RegistrationAuthApiPort {
                override suspend fun register(
                    request: dev.hryshyn.remanence.core.data.network.RegisterRequestDto,
                ) = AuthResult.Success(registerResponseC(), 201)
            },
            accounts = object : CurrentAccountPort {
                override suspend fun recordCurrentAccount(
                    user: RegistrationUserDto,
                    activeKeyBundleId: String,
                ) {
                    cEntered.complete(Unit)
                    cRelease.await()
                    accountOwner.set(UserId.parseRest(user.userId))
                }
            },
            sessionReplacement = tracking,
        )

        val login = async(Dispatchers.Default) { loginB.login("b@example.com", "secret") }
        bEntered.await()

        val register = async(Dispatchers.Default) {
            registerC.register("c@example.com", "secret", "owner-c")
        }
        withTimeout(1_000) {
            while (tracking.entries.get() < 2) yield()
        }
        assertFalse(cEntered.isCompleted)
        assertDomainClosedNoOrdinaryBearer(apiStack)
        assertEquals(0, tracking.installCount)
        assertEquals(ownerA, accountOwner.get())

        bRelease.complete(Unit)
        assertEquals(
            LoginUseCase.Outcome.LoggedIn(ownerB.toRestString(), "owner-b", bundleB),
            login.await(),
        )
        cEntered.await()
        assertEquals(1, tracking.installCount)
        assertEquals(ownerB, tracking.lastInstalled)
        assertEquals(ownerB, accountOwner.get())
        assertDomainClosedNoOrdinaryBearer(apiStack)

        cRelease.complete(Unit)
        assertEquals(
            RegistrationUseCase.Outcome.Registered(ownerC.toRestString(), "owner-c", bundleC),
            register.await(),
        )
        assertEquals(2, tracking.installCount)
        assertEquals(ownerC, tracking.lastInstalled)
        assertEquals(ownerC, stored.get()?.ownerUserId)
        assertEquals(ownerC, accountOwner.get())
        assertEquals("pm_rt_c", stored.get()?.refreshToken)
        assertEquals("pm_at_c", tokens.accessToken)
        assertEquals("Bearer pm_at_c", attachedAuthorization(apiStack.sessionRefreshCoordinator))
        assertEquals(
            CoordinatedRefreshOutcome.Unreachable,
            apiStack.sessionRefreshCoordinator.refreshForBootstrap(ownerC),
        )
        assertEquals(
            CoordinatedRefreshOutcome.Invalidated,
            apiStack.sessionRefreshCoordinator.refreshForBootstrap(ownerB),
        )
    }
}
