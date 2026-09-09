package dev.hryshyn.remanence.session

import dev.hryshyn.remanence.core.crypto.SessionRefreshRecord
import dev.hryshyn.remanence.core.data.network.ApiBaseUrl
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.data.network.AuthTokenHolder
import dev.hryshyn.remanence.core.data.network.CoordinatedRefreshOutcome
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.ProductionApiStack
import dev.hryshyn.remanence.core.data.network.RefreshTokenReader
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.data.network.SessionRotationSink
import dev.hryshyn.remanence.sync.IncomingCapsuleSyncWorker
import dev.hryshyn.remanence.sync.runWithRestoredSession
import com.sun.net.httpserver.HttpServer
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionOwnerCoordinatorTest {

    private companion object {
        const val OWNER_TEXT = "0198f0a0-0000-7000-8000-00000000d501"
        const val BUNDLE = "00000000-0000-4000-8000-000000000501"
        val OWNER = UserId.parseRest(OWNER_TEXT)
    }

    private class PersistedTokenPort(initial: SessionRefreshRecord?) : SessionTokenPort {
        var record: SessionRefreshRecord? = initial

        override fun readToken(): String? = record?.refreshToken

        override fun readRecord(): SessionRefreshRecord? = record

        override fun saveToken(refreshToken: String) {
            val current = record ?: error("refresh record unexpectedly absent")
            record = SessionRefreshRecord(current.ownerUserId, refreshToken)
        }

        override fun saveRecord(record: SessionRefreshRecord) {
            this.record = record
        }

        override fun clearToken() {
            record = null
        }
    }

    private fun coordinatorFor(
        state: SessionState,
        liveOwner: UserId? = OWNER,
        accessToken: String? = "access-token",
    ): SessionOwnerCoordinator = SessionOwnerCoordinator(
        sessionResolver = object : SessionStateResolver {
            override suspend fun bootstrap(): SessionState = state

            override suspend fun logout(): SessionState = SessionState.SignedOut
        },
        currentOwner = { liveOwner },
        liveAccessToken = { accessToken },
    )

    @Test
    fun processDeathRestoresPersistedSessionBeforeWorkerOperationWithoutRootViewModel() = runBlocking {
        val tokens = PersistedTokenPort(SessionRefreshRecord(OWNER, "persisted-refresh"))
        val holder = AuthTokenHolder()
        val refreshCalls = AtomicInteger()
        val refresher = object : SessionRefresher {
            override suspend fun hasStoredToken(): Boolean = tokens.readRecord() != null

            override suspend fun refresh(expectedOwner: UserId): SessionRefreshOutcome {
                assertEquals(OWNER, expectedOwner)
                assertNull("process-death holder must start empty", holder.accessToken)
                refreshCalls.incrementAndGet()
                tokens.saveToken("rotated-refresh")
                holder.updateTokens("restored-access", "rotated-refresh")
                return SessionRefreshOutcome.Rotated("restored-access", "rotated-refresh")
            }
        }
        val bootstrap = SessionBootstrap(
            tokens = tokens,
            identity = object : IdentityAvailabilityPort {
                override fun hasIdentityFor(activeKeyBundleId: String): Boolean =
                    activeKeyBundleId == BUNDLE
            },
            account = {
                PersistedAccountSummary(OWNER_TEXT, "mykola", BUNDLE)
            },
            refresher = refresher,
        )
        val coordinator = SessionOwnerCoordinator(
            sessionResolver = bootstrap,
            currentOwner = { tokens.readRecord()?.ownerUserId },
            liveAccessToken = { holder.accessToken },
        )
        var workerOperationCalls = 0

        val result = runWithRestoredSession(OWNER, coordinator) {
            workerOperationCalls += 1
            ListenableWorker.Result.success()
        }

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, refreshCalls.get())
        assertEquals(1, workerOperationCalls)
        assertEquals("rotated-refresh", tokens.readToken())
        assertEquals("restored-access", holder.accessToken)
        // No Activity or RootViewModel is constructed by this worker path.
    }

    @Test
    fun transientOfflineSessionRetriesWithoutRunningWorkerOperation() = runBlocking {
        var operationCalls = 0
        val result = runWithRestoredSession(
            OWNER,
            coordinatorFor(
                SessionState.OfflineActive(OWNER_TEXT, "mykola", true, true, BUNDLE),
                accessToken = null,
            ),
        ) {
            operationCalls += 1
            ListenableWorker.Result.success()
        }

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(0, operationCalls)
    }

    @Test
    fun logoutAndOwnerChangeAreTerminalWithoutRunningWorkerOperation() = runBlocking {
        val cases = listOf(
            SessionOwnerResolution.NoOwner to coordinatorFor(SessionState.SignedOut, liveOwner = null),
            SessionOwnerResolution.AccountChanged to coordinatorFor(
                SessionState.Active(OWNER_TEXT, "mykola", true, true, BUNDLE),
                liveOwner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000d502"),
            ),
            SessionOwnerResolution.Rejected to coordinatorFor(SessionState.SignedOut),
            SessionOwnerResolution.RecoveryRequired to coordinatorFor(SessionState.RecoveryRequired),
        )

        for ((expected, coordinator) in cases) {
            assertEquals(expected, coordinator.ensure(OWNER))
        }
    }

    @Test
    fun activeWithoutRestoredBearerRemainsRetryable() = runBlocking {
        assertEquals(
            SessionOwnerResolution.Retryable,
            coordinatorFor(
                SessionState.Active(OWNER_TEXT, "mykola", true, true, BUNDLE),
                accessToken = null,
            ).ensure(OWNER),
        )
    }

    @Test
    fun finalAdmissionRejectsOwnerReplacementAfterOwnerCheckBeforeReady() = runBlocking {
        var liveOwner: UserId? = OWNER
        var liveToken: String? = "a1-access"
        val a1Lease = SessionRequestLease(OWNER, 41L)
        val coordinator = SessionOwnerCoordinator(
            sessionResolver = object : SessionStateResolver {
                override suspend fun bootstrap(): SessionState =
                    SessionState.Active(OWNER_TEXT, "mykola", true, true, BUNDLE, a1Lease)

                override suspend fun logout(): SessionState = SessionState.SignedOut
            },
            currentOwner = { liveOwner },
            liveAccessToken = { liveToken },
            finalAdmission = { expectedOwner, lease ->
                expectedOwner == liveOwner && lease == a1Lease && liveToken == "a1-access"
            },
        )
        coordinator.onBeforeFinalAdmission = {
            liveOwner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000d502")
            liveToken = "b-access"
        }

        assertEquals(SessionOwnerResolution.Retryable, coordinator.ensure(OWNER))
    }

    @Test
    fun finalAdmissionRejectsSameOwnerReincarnationAfterOwnerCheckBeforeReady() = runBlocking {
        var liveToken: String? = "a1-access"
        val a1Lease = SessionRequestLease(OWNER, 42L)
        val a2Lease = SessionRequestLease(OWNER, 43L)
        val coordinator = SessionOwnerCoordinator(
            sessionResolver = object : SessionStateResolver {
                override suspend fun bootstrap(): SessionState =
                    SessionState.Active(OWNER_TEXT, "mykola", true, true, BUNDLE, a1Lease)

                override suspend fun logout(): SessionState = SessionState.SignedOut
            },
            currentOwner = { OWNER },
            liveAccessToken = { liveToken },
            finalAdmission = { expectedOwner, lease ->
                expectedOwner == OWNER && lease == a1Lease && liveToken == "a1-access"
            },
        )
        coordinator.onBeforeFinalAdmission = {
            liveToken = "a2-access"
            assertTrue(a2Lease != a1Lease)
        }

        assertEquals(SessionOwnerResolution.Retryable, coordinator.ensure(OWNER))
    }

    @Test
    fun logoutBetweenBootstrapAndWorkerAdmissionStopsTheOperation() = runBlocking {
        var ownerReads = 0
        var operationCalls = 0
        val coordinator = SessionOwnerCoordinator(
            sessionResolver = object : SessionStateResolver {
                override suspend fun bootstrap(): SessionState =
                    SessionState.Active(OWNER_TEXT, "mykola", true, true, BUNDLE)

                override suspend fun logout(): SessionState = SessionState.SignedOut
            },
            currentOwner = {
                ownerReads += 1
                if (ownerReads == 1) OWNER else null
            },
            liveAccessToken = { "access-token" },
        )

        val result = runWithRestoredSession(OWNER, coordinator) {
            operationCalls += 1
            ListenableWorker.Result.success()
        }

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(0, operationCalls)
    }

    @Test
    fun durableOwnerOnlyAdmissionRestoresEmptyCoordinatorAndUsesFreshSameOwnerLease() = runBlocking {
        val authorization = CopyOnWriteArrayList<String?>()
        val refreshBodies = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val refreshResponse: (String, String) -> ByteArray = { access, refresh ->
            """
                {
                  "session_id": "0198f0a0-0000-7000-8000-00000000d511",
                  "access_token": "$access",
                  "access_expires_at": "2026-08-23T04:15:00Z",
                  "refresh_token": "$refresh",
                  "refresh_expires_at": "2026-09-22T04:00:00Z"
                }
            """.trimIndent().toByteArray()
        }
        server.createContext("/v1/auth/refresh") { exchange ->
            val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            refreshBodies += body
            val response = when {
                body.contains("a1-refresh") -> refreshResponse("a1-access-refreshed", "a1-refresh-rotated")
                body.contains("a2-refresh") -> refreshResponse("a2-access", "a2-refresh-rotated")
                else -> error("unexpected refresh lineage")
            }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        val response = """
            {
              "user":{"user_id":"$OWNER_TEXT","handle":"mykola"},
              "key_bundle":{
                "key_bundle_id":"$BUNDLE",
                "user_id":"$OWNER_TEXT",
                "suite":"HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
                "protocol_version":1,
                "encryption_public_keyset":"ZW5jcnlwdGlvbg",
                "signing_public_keyset":"c2lnbmluZw",
                "status":"ACTIVE",
                "created_at":"2026-08-30T03:00:00Z"
              },
              "directory_version":"v1"
            }
        """.trimIndent().toByteArray()
        server.createContext("/v1/directory/handles/mykola") { exchange ->
            authorization += exchange.requestHeaders.getFirst("Authorization")
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val durableRequest = IncomingCapsuleSyncWorker.request(OWNER)
            assertEquals(
                setOf(IncomingCapsuleSyncWorker.INPUT_OWNER_USER_ID),
                durableRequest.workSpec.input.keyValueMap.keys,
            )
            assertEquals(
                OWNER_TEXT,
                durableRequest.workSpec.input.getString(IncomingCapsuleSyncWorker.INPUT_OWNER_USER_ID),
            )

            fun createProductionAdmission(
                tokenPort: PersistedTokenPort,
                holder: AuthTokenHolder,
                admissionCalls: AtomicInteger,
            ): Pair<ProductionApiStack, SessionOwnerCoordinator> {
                lateinit var stack: ProductionApiStack
                stack = ProductionApiStack.create(
                    baseUrl = ApiBaseUrl.parse("http://127.0.0.1:${server.address.port}/"),
                    tokens = holder,
                    refreshTokenReader = RefreshTokenReader {
                        tokenPort.readRecord()?.let { record ->
                            dev.hryshyn.remanence.core.data.network.BoundRefreshCredential(
                                record.ownerUserId,
                                record.refreshToken,
                            )
                        }
                    },
                    rotationSink = object : SessionRotationSink {
                        override fun rotate(accessToken: String, refreshToken: String, ownerUserId: UserId) {
                            tokenPort.saveToken(refreshToken)
                            holder.updateTokens(accessToken, refreshToken)
                        }

                        override fun clear() {
                            tokenPort.clearToken()
                            holder.clearSession()
                        }
                    },
                )
                val bootstrap = SessionBootstrap(
                    tokens = tokenPort,
                    identity = object : IdentityAvailabilityPort {
                        override fun hasIdentityFor(activeKeyBundleId: String): Boolean = activeKeyBundleId == BUNDLE
                    },
                    account = { PersistedAccountSummary(OWNER_TEXT, "mykola", BUNDLE) },
                    refresher = object : SessionRefresher {
                        override suspend fun hasStoredToken(): Boolean = stack.sessionRefreshCoordinator.hasStoredToken()

                        override suspend fun refresh(expectedOwner: UserId): SessionRefreshOutcome =
                            when (val outcome = stack.sessionRefreshCoordinator.refreshForBootstrap(expectedOwner)) {
                                is CoordinatedRefreshOutcome.Rotated ->
                                    SessionRefreshOutcome.Rotated(
                                        accessToken = outcome.accessToken,
                                        refreshToken = outcome.refreshToken,
                                        lease = outcome.lease,
                                    )
                                is CoordinatedRefreshOutcome.Reused -> SessionRefreshOutcome.Reused(
                                    accessToken = outcome.accessToken,
                                    lease = outcome.lease,
                                )
                                CoordinatedRefreshOutcome.NoToken -> SessionRefreshOutcome.NoToken
                                CoordinatedRefreshOutcome.Rejected -> SessionRefreshOutcome.Rejected
                                CoordinatedRefreshOutcome.Unreachable -> SessionRefreshOutcome.Unreachable
                                CoordinatedRefreshOutcome.Unavailable -> SessionRefreshOutcome.Unavailable
                                CoordinatedRefreshOutcome.Invalidated -> SessionRefreshOutcome.Invalidated
                            }
                    },
                )
                return stack to SessionOwnerCoordinator(
                    sessionResolver = bootstrap,
                    currentOwner = { tokenPort.readRecord()?.ownerUserId },
                    liveAccessToken = { holder.accessToken },
                    finalAdmission = { owner, lease ->
                        admissionCalls.incrementAndGet()
                        stack.admitOwnerLease(owner, lease)
                    },
                )
            }

            // A process-death worker starts with no in-memory bearer or
            // installed owner. Its owner-only WorkManager input reaches the
            // actual SessionBootstrap -> coordinator admission path.
            val a1TokenPort = PersistedTokenPort(SessionRefreshRecord(OWNER, "a1-refresh"))
            val a1Holder = AuthTokenHolder()
            assertNull(a1Holder.accessToken)
            val a1AdmissionCalls = AtomicInteger()
            val (a1Stack, a1OwnerCoordinator) = createProductionAdmission(
                tokenPort = a1TokenPort,
                holder = a1Holder,
                admissionCalls = a1AdmissionCalls,
            )
            val a1Worker = TestListenableWorkerBuilder.from(
                ApplicationProvider.getApplicationContext(),
                IncomingCapsuleSyncWorker::class.java,
            ).setInputData(durableRequest.workSpec.input).build()
            a1Worker.installTestRuntime(a1OwnerCoordinator) {
                assertTrue(a1Stack.directoryRepository.lookup("mykola") is DirectoryLookupResult.Found)
                ListenableWorker.Result.success()
            }
            val a1Result = a1Worker.doWork()
            assertEquals(ListenableWorker.Result.success(), a1Result)
            assertEquals("a1-access-refreshed", a1Holder.accessToken)
            assertEquals(1, a1AdmissionCalls.get())

            // Logout retires the old coordinator. A later process starts with
            // only the same owner's new durable refresh lineage; bootstrap
            // must publish a fresh in-memory lease before the protected call.
            a1Stack.sessionRefreshCoordinator.invalidate()
            a1TokenPort.clearToken()
            val a2Holder = AuthTokenHolder()
            assertNull(a2Holder.accessToken)
            val a2AdmissionCalls = AtomicInteger()
            val a2TokenPort = PersistedTokenPort(SessionRefreshRecord(OWNER, "a2-refresh"))
            val (a2Stack, a2OwnerCoordinator) = createProductionAdmission(
                tokenPort = a2TokenPort,
                holder = a2Holder,
                admissionCalls = a2AdmissionCalls,
            )
            val a2Worker = TestListenableWorkerBuilder.from(
                ApplicationProvider.getApplicationContext(),
                IncomingCapsuleSyncWorker::class.java,
            ).setInputData(durableRequest.workSpec.input).build()
            a2Worker.installTestRuntime(a2OwnerCoordinator) {
                assertTrue(a2Stack.directoryRepository.lookup("mykola") is DirectoryLookupResult.Found)
                ListenableWorker.Result.success()
            }
            val a2Result = a2Worker.doWork()

            assertEquals(ListenableWorker.Result.success(), a2Result)
            assertEquals("a2-access", a2Holder.accessToken)
            assertEquals(1, a2AdmissionCalls.get())
            assertEquals(
                listOf("Bearer a1-access-refreshed", "Bearer a2-access"),
                authorization.toList(),
            )
            assertEquals(2, refreshBodies.size)
            assertTrue(refreshBodies[0].contains("a1-refresh"))
            assertTrue(refreshBodies[1].contains("a2-refresh"))
        } finally {
            server.stop(0)
        }
    }
}
