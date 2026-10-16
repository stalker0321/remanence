package dev.hryshyn.remanence.session

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import javax.crypto.KeyGenerator
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import dev.hryshyn.remanence.core.data.network.ApiBaseUrl
import dev.hryshyn.remanence.core.data.network.AuthTokenHolder
import dev.hryshyn.remanence.core.data.network.BoundRefreshCredential
import dev.hryshyn.remanence.core.data.network.CoordinatedRefreshOutcome
import dev.hryshyn.remanence.core.data.network.ProductionApiStack
import dev.hryshyn.remanence.core.data.network.RefreshTokenReader
import dev.hryshyn.remanence.core.data.network.SessionRotationSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.hryshyn.remanence.core.crypto.KekBoundary
import dev.hryshyn.remanence.core.crypto.SessionRefreshRecord
import dev.hryshyn.remanence.core.crypto.SessionTokenStore
import dev.hryshyn.remanence.core.model.UserId

/**
 * Cold-start bootstrap proof (FIX-M1-007-05): only the ROTATING REFRESH token
 * is persisted sealed; Active requires a successful live refresh; transient
 * connectivity failures retain the local account as offline-capable.
 */
class SessionBootstrapTest {

    private val bundleA = "00000000-0000-4000-8000-000000000001"
    private val bundleB = "00000000-0000-4000-8000-000000000002"
    private val ownerA = "0198f0a0-0000-7000-8000-00000000a001"
    private val ownerB = "0198f0a0-0000-7000-8000-00000000a002"

    private class FakeIdentity(private val localBundleId: String? = "00000000-0000-4000-8000-000000000001") :
        IdentityAvailabilityPort {
        var calls: Int = 0
            private set
        var checkedBundleId: String? = null
            private set

        override fun hasIdentityFor(activeKeyBundleId: String): Boolean {
            calls++
            checkedBundleId = activeKeyBundleId
            return localBundleId == activeKeyBundleId
        }
    }

    private class SoftwareBoundary : KekBoundary {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun hasKey(alias: String): Boolean = true
        override fun createAes256GcmKey(alias: String) = Unit
        override fun loadKekAead(alias: String): com.google.crypto.tink.Aead =
            com.google.crypto.tink.subtle.AesGcmJce(key.encoded)
    }

    /** Real sealed-token behavior over a software KEK, adapted to the port. */
    private class TokenPort(directory: File, saved: SessionRefreshRecord?) : SessionTokenPort {
        val store = SessionTokenStore(directory, SoftwareBoundary(), "session-test")
        var clearCount: Int = 0
            private set

        init {
            if (saved != null) store.save(saved)
        }

        override fun readToken(): String? = store.load()?.refreshToken

        override fun readRecord(): SessionRefreshRecord? = store.load()

        override fun saveToken(refreshToken: String) {
            val existing = store.load() ?: return
            store.save(SessionRefreshRecord(existing.ownerUserId, refreshToken))
        }

        override fun saveRecord(record: SessionRefreshRecord) {
            store.save(record)
        }

        override fun clearToken() {
            clearCount++
            store.clear()
        }
    }

    private class FakeRefresher(
        var outcome: SessionRefreshOutcome,
        private val tokenPresent: Boolean,
        private val tokens: TokenPort? = null,
    ) : SessionRefresher {
        var calls: Int = 0
        var onRefreshEntry: (suspend () -> Unit)? = null

        override suspend fun hasStoredToken(): Boolean = tokenPresent

        override suspend fun refresh(expectedOwner: UserId): SessionRefreshOutcome {
            calls++
            onRefreshEntry?.invoke()
            val record = tokens?.readRecord()
            if (record == null || record.ownerUserId != expectedOwner) {
                return SessionRefreshOutcome.Invalidated
            }
            when (val result = outcome) {
                is SessionRefreshOutcome.Rotated -> tokens?.saveToken(result.refreshToken)
                SessionRefreshOutcome.Rejected,
                SessionRefreshOutcome.NoToken,
                -> tokens?.clearToken()
                else -> Unit
            }
            return outcome
        }
    }

    private data class Fixture(
        val bootstrap: SessionBootstrap,
        val tokens: TokenPort,
        val refresher: FakeRefresher,
        val identity: IdentityAvailabilityPort,
    )

    private fun fixture(
        savedRefresh: String? = "stored-refresh-token",
        recordOwner: String = ownerA,
        refresherOutcome: SessionRefreshOutcome = SessionRefreshOutcome.Rotated("fresh-access", "fresh-refresh"),
        identity: IdentityAvailabilityPort = FakeIdentity(),
        summary: PersistedAccountSummary? = PersistedAccountSummary(ownerA, "mykola", bundleA),
        tokenPresent: Boolean? = null,
        recoveryReady: Boolean = true,
        refreshForRecovery: Boolean = false,
        binder: (ownerUserId: String, bundleId: String) -> Boolean = { _, _ -> true },
    ): Fixture {
        val dir = createTempDirectory("session").toFile().apply { deleteOnExit() }
        val saved = savedRefresh?.let {
            SessionRefreshRecord(UserId.parseRest(recordOwner), it)
        }
        val tokens = TokenPort(dir, saved)
        val refresher = FakeRefresher(
            refresherOutcome,
            tokenPresent ?: (savedRefresh != null),
            tokens,
        )
        val bootstrap = SessionBootstrap(tokens, identity, { summary }, refresher,
            recoveryReady = { recoveryReady }, refreshForRecovery = refreshForRecovery,
            verifyOwnerBinding = binder)
        return Fixture(bootstrap, tokens, refresher, identity)
    }

    @Test
    fun coldStartWithoutTokenIsSignedOut() = runBlocking {
        assertEquals(SessionState.SignedOut, fixture(savedRefresh = null).bootstrap.bootstrap())
    }

    @Test fun unfinishedRecoveryBlocksOnlineAndOfflineWithoutErasingAccount() = runBlocking {
        val online = fixture(recoveryReady = false)
        assertEquals(SessionState.RecoverySetupRequired(ownerA, "mykola", bundleA), online.bootstrap.bootstrap())
        assertEquals(1, online.refresher.calls)
        assertEquals(0, online.tokens.clearCount)
        val offline = fixture(recoveryReady = false, refresherOutcome = SessionRefreshOutcome.Unreachable)
        assertTrue(offline.bootstrap.bootstrap() is SessionState.RecoverySetupRequired)
        assertEquals(0, offline.tokens.clearCount)
    }

    @Test fun recoverySessionCanRefreshWithoutGeneratingMissingIdentity() = runBlocking {
        val f = fixture(identity = FakeIdentity(null), refreshForRecovery = true)
        assertEquals(SessionState.RecoveryRequired, f.bootstrap.bootstrap())
        assertEquals(1, f.refresher.calls)
    }

    @Test
    fun missingIdentityIsRecoveryRequiredBeforeAnyNetworkCall() = runBlocking {
        val f = fixture(
            identity = FakeIdentity(localBundleId = null),
            refresherOutcome = SessionRefreshOutcome.Rejected,
        )

        assertEquals(SessionState.RecoveryRequired, f.bootstrap.bootstrap())
    }

    @Test
    fun coldUpgradeBindsUnclaimedLegacyAndOpensPrivateKeys() = runBlocking {
        val root = createTempDirectory("bootstrap-stores").toFile()
        val kek = dev.hryshyn.remanence.auth.SoftwareKekBoundary()
        if (!kek.hasKey("t210-bootstrap-kek")) kek.createAes256GcmKey("t210-bootstrap-kek")
        val wrapper = dev.hryshyn.remanence.core.crypto.KeysetKekWrapper(kek)
        val stores = dev.hryshyn.remanence.wiring.OwnerScopedIdentityStore(
            identityRoot = File(root, "identity"),
            stagingRoot = File(root, "identity-staging"),
            wrapper = wrapper,
        )
        // Upgrade fixture: valid sealed session + Room row + legacy keys,
        // but NO claim marker (pre-T210 install).
        dev.hryshyn.remanence.core.crypto.IdentityBundleRepository(
            File(root, "identity"),
            wrapper,
        ).createFresh("t210-bootstrap-kek")
        val exports = dev.hryshyn.remanence.core.crypto.IdentityBundleRepository(
            File(root, "identity"),
            wrapper,
        ).loadPublicExports() as dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.PublicExportsResult.Available
        val derived = java.util.UUID.nameUUIDFromBytes(exports.encryptionPublicKeyset).toString()
        val availability = object : IdentityAvailabilityPort {
            override fun hasIdentityFor(activeKeyBundleId: String): Boolean =
                stores.hasIdentityFor(activeKeyBundleId)
        }
        val bindings = mutableListOf<Pair<String, String>>()

        // Online: refresh proves the session, the gate binds the proven
        // owner, and the private load actually opens (not just flags).
        // The binder mirrors production: attempt the claim, then prove
        // owner-bound private resolution.
        val online = fixture(
            identity = availability,
            summary = PersistedAccountSummary(ownerA, "mykola", derived),
            binder = { owner, bundle ->
                bindings += owner to bundle
                stores.claimLegacyIfMatching(owner, bundle)
                stores.resolve(owner, bundle)
                    is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available
            },
        )
        val active = online.bootstrap.bootstrap() as SessionState.Active
        assertEquals(derived, active.activeKeyBundleId)
        assertEquals(listOf(ownerA to derived), bindings)
        assertTrue(
            stores.resolve(ownerA, derived)
                is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available,
        )

        // Unknown/conflicting B is refused without touching A's binding.
        assertEquals(SessionState.RecoveryRequired, fixture(
            identity = availability,
            summary = PersistedAccountSummary(ownerA, "mykola", bundleB),
        ).bootstrap.bootstrap())
        assertEquals(
            ownerA to derived,
            stores.readClaim(File(root, "identity"))?.let { it.ownerUserId to it.keyBundleId },
        )
        assertTrue(
            stores.resolve(ownerB, derived)
                is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.RecoveryRequired,
        )
    }

    @Test
    fun coldUpgradeOfflineKeepsPrivateUsabilityWithoutRelogin() = runBlocking {
        val root = createTempDirectory("bootstrap-offline").toFile()
        val kek = dev.hryshyn.remanence.auth.SoftwareKekBoundary()
        if (!kek.hasKey("t210-offline-kek")) kek.createAes256GcmKey("t210-offline-kek")
        val wrapper = dev.hryshyn.remanence.core.crypto.KeysetKekWrapper(kek)
        val stores = dev.hryshyn.remanence.wiring.OwnerScopedIdentityStore(
            identityRoot = File(root, "identity"),
            stagingRoot = File(root, "identity-staging"),
            wrapper = wrapper,
        )
        dev.hryshyn.remanence.core.crypto.IdentityBundleRepository(
            File(root, "identity"),
            wrapper,
        ).createFresh("t210-offline-kek")
        val exports = dev.hryshyn.remanence.core.crypto.IdentityBundleRepository(
            File(root, "identity"),
            wrapper,
        ).loadPublicExports() as dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.PublicExportsResult.Available
        val derived = java.util.UUID.nameUUIDFromBytes(exports.encryptionPublicKeyset).toString()
        val availability = object : IdentityAvailabilityPort {
            override fun hasIdentityFor(activeKeyBundleId: String): Boolean =
                stores.hasIdentityFor(activeKeyBundleId)
        }

        // Offline with a bound record, agreeing summary, and proven exact
        // keys: OfflineActive AND the private load opens — no re-login.
        val offline = fixture(
            identity = availability,
            summary = PersistedAccountSummary(ownerA, "mykola", derived),
            refresherOutcome = SessionRefreshOutcome.Unreachable,
            binder = { owner, bundle ->
                stores.claimLegacyIfMatching(owner, bundle)
                stores.resolve(owner, bundle)
                    is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available
            },
        )
        val state = offline.bootstrap.bootstrap() as SessionState.OfflineActive
        assertEquals(derived, state.activeKeyBundleId)
        assertTrue(
            stores.resolve(ownerA, derived)
                is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available,
        )
        // A second adopted owner stays independently visible, holder-free.
        val preparedB = stores.prepareProvisional("bootstrap-owner-flow", "t210-offline-kek")
        assertTrue(stores.adoptStaged(preparedB.keyBundleId, ownerB))
        assertTrue(availability.hasIdentityFor(preparedB.keyBundleId))
    }

    @Test
    fun conflictingLegacyClaimRefusesActiveWithoutClearingCredentials() = runBlocking {
        val root = createTempDirectory("bootstrap-conflict").toFile()
        val kek = dev.hryshyn.remanence.auth.SoftwareKekBoundary()
        if (!kek.hasKey("t210-conflict-kek")) kek.createAes256GcmKey("t210-conflict-kek")
        val wrapper = dev.hryshyn.remanence.core.crypto.KeysetKekWrapper(kek)
        val stores = dev.hryshyn.remanence.wiring.OwnerScopedIdentityStore(
            identityRoot = File(root, "identity"),
            stagingRoot = File(root, "identity-staging"),
            wrapper = wrapper,
        )
        dev.hryshyn.remanence.core.crypto.IdentityBundleRepository(
            File(root, "identity"),
            wrapper,
        ).createFresh("t210-conflict-kek")
        val exports = dev.hryshyn.remanence.core.crypto.IdentityBundleRepository(
            File(root, "identity"),
            wrapper,
        ).loadPublicExports() as dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.PublicExportsResult.Available
        val derived = java.util.UUID.nameUUIDFromBytes(exports.encryptionPublicKeyset).toString()
        // The legacy home is validly bound to A; B's persisted row points
        // at A's bundle (conflicting owner).
        assertTrue(stores.claimLegacyIfMatching(ownerA, derived))
        val availability = object : IdentityAvailabilityPort {
            override fun hasIdentityFor(activeKeyBundleId: String): Boolean =
                stores.hasIdentityFor(activeKeyBundleId)
        }

        // The public scan still sees A's bundle, but B's bootstrap must NOT
        // emit Active with unusable keys — and must NOT clear B's sealed
        // credentials or touch A's keys to resolve the UI state.
        val conflicted = fixture(
            recordOwner = ownerB,
            identity = availability,
            summary = PersistedAccountSummary(ownerB, "other", derived),
            binder = { owner, bundle ->
                stores.claimLegacyIfMatching(owner, bundle)
                stores.resolve(owner, bundle)
                    is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available
            },
        )
        assertEquals(SessionState.RecoveryRequired, conflicted.bootstrap.bootstrap())
        assertEquals(0, conflicted.tokens.clearCount)
        assertEquals(
            ownerA to derived,
            stores.readClaim(File(root, "identity"))?.let { it.ownerUserId to it.keyBundleId },
        )
        assertTrue(
            stores.resolve(ownerA, derived)
                is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available,
        )
        assertTrue(
            stores.resolve(ownerB, derived)
                is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.RecoveryRequired,
        )
    }

    @Test
    fun matchingPersistedBundleProducesNormalActiveState() = runBlocking {
        val f = fixture(identity = FakeIdentity(localBundleId = bundleA))

        val active = f.bootstrap.bootstrap() as SessionState.Active
        val observed = f.identity as FakeIdentity

        assertEquals(bundleA, observed.checkedBundleId)
        assertEquals(bundleA, active.activeKeyBundleId)
        assertEquals(1, f.refresher.calls)
    }

    @Test
    fun differentPersistedBundleCannotUseAnotherAccountsLocalIdentity() = runBlocking {
        val f = fixture(
            recordOwner = ownerB,
            identity = FakeIdentity(localBundleId = bundleA),
            summary = PersistedAccountSummary(ownerB, "other", bundleB),
        )

        assertEquals(SessionState.RecoveryRequired, f.bootstrap.bootstrap())
        val observed = f.identity as FakeIdentity
        assertEquals(1, observed.calls)
        assertEquals(0, f.refresher.calls)
        assertEquals(bundleB, observed.checkedBundleId)
    }

    @Test
    fun missingPersistedSummaryFailsClosedBeforeRefresh() = runBlocking {
        val f = fixture(summary = null)

        assertEquals(SessionState.SignedOut, f.bootstrap.bootstrap())
        assertEquals(0, (f.identity as FakeIdentity).calls)
        assertEquals(0, f.refresher.calls)
        assertEquals(1, f.tokens.clearCount)
    }

    @Test
    fun realAuthInvalidRefreshMapsToSignedOutAndClearsStoredToken() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/auth/refresh") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val body = """{"code":"AUTH_INVALID"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/problem+json")
            exchange.sendResponseHeaders(401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val f = fixture()
            val holder = AuthTokenHolder(initialAccess = "old-access")
            val stack = ProductionApiStack.create(
                baseUrl = ApiBaseUrl.parse("http://127.0.0.1:${server.address.port}/"),
                tokens = holder,
                refreshTokenReader = RefreshTokenReader {
                    f.tokens.readRecord()?.let { record ->
                        BoundRefreshCredential(record.ownerUserId, record.refreshToken)
                    }
                },
                rotationSink = object : SessionRotationSink {
                    override fun rotate(
                        accessToken: String,
                        refreshToken: String,
                        ownerUserId: UserId,
                    ) = error("401 refresh must not rotate")

                    override fun clear() {
                        f.tokens.clearToken()
                        holder.clearSession()
                    }
                },
            )
            val bootstrap = SessionBootstrap(
                tokens = f.tokens,
                identity = f.identity,
                account = { PersistedAccountSummary(ownerA, "mykola", bundleA) },
                refresher = object : SessionRefresher {
                    override suspend fun hasStoredToken(): Boolean =
                        stack.sessionRefreshCoordinator.hasStoredToken()

                    override suspend fun refresh(expectedOwner: UserId): SessionRefreshOutcome =
                        when (stack.sessionRefreshCoordinator.refreshForBootstrap(expectedOwner)) {
                            is CoordinatedRefreshOutcome.Rotated,
                            is CoordinatedRefreshOutcome.Reused,
                            -> error("401 refresh must not produce active credentials")
                            CoordinatedRefreshOutcome.Rejected -> SessionRefreshOutcome.Rejected
                            CoordinatedRefreshOutcome.NoToken -> SessionRefreshOutcome.NoToken
                            CoordinatedRefreshOutcome.Unreachable -> SessionRefreshOutcome.Unreachable
                            CoordinatedRefreshOutcome.Unavailable -> SessionRefreshOutcome.Unavailable
                            CoordinatedRefreshOutcome.Invalidated -> SessionRefreshOutcome.Invalidated
                        }
                },
            )

            assertEquals(SessionState.SignedOut, bootstrap.bootstrap())
            assertNull(f.tokens.readRecord())
            assertNull(holder.accessToken)
            assertNull(holder.refreshToken)
            assertEquals(1, f.tokens.clearCount)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun pauseAfterReadThenPublishedBMismatchDoesNotClearB() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f = fixture(
            recordOwner = ownerA,
            summary = PersistedAccountSummary(ownerB, "other", bundleB),
        )
        f.bootstrap.onAfterRecordRead = {
            entered.complete(Unit)
            runBlocking { release.await() }
        }
        val job = async { f.bootstrap.bootstrap() }
        entered.await()
        assertEquals(ownerA, f.tokens.readRecord()?.ownerUserId?.toRestString())
        f.tokens.saveRecord(
            SessionRefreshRecord(UserId.parseRest(ownerB), "pm_rt_published-b"),
        )
        release.complete(Unit)
        assertEquals(SessionState.SignedOut, job.await())
        assertEquals(0, f.refresher.calls)
        assertEquals(0, f.tokens.clearCount)
        assertEquals(ownerB, f.tokens.readRecord()?.ownerUserId?.toRestString())
        assertEquals("pm_rt_published-b", f.tokens.readToken())
    }

    @Test
    fun crashWindowRecordBWithAccountANeverActivatesOnSuccessOrNetwork() = runBlocking {
        for (outcome in listOf(
            SessionRefreshOutcome.Rotated("fresh-access", "fresh-refresh"),
            SessionRefreshOutcome.Unreachable,
        )) {
            val f = fixture(
                recordOwner = ownerB,
                refresherOutcome = outcome,
                summary = PersistedAccountSummary(ownerA, "mykola", bundleA),
            )
            assertEquals(SessionState.SignedOut, f.bootstrap.bootstrap())
            assertEquals(0, f.refresher.calls)
            assertEquals(1, f.tokens.clearCount)
            assertEquals(null, f.tokens.readToken())
        }
    }

    @Test
    fun validatedOwnerAThenReplacementBBeforeRefreshDoesNotActivateAOnSuccessOrNetwork() = runBlocking {
        for (outcome in listOf(
            SessionRefreshOutcome.Rotated("fresh-access", "fresh-refresh"),
            SessionRefreshOutcome.Unreachable,
        )) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val f = fixture(refresherOutcome = outcome)
            f.refresher.onRefreshEntry = {
                entered.complete(Unit)
                release.await()
            }
            val job = async { f.bootstrap.bootstrap() }
            entered.await()
            f.tokens.saveRecord(
                SessionRefreshRecord(UserId.parseRest(ownerB), "pm_rt_replacement-b"),
            )
            release.complete(Unit)
            val state = job.await()
            assertEquals(SessionState.RequiresConnectivity, state)
            assertEquals(1, f.refresher.calls)
            assertEquals(ownerB, f.tokens.readRecord()?.ownerUserId?.toRestString())
            assertEquals("pm_rt_replacement-b", f.tokens.readToken())
        }
    }

    @Test
    fun unusablePersistedBundleIsRecoveryRequiredBeforeRefresh() = runBlocking {
        val f = fixture(
            summary = PersistedAccountSummary(ownerA, "mykola", "not-a-bundle"),
        )

        assertEquals(SessionState.RecoveryRequired, f.bootstrap.bootstrap())
        assertEquals(0, (f.identity as FakeIdentity).calls)
        assertEquals(0, f.refresher.calls)
    }

    @Test
    fun activeOnlyAfterLiveRefreshRotatesAndRepersistsTheNewRefreshToken() = runBlocking {
        val f = fixture()

        val state = f.bootstrap.bootstrap()

        val active = state as SessionState.Active
        assertEquals(ownerA, active.userId)
        assertEquals("mykola", active.handle)
        assertEquals(bundleA, active.activeKeyBundleId)
        assertFalse(!active.hasEncryptionKeyset || !active.hasSigningKeyset)
        // The rotated REFRESH token replaced the stored one, sealed on disk.
        assertEquals("fresh-refresh", f.tokens.readToken())
    }

    @Test
    fun rejectedRefreshSignsOut() = runBlocking {
        val f = fixture(refresherOutcome = SessionRefreshOutcome.Rejected)

        assertEquals(SessionState.SignedOut, f.bootstrap.bootstrap())
        assertEquals(1, f.tokens.clearCount)
    }

    @Test
    fun unreachableRefreshKeepsTheTokenAndActivatesOffline() = runBlocking {
        val f = fixture(refresherOutcome = SessionRefreshOutcome.Unreachable)

        assertEquals(
            SessionState.OfflineActive(
                userId = ownerA,
                handle = "mykola",
                hasEncryptionKeyset = true,
                hasSigningKeyset = true,
                activeKeyBundleId = bundleA,
            ),
            f.bootstrap.bootstrap(),
        )
        // The stored lineage stays intact for a later attempt.
        assertEquals("stored-refresh-token", f.tokens.readToken())
        assertEquals(0, f.tokens.clearCount)
    }

    @Test
    fun reusedRefreshIsActiveWithoutASecondNetworkOutcome() = runBlocking {
        val f = fixture(refresherOutcome = SessionRefreshOutcome.Reused("already-fresh-access"))

        val active = f.bootstrap.bootstrap() as SessionState.Active
        assertEquals(ownerA, active.userId)
        assertEquals(bundleA, active.activeKeyBundleId)
        assertEquals("stored-refresh-token", f.tokens.readToken())
        assertEquals(0, f.tokens.clearCount)
    }

    @Test
    fun unavailableRefreshDoesNotActivateOffline() = runBlocking {
        val f = fixture(refresherOutcome = SessionRefreshOutcome.Unavailable)

        assertEquals(SessionState.RequiresConnectivity, f.bootstrap.bootstrap())
        assertEquals("stored-refresh-token", f.tokens.readToken())
        assertEquals(0, f.tokens.clearCount)
    }

    @Test
    fun invalidatedRefreshDoesNotActivateOffline() = runBlocking {
        val f = fixture(refresherOutcome = SessionRefreshOutcome.Invalidated)

        assertEquals(SessionState.RequiresConnectivity, f.bootstrap.bootstrap())
        assertEquals("stored-refresh-token", f.tokens.readToken())
    }

    @Test
    fun noTokenAfterCoordinatorLockIsSignedOut() = runBlocking {
        val f = fixture(refresherOutcome = SessionRefreshOutcome.NoToken)

        assertEquals(SessionState.SignedOut, f.bootstrap.bootstrap())
    }

    @Test
    fun identityLookupFailureIsRecoveryRequiredBeforeRefresh() = runBlocking {
        val f = fixture(
            identity = object : IdentityAvailabilityPort {
                override fun hasIdentityFor(activeKeyBundleId: String): Boolean {
                    throw IllegalStateException("identity store unavailable")
                }
            },
            refresherOutcome = SessionRefreshOutcome.Unreachable,
        )

        assertEquals(SessionState.RecoveryRequired, f.bootstrap.bootstrap())
        assertEquals(0, f.refresher.calls)
    }

    @Test
    fun logoutClearsTheSealedTokenAndReturnsToSignedOutWhileKeysRemain() = runBlocking {
        val f = fixture()

        val afterLogout = f.bootstrap.logout()

        assertEquals(SessionState.SignedOut, afterLogout)
        assertEquals(1, f.tokens.clearCount)
        // A subsequent cold start finds no token: signed out, keys still on disk.
        assertEquals(SessionState.SignedOut, fixture(savedRefresh = null).bootstrap.bootstrap())
    }
}
