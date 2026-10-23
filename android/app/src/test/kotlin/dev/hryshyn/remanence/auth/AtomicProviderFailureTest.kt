package dev.hryshyn.remanence.auth

import androidx.credentials.exceptions.*
import dev.hryshyn.remanence.core.data.network.AtomicAction
import dev.hryshyn.remanence.recovery.RecoveryCredentialPort
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

/** Real controller and existing provider interfaces; synthetic exceptions, NOT native reproduction. */
class AtomicProviderFailureTest {
    private val privateText = "synthetic private provider detail; Cancelled; UNREGISTERED_ON_API_CONSOLE; 50152"
    private fun getFailures() = listOf(
        GetCredentialCancellationException(privateText) to "PROVIDER_CANCELLED",
        NoCredentialException(privateText) to "PROVIDER_NO_CREDENTIAL",
        GetCredentialProviderConfigurationException(privateText) to "PROVIDER_NOT_CONFIGURED",
        GetCredentialUnsupportedException(privateText) to "PROVIDER_UNAVAILABLE",
        GetCredentialInterruptedException(privateText) to "PROVIDER_FAILED",
        GetCredentialUnknownException(privateText) to "PROVIDER_FAILED",
        GetCredentialCustomException("synthetic.future.get", privateText) to "PROVIDER_FAILED",
    )
    private fun createFailures() = listOf(
        CreateCredentialCancellationException(privateText) to "PROVIDER_CANCELLED",
        CreateCredentialNoCreateOptionException(privateText) to "PROVIDER_NOT_CONFIGURED",
        CreateCredentialProviderConfigurationException(privateText) to "PROVIDER_NOT_CONFIGURED",
        CreateCredentialUnsupportedException(privateText) to "PROVIDER_UNAVAILABLE",
        CreateCredentialInterruptedException(privateText) to "PROVIDER_FAILED",
        CreateCredentialUnknownException(privateText) to "PROVIDER_FAILED",
        CreateCredentialCustomException("synthetic.future.create", privateText) to "PROVIDER_FAILED",
    )

    @Test fun googleFailureStopsBeforeVerifyAndNeverClaimsUserCancelForOtherSubtypes(): Unit = runBlocking {
        for ((failure, reason) in getFailures()) {
            val f = AtomicRegistrationControllerTest.Fixture(); val c = f.controller(); var calls = 0
            c.begin(GoogleCredentialPort { _, _ -> calls++; throw failure })
            assertEquals(reason, c.state.value.error); assertEquals(AtomicPhase.IDLE, c.state.value.phase)
            assertFalse(c.state.value.busy); assertFalse(c.state.value.sessionEstablished)
            assertEquals(1, calls); assertEquals(listOf(AtomicAction.GOOGLE_OPTIONS), f.requests.map { it.first })
            assertEquals(0, f.generations); assertNull(f.published); assertNull(f.installed)
        }
    }

    @Test fun createFailureRetainsHandlePhaseWithoutVerifyGenerateFinalizeOrAutomaticRetry(): Unit = runBlocking {
        for ((failure, reason) in createFailures()) {
            val f = AtomicRegistrationControllerTest.Fixture(); val c = f.controller(); var calls = 0
            c.begin(f.google)
            c.save("synthetic_handle", object : RecoveryCredentialPort by f {
                override suspend fun create(options: JsonObject): JsonObject { calls++; throw failure }
            })
            assertEquals(reason, c.state.value.error)
            assertEquals(if (failure is CreateCredentialCancellationException) AtomicPhase.CREATE_REQUIRED else AtomicPhase.CREDENTIAL_UNKNOWN, c.state.value.phase)
            assertFalse(c.state.value.busy); assertFalse(c.state.value.sessionEstablished)
            assertEquals(1, calls); assertEquals(0, f.generations); assertNull(f.published); assertNull(f.installed)
            assertFalse(f.requests.any { it.first in setOf(AtomicAction.CREDENTIAL_VERIFY, AtomicAction.SETUP_OPTIONS, AtomicAction.FINALIZE) })
        }
    }

    @Test fun existingCredentialGetFailureHasNoAutomaticCreateOrCredentialSubstitution(): Unit = runBlocking {
        for ((failure, reason) in getFailures()) {
            val f = AtomicRegistrationControllerTest.Fixture(); f.registered = true
            val c = f.controller(); var calls = 0; c.begin(f.google)
            c.chooseHandle("synthetic_handle")
            c.verify("synthetic_handle", object : RecoveryCredentialPort by f {
                override suspend fun get(options: JsonObject): JsonObject { calls++; throw failure }
            })
            assertEquals(reason, c.state.value.error); assertEquals(AtomicPhase.VERIFY_REQUIRED, c.state.value.phase)
            assertEquals(1, calls); assertEquals(0, f.creates); assertEquals(0, f.generations)
            assertFalse(f.requests.any { it.first == AtomicAction.SETUP_VERIFY || it.first == AtomicAction.FINALIZE })
            assertNull(f.published); assertNull(f.installed)
        }
    }

    @Test fun freshCreationNeverAttemptsImmediateOrOrdinaryGet(): Unit = runBlocking {
        val f = AtomicRegistrationControllerTest.Fixture(); f.providerMode = "pending"; val c = f.controller()
        c.begin(f.google); c.save("synthetic_handle", f)
        assertNull(c.state.value.error); assertEquals(AtomicPhase.VERIFY_REQUIRED, c.state.value.phase)
        assertEquals(1, f.creates); assertEquals(0, f.immediateGets); assertEquals(0, f.discoveryGets)
        assertEquals(0, f.generations); assertNull(f.published)
    }

    @Test fun missingPrfIsNeitherProviderCancelNorCommittedAccount(): Unit = runBlocking {
        val f = AtomicRegistrationControllerTest.Fixture(); f.providerMode = "no-prf"; val c = f.controller()
        c.begin(f.google); c.save("synthetic_handle", f)
        c.verify("synthetic_handle", f)
        assertEquals("PRF_UNAVAILABLE", c.state.value.error); assertEquals(AtomicPhase.VERIFY_REQUIRED, c.state.value.phase)
        assertEquals(0, f.generations); assertNull(f.saved); assertNull(f.published); assertNull(f.installed)
    }

    @Test fun coroutineCancellationPropagatesByIdentityAndRetiresOwner(): Unit = runBlocking {
        val f = AtomicRegistrationControllerTest.Fixture(); val c = f.controller()
        val cancelled = CancellationException("synthetic lifecycle cancellation"); var caught: Exception? = null
        val request = launch {
            try { c.begin(GoogleCredentialPort { _, _ -> throw cancelled }) }
            catch (failure: CancellationException) { caught = failure; throw failure }
        }
        request.join()
        assertSame(cancelled, caught); assertTrue(request.isCancelled); assertEquals(AtomicView(), c.state.value)
        assertEquals(listOf(AtomicAction.GOOGLE_OPTIONS), f.requests.map { it.first })
        assertNull(f.installed); assertNull(f.published)
    }

    @Test fun lateOldProviderFailureCannotOverwriteNewReadyOwnerOrKeys(): Unit = runBlocking {
        withTimeout(10000) {
            val f = AtomicRegistrationControllerTest.Fixture(); val c = f.controller()
            val arrived = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            c.begin(f.google)
            val old = launch { c.save("old_handle", object : RecoveryCredentialPort by f {
                override suspend fun create(options: JsonObject): JsonObject {
                    withContext(NonCancellable) { arrived.complete(Unit); release.await(); throw CreateCredentialUnknownException(privateText) }
                }
            }) }
            arrived.await(); assertNotNull(c.leave()); c.begin(f.google); c.save("new_handle", f)
            c.verify("new_handle", f)
            val ready = c.state.value; val keys = checkNotNull(f.installed); val login = checkNotNull(f.published)
            assertEquals(AtomicPhase.READY, ready.phase)
            release.complete(Unit); old.join()
            assertEquals(ready, c.state.value); assertSame(keys, f.installed); assertSame(login, f.published)
            assertEquals(1, f.generations); assertEquals(1, f.finals.size); assertTrue(f.revoked.isEmpty())
        }
    }

    @Test fun lateFailureOnExternallyRetiredLeaseLeavesNoErrorOrPublication(): Unit = runBlocking {
        withTimeout(5000) {
            val f = AtomicRegistrationControllerTest.Fixture(); val c = f.controller()
            val arrived = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val old = launch { c.begin(GoogleCredentialPort { _, _ ->
                withContext(NonCancellable) { arrived.complete(Unit); release.await(); throw GetCredentialUnknownException(privateText) }
            }) }
            arrived.await(); f.epoch++; release.complete(Unit); old.join()
            assertEquals(AtomicView(), c.state.value); assertNull(f.published); assertNull(f.installed)
            assertEquals(0, f.generations); assertFalse(f.requests.any { it.first == AtomicAction.GOOGLE_VERIFY })
        }
    }
}
