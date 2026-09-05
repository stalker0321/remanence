package dev.hryshyn.remanence.session

import dev.hryshyn.remanence.core.crypto.SessionRefreshRecord
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.data.network.AuthTokenHolder
import dev.hryshyn.remanence.sync.runWithRestoredSession
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
