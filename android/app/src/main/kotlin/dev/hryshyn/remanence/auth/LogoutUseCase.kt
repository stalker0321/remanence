package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.session.SessionTokenPort
import dev.hryshyn.remanence.core.data.network.AuthResult
import dev.hryshyn.remanence.core.data.network.SessionRotationSink

/** Port over the server logout endpoint (protocol.md section 5). */
fun interface ServerLogoutPort {
    suspend fun logout(accessToken: String): AuthResult<Unit>
}

/** Port over the memory-only current access token. */
fun interface CurrentAccessTokenPort {
    fun get(): String?
}

/**
 * FIX-M1-007-07: real account teardown with the mandated ordering -
 * 1. attempt SERVER logout first while the bearer is still live (best-effort:
 *    an unreachable server never traps credentials on the device);
 * 2. clear SESSION material (memory-only access token plus sealed rotating
 *    refresh token) through the atomic rotation sink;
 * 3. clear LOCAL state: the `local_account` Room row;
 * 4. invalidate any SCAN GRANT held by the running session.
 * Wrapped identity keysets and ciphertext stay on disk for this same account,
 * exactly as docs/security.md section 9 requires.
 */
class LogoutUseCase(
    private val serverLogout: ServerLogoutPort,
    private val accessToken: CurrentAccessTokenPort,
    private val tokens: SessionTokenPort,
    private val credentialSink: SessionRotationSink,
    private val accounts: suspend () -> Unit,
    private val grants: () -> Unit,
) {

    /** Runs every step in order; local completion is unconditional. */
    suspend fun logout() {
        // 1. Server revocation first, while the access token still works.
        val bearer = accessToken.get()
        if (bearer != null) {
            try {
                serverLogout.logout(bearer)
            } catch (_: Exception) {
                // Best effort only: offline logout must remain possible.
            }
        }

        // 2. Session credentials: memory + sealed storage, atomically.
        credentialSink.clear()
        runCatching { tokens.clearToken() }

        // 3. Local account row.
        accounts()

        // 4. Any scan grant of the running session dies with the account.
        grants()
    }
}
