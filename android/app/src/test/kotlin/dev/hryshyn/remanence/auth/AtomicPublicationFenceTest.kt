package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.core.data.network.*
import dev.hryshyn.remanence.core.model.UserId
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Real BoundSessionReplacement/coordinator. No API or provider response mock can publish for a retired UI. */
class AtomicPublicationFenceTest {
    private val a = UserId.parseRest("11111111-1111-4111-8111-111111111111")
    private val b = UserId.parseRest("22222222-2222-4222-8222-222222222222")
    private class State {
        val tokens = AuthTokenHolder()
        val stored = AtomicReference<BoundRefreshCredential?>(null)
        val account = AtomicReference<UserId?>(null)
        val stack = ProductionApiStack.create(ApiBaseUrl.parse("http://127.0.0.1:1/"), tokens,
            RefreshTokenReader { stored.get() }, object : SessionRotationSink {
                override fun rotate(accessToken: String, refreshToken: String, ownerUserId: UserId) {
                    stored.set(BoundRefreshCredential(ownerUserId, refreshToken)); tokens.updateTokens(accessToken, refreshToken)
                }
                override fun clear() { stored.set(null); tokens.clearSession() }
            })
        val replacement = BoundSessionReplacement(stack.sessionRefreshCoordinator) { account.get() }
    }

    @Test fun lateAtomicAttemptCannotCloseOrEraseNewLoginEvenWithoutLogoutEpochChange(): Unit = runBlocking {
        val s = State(); val lease = s.replacement.acquireLease()
        s.replacement.replace(lease, b, "pm_at_new", "pm_rt_new") { s.account.set(b) }
        var writes = 0
        val rejected = runCatching {
            s.replacement.replaceGuarded(lease, a, "pm_at_old", "pm_rt_old",
                { s.stack.sessionRefreshCoordinator.rawAccessToken() == null }, { it() }) { writes++; s.account.set(a) }
        }
        assertTrue(rejected.isFailure); assertEquals(0, writes)
        assertEquals(b, s.account.get()); assertEquals("pm_at_new", s.stack.sessionRefreshCoordinator.openDomainAccessToken())
        assertEquals(BoundRefreshCredential(b, "pm_rt_new"), s.stored.get())
    }

    @Test fun leaveDuringSuspendingAccountWriteCannotPublishReadySession(): Unit = runBlocking {
        withTimeout(5000) {
            val s = State(); var current = true
            val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val operation = async {
                runCatching { s.replacement.replaceGuarded(s.replacement.acquireLease(), a, "pm_at_old", "pm_rt_old",
                    { current }, { install -> check(current); install() }) {
                    reached.complete(Unit); release.await(); s.account.set(a)
                } }
            }
            reached.await(); current = false; release.complete(Unit)
            assertTrue(operation.await().isFailure)
            assertNull(s.tokens.accessToken); assertNull(s.stored.get())
            assertNull(s.stack.sessionRefreshCoordinator.openDomainAccessToken())
        }
    }

    @Test fun admittedOwnerPublishesThroughSameKernelAndLogoutRetiresIt(): Unit = runBlocking {
        val s = State(); var commits = 0; val lease = s.replacement.acquireLease()
        s.replacement.replaceGuarded(lease, a, "pm_at_a", "pm_rt_a", { true }, { commits++; it() }) { s.account.set(a) }
        assertEquals(1, commits); assertEquals("pm_at_a", s.stack.sessionRefreshCoordinator.openDomainAccessToken())
        s.stack.sessionRefreshCoordinator.invalidate()
        assertFalse(s.stack.sessionRefreshCoordinator.commitLocalRecovery(lease) { fail("retired local install") })
    }
}
