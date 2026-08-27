package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.core.data.network.AuthResult
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.session.SessionTokenPort
import java.util.ArrayDeque
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * FIX-M1-007-07 ordering proof: owner snapshot, account-work cancellation,
 * best-effort server revocation, each local teardown step, and cancellation
 * preservation are all observable without using production low-level ports.
 */
class LogoutUseCaseTest {

    private val owner = UserId(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))

    private class Recorder {
        val events = mutableListOf<String>()
    }

    private enum class FailurePoint {
        WORK,
        CREDENTIALS,
        REFRESH_TOKEN,
        TEMP,
        ACCOUNT,
        GRANTS,
    }

    private fun useCase(
        recorder: Recorder,
        serverBehavior: suspend (String) -> AuthResult<Unit> = { AuthResult.Success(Unit, 204) },
        bearerAtCallTime: ArrayDeque<String> = ArrayDeque(listOf("pm_at_live")),
        ownerSnapshot: suspend () -> UserId = {
            recorder.events += "owner-snapshot"
            owner
        },
        workBehavior: suspend (UserId) -> Unit = { target ->
            assertEquals(owner, target)
            recorder.events += "work-cancelled"
        },
        credentialBehavior: () -> Unit = {
            recorder.events += "session-credentials-cleared"
        },
        refreshTokenBehavior: () -> Unit = {
            recorder.events += "tokens-cleared"
        },
        tempBehavior: suspend (UserId) -> Unit = { target ->
            assertEquals(owner, target)
            recorder.events += "temp-cleaned"
        },
        accountBehavior: suspend () -> Unit = {
            recorder.events += "account-row-cleared"
        },
        grantsBehavior: () -> Unit = {
            recorder.events += "grants-invalidated"
        },
    ) = LogoutUseCase(
        serverLogout = { accessToken ->
            recorder.events += "server-logout:$accessToken"
            serverBehavior(accessToken)
        },
        accessToken = { bearerAtCallTime.poll() },
        tokens = object : SessionTokenPort {
            override fun readToken(): String? = "sealed-refresh"
            override fun saveToken(refreshToken: String) = Unit
            override fun clearToken() = refreshTokenBehavior()
        },
        credentialSink = LogoutCredentialSink { credentialBehavior() },
        accounts = accountBehavior,
        grants = grantsBehavior,
        logoutOwnerSnapshot = LogoutOwnerSnapshotPort { ownerSnapshot() },
        tempStorageCleanup = LogoutTempCleanupPort { target -> tempBehavior(target) },
        workCancellation = LogoutWorkCancellationPort { target -> workBehavior(target) },
    )

    @Test
    fun logoutRunsEveryStepInTheDocumentedOrderExactlyOnce() = runBlocking {
        val recorder = Recorder()

        useCase(recorder).logout()

        assertEquals(
            listOf(
                "owner-snapshot",
                "work-cancelled",
                "server-logout:pm_at_live",
                "session-credentials-cleared",
                "tokens-cleared",
                "temp-cleaned",
                "account-row-cleared",
                "grants-invalidated",
            ),
            recorder.events,
        )
    }

    @Test
    fun unreachableServerNeverBlocksLocalTeardown() = runBlocking {
        val recorder = Recorder()

        useCase(recorder, serverBehavior = { throw java.io.IOException("airplane mode") }).logout()

        assertEquals("server-logout:pm_at_live", recorder.events[2])
        assertTrue("session-credentials-cleared" in recorder.events)
        assertTrue("account-row-cleared" in recorder.events)
        assertTrue("grants-invalidated" in recorder.events)
    }

    @Test
    fun noLiveBearerStillCompletesLocalCleanupInOrder() = runBlocking {
        val recorder = Recorder()

        useCase(recorder, bearerAtCallTime = ArrayDeque(emptyList())).logout()

        assertEquals(
            listOf(
                "owner-snapshot",
                "work-cancelled",
                "session-credentials-cleared",
                "tokens-cleared",
                "temp-cleaned",
                "account-row-cleared",
                "grants-invalidated",
            ),
            recorder.events,
        )
    }

    @Test
    fun operationalFailureInEachLocalStepDoesNotBlockLaterSteps() = runBlocking {
        FailurePoint.values().forEach { failurePoint ->
            val recorder = Recorder()
            val boom = IllegalStateException(failurePoint.name)
            val outcome = useCase(
                recorder,
                workBehavior = { target ->
                    recorder.events += "work-cancelled"
                    if (failurePoint == FailurePoint.WORK) throw boom
                    assertEquals(owner, target)
                },
                credentialBehavior = {
                    recorder.events += "session-credentials-cleared"
                    if (failurePoint == FailurePoint.CREDENTIALS) throw boom
                },
                refreshTokenBehavior = {
                    recorder.events += "tokens-cleared"
                    if (failurePoint == FailurePoint.REFRESH_TOKEN) throw boom
                },
                tempBehavior = { target ->
                    recorder.events += "temp-cleaned"
                    if (failurePoint == FailurePoint.TEMP) throw boom
                    assertEquals(owner, target)
                },
                accountBehavior = {
                    recorder.events += "account-row-cleared"
                    if (failurePoint == FailurePoint.ACCOUNT) throw boom
                },
                grantsBehavior = {
                    recorder.events += "grants-invalidated"
                    if (failurePoint == FailurePoint.GRANTS) throw boom
                },
            ).logout()

            val expectedFailure = when (failurePoint) {
                FailurePoint.WORK -> outcome.workCancellationFailure
                FailurePoint.CREDENTIALS -> outcome.credentialSinkClearFailure
                FailurePoint.REFRESH_TOKEN -> outcome.refreshTokenClearFailure
                FailurePoint.TEMP -> outcome.tempStorageCleanupFailure
                FailurePoint.ACCOUNT -> outcome.localAccountClearFailure
                FailurePoint.GRANTS -> outcome.scanGrantInvalidationFailure
            }
            assertEquals(failurePoint.name, boom, expectedFailure)
            assertEquals(1, recorder.events.count { it == "session-credentials-cleared" })
            assertEquals(1, recorder.events.count { it == "tokens-cleared" })
            assertEquals(1, recorder.events.count { it == "account-row-cleared" })
            assertEquals(1, recorder.events.count { it == "grants-invalidated" })
        }
    }

    @Test
    fun fatalErrorPropagatesInsteadOfBeingRecorded() {
        val recorder = Recorder()
        val fatal = AssertionError("fatal")
        var propagated: AssertionError? = null

        try {
            runBlocking {
                useCase(
                    recorder,
                    workBehavior = { throw fatal },
                ).logout()
            }
            fail("expected fatal error")
        } catch (expected: AssertionError) {
            propagated = expected
        }
        assertTrue(propagated === fatal || propagated?.cause === fatal)
        assertEquals(listOf("owner-snapshot"), recorder.events)
    }

    @Test
    fun cancellationFromOwnerSnapshotRunsUnscopedTeardownAndIsRethrown() {
        val recorder = Recorder()
        val cancellation = CancellationException("snapshot cancelled")

        assertRethrowsCancellation {
            useCase(
                recorder,
                ownerSnapshot = { throw cancellation },
            ).logout()
        }

        assertEquals(
            listOf(
                "server-logout:pm_at_live",
                "session-credentials-cleared",
                "tokens-cleared",
                "account-row-cleared",
                "grants-invalidated",
            ),
            recorder.events,
        )
    }

    @Test
    fun cancellationFromServerRunsEveryLocalStepAndIsRethrown() {
        val recorder = Recorder()
        val cancellation = CancellationException("server cancelled")

        assertRethrowsCancellation {
            useCase(recorder, serverBehavior = { throw cancellation }).logout()
        }

        assertEquals(
            listOf(
                "owner-snapshot",
                "work-cancelled",
                "server-logout:pm_at_live",
                "session-credentials-cleared",
                "tokens-cleared",
                "temp-cleaned",
                "account-row-cleared",
                "grants-invalidated",
            ),
            recorder.events,
        )
    }

    private fun assertRethrowsCancellation(block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("expected cancellation")
        } catch (expected: CancellationException) {
            assertNotNull(expected)
        }
    }
}
