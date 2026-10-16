package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.core.data.network.ActiveKeyBundleMetadataDto
import dev.hryshyn.remanence.core.data.network.ApiBaseUrl
import dev.hryshyn.remanence.core.data.network.AuthResult
import dev.hryshyn.remanence.core.data.network.AuthTokenHolder
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
import dev.hryshyn.remanence.wiring.OwnerScopedIdentityStore
import dev.hryshyn.remanence.core.crypto.IdentityBundleRepository
import dev.hryshyn.remanence.core.crypto.KeysetKekWrapper
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
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
import org.junit.Assert.assertTrue
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
            .addInterceptor { chain ->
                val request = chain.request()
                val accessToken = coordinator.openDomainAccessToken()
                val authenticatedRequest = if (
                    accessToken != null && request.header("Authorization") == null
                ) {
                    request.newBuilder()
                        .header("Authorization", "Bearer $accessToken")
                        .build()
                } else {
                    request
                }
                chain.proceed(authenticatedRequest)
            }
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
            identity = object : RegistrationIdentityPort {
                override fun prepareProvisional(flowKey: String): PreparedIdentity =
                    PreparedIdentity(
                        keyBundleId = bundleC,
                        encryptionPublicKeysetB64Url = "CIenc",
                        signingPublicKeysetB64Url = "CJsig",
                    )

                override fun bindStagedToOwner(bundleId: String, ownerId: String): Boolean = true
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
            identity = object : RegistrationIdentityPort {
                override fun prepareProvisional(flowKey: String): PreparedIdentity =
                    PreparedIdentity(
                        keyBundleId = bundleC,
                        encryptionPublicKeysetB64Url = "CIenc",
                        signingPublicKeysetB64Url = "CJsig",
                    )

                override fun bindStagedToOwner(bundleId: String, ownerId: String): Boolean = true
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

    @Test
    fun staleRegistrationAfterLogoutAdoptsNothingAndKeepsAAndBIntact() = runBlocking {
        // Real owner-scoped filesystem: claimed legacy A, staged B.
        val root = Files.createTempDirectory("stale-race-stores").toFile()
        val kek = SoftwareKekBoundary()
        val kekAlias = "t210-stale-race-kek"
        if (!kek.hasKey(kekAlias)) kek.createAes256GcmKey(kekAlias)
        val wrapper = KeysetKekWrapper(kek)
        val stores = OwnerScopedIdentityStore(
            File(root, "identity"),
            File(root, "identity-staging"),
            wrapper,
        )
        val legacyRoot = File(root, "identity")
        IdentityBundleRepository(legacyRoot, wrapper).createFresh(kekAlias)
        val bundleA = UUID.nameUUIDFromBytes(
            (IdentityBundleRepository(legacyRoot, wrapper).loadPublicExports()
                as IdentityBundleRepository.PublicExportsResult.Available).encryptionPublicKeyset,
        ).toString()
        assertTrue(stores.claimLegacyIfMatching(ownerA.toRestString(), bundleA))
        fun sealedHashes(directory: File): Map<String, String> = listOf(
            IdentityBundleRepository.ENCRYPTION_FILE_NAME,
            IdentityBundleRepository.SIGNING_FILE_NAME,
        ).associateWith { name ->
            MessageDigest.getInstance("SHA-256").digest(File(directory, name).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
        val sealedABefore = sealedHashes(legacyRoot)

        val stored = AtomicReference(BoundRefreshCredential(ownerA, "pm_rt_a"))
        val accountOwner = AtomicReference(ownerA)
        val tokens = AuthTokenHolder("pm_at_a", "pm_rt_a")
        val apiStack = stack(stored, tokens)
        val tracking = TrackingReplacement(replacement(apiStack, accountOwner))

        // Login B completes first through the real use case over the real
        // store gate: LoggedIn, B adopted into its owner dir, tokens for B.
        val stagedB = stores.prepareProvisional("stale-race-login-flow", kekAlias)
        val loginBResponse = loginResponseB().copy(
            activeKeyBundle = loginResponseB().activeKeyBundle.copy(keyBundleId = stagedB.keyBundleId),
        )
        val loginB = LoginUseCase(
            identity = { candidate -> stores.hasIdentityFor(candidate) },
            authApi = { AuthResult.Success(loginBResponse, 200) },
            accounts = object : CurrentAccountPort {
                override suspend fun recordCurrentAccount(
                    user: RegistrationUserDto,
                    activeKeyBundleId: String,
                ) {
                    accountOwner.set(UserId.parseRest(user.userId))
                }
            },
            sessionReplacement = tracking,
            ownerStores = stores,
        )
        assertEquals(
            LoginUseCase.Outcome.LoggedIn(ownerB.toRestString(), "owner-b", stagedB.keyBundleId),
            loginB.login("b@example.com", "secret"),
        )
        assertEquals(1, tracking.installCount)
        assertEquals("pm_at_b", tokens.accessToken)
        assertTrue(
            stores.resolve(ownerB.toRestString(), stagedB.keyBundleId)
                is IdentityBundleRepository.LoadResult.Available,
        )
        val sealedBBefore = sealedHashes(File(legacyRoot, ownerB.toRestString()))
        val stagedC = stores.prepareProvisional("stale-race-register-flow", kekAlias)

        // A stale registration C pauses inside its commit holding a
        // pre-logout lease; logout then tears B's session down through the
        // real LogoutUseCase (firing the production epoch-rotation hook).
        val cEntered = CompletableDeferred<Unit>()
        val cRelease = CompletableDeferred<Unit>()
        val registerC = RegistrationUseCase(
            identity = object : RegistrationIdentityPort {
                override fun prepareProvisional(flowKey: String): PreparedIdentity =
                    PreparedIdentity(
                        keyBundleId = stagedC.keyBundleId,
                        encryptionPublicKeysetB64Url = "CIenc",
                        signingPublicKeysetB64Url = "CJsig",
                    )

                override fun bindStagedToOwner(bundleId: String, ownerId: String): Boolean =
                    stores.adoptStaged(bundleId, ownerId)
            },
            authApi = object : RegistrationAuthApiPort {
                override suspend fun register(
                    request: dev.hryshyn.remanence.core.data.network.RegisterRequestDto,
                ): AuthResult<RegisterResponseDto> =
                    AuthResult.Success(registerResponseC().copy(activeKeyBundleId = stagedC.keyBundleId), 201)
            },
            accounts = object : CurrentAccountPort {
                override suspend fun recordCurrentAccount(
                    user: RegistrationUserDto,
                    activeKeyBundleId: String,
                ) {
                    // Pause boundary (documented): the account write
                    // COMPLETES first — retained commitAccount-before-
                    // publication semantics per T209 design #4 — then the
                    // callback pauses before publication returns. Logout
                    // clears the row mid-pause; release then loses the
                    // lease. The opposite ordering (pause BEFORE the row
                    // write, leaving C's row with no publication) is a
                    // known pre-existing raw-row race in the same
                    // admission semantics, recorded as a follow-up in the
                    // task result — not redesigned here.
                    accountOwner.set(UserId.parseRest(user.userId))
                    cEntered.complete(Unit)
                    cRelease.await()
                }
            },
            sessionReplacement = tracking,
        )
        val register = async(Dispatchers.Default) {
            registerC.register("c@example.com", "secret", "owner-c")
        }
        withTimeout(5_000) { cEntered.await() }
        assertEquals(ownerC, accountOwner.get())

        var logoutHookCalls = 0
        var grantsCleared = false
        val logout = LogoutUseCase(
            serverLogout = { AuthResult.Success(Unit, 204) },
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
            grants = { grantsCleared = true },
            invalidateSessionLease = { apiStack.sessionRefreshCoordinator.invalidate() },
            onLogoutCompleted = { logoutHookCalls++ },
        )
        logout.logout()
        assertEquals(1, logoutHookCalls)
        assertTrue(grantsCleared)
        cRelease.complete(Unit)

        // The stale completion publishes nothing: no install, no
        // reopen — and the ONLY store effect allowed (inert archival)
        // never ran, because archival happens solely after a committed
        // response. The pre-logout account callback's completed write
        // stands as the retained contract prescribes (logout cleared it
        // after); no rollback is demanded of the replacement fence.
        assertEquals(RegistrationUseCase.Outcome.InvalidResponse, register.await())
        assertEquals(1, tracking.installCount)
        assertEquals(ownerB, tracking.lastInstalled)
        assertNull(accountOwner.get())
        assertNull(tokens.accessToken)
        assertNull(stored.get())
        assertDomainClosedNoOrdinaryBearer(apiStack)
        // Readiness gate: B still ready, C's staged keys never readiness.
        assertTrue(stores.hasIdentityFor(stagedB.keyBundleId))
        assertTrue(!stores.hasIdentityFor(stagedC.keyBundleId))
        // A sealed bytes and B adopted bytes byte-identical; C adopted
        // nowhere (its staged candidate is retained, unbound).
        assertEquals(sealedABefore, sealedHashes(legacyRoot))
        assertEquals(sealedBBefore, sealedHashes(File(legacyRoot, ownerB.toRestString())))
        assertTrue(!File(File(legacyRoot, ownerC.toRestString()), "identity.encryption.pwks").exists())
        assertTrue(
            stores.resolve(ownerB.toRestString(), stagedB.keyBundleId)
                is IdentityBundleRepository.LoadResult.Available,
        )
        assertTrue(
            stores.resolve(ownerA.toRestString(), bundleA)
                is IdentityBundleRepository.LoadResult.Available,
        )
        assertTrue(stores.resolveStaged(stagedC.keyBundleId) != null)
    }
}
