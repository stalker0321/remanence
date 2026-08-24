package app.postmark.memory.auth

import app.postmark.memory.session.SessionTokenPort
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import postmark.core.data.network.AuthResult
import postmark.core.data.network.SessionRotationSink

/**
 * FIX-M1-007-07 ordering proof: server revocation first while the bearer is
 * live, then session credentials, local account row, and scan grants - and a
 * failing server call never blocks local completion.
 */
class LogoutUseCaseTest {

    private class Recorder {
        val events = mutableListOf<String>()
    }

    private class RecordingTokens(private val recorder: Recorder) : SessionTokenPort {
        override fun readToken(): String? = "sealed-refresh"
        override fun saveToken(refreshToken: String) = Unit
        override fun clearToken() {
            recorder.events += "tokens-cleared"
        }
    }

    private class RecordingSink(private val recorder: Recorder) : SessionRotationSink {
        override fun rotate(accessToken: String, refreshToken: String) = Unit
        override fun clear() {
            recorder.events += "session-credentials-cleared"
        }
    }

    private fun useCase(
        recorder: Recorder,
        serverBehavior: suspend (String) -> AuthResult<Unit> = { AuthResult.Success(Unit, 204) },
        bearerAtCallTime: ArrayDeque<String> = ArrayDeque(listOf("pm_at_live")),
    ) = LogoutUseCase(
        serverLogout = { accessToken ->
            recorder.events += "server-logout:$accessToken"
            serverBehavior(accessToken)
        },
        accessToken = { bearerAtCallTime.poll() },        tokens = RecordingTokens(recorder),
        credentialSink = RecordingSink(recorder),
        accounts = { recorder.events += "account-row-cleared" },
        grants = { recorder.events += "grants-invalidated" },
    )

    @Test
    fun logoutRunsServerThenSessionThenLocalThenGrants() = runBlocking {
        val recorder = Recorder()
        useCase(recorder).logout()

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
    fun unreachableServerNeverBlocksLocalTeardown() = runBlocking {
        val recorder = Recorder()

        val subject = useCase(recorder, serverBehavior = { throw java.io.IOException("airplane mode") })

        subject.logout()

        assertEquals("server-logout:pm_at_live", recorder.events.first())
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
                "session-credentials-cleared",
                "tokens-cleared",
                "account-row-cleared",
                "grants-invalidated",
            ),
            recorder.events,
        )
    }
}
