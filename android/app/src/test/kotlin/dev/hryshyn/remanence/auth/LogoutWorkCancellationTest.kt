package dev.hryshyn.remanence.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.hryshyn.remanence.AppContainer
import dev.hryshyn.remanence.core.data.network.AuthResult
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.session.SessionTokenPort
import dev.hryshyn.remanence.sync.AccountWorkIdentity
import dev.hryshyn.remanence.sync.NoOpWorker
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class RecordingSink(private val trace: MutableList<String>? = null) : LogoutCredentialSink {
    override fun clear() {
        trace?.add("credentials")
    }
}

private class RecordingTokenPort(private val trace: MutableList<String>? = null) : SessionTokenPort {
    override fun readToken(): String? = null
    override fun saveToken(refreshToken: String) = Unit
    override fun clearToken() {
        trace?.add("tokens")
    }
}

/**
 * M2-P05 wiring proofs: [LogoutUseCase] cancels EXACTLY the snapshotted
 * account's WorkManager chains - before any server/network teardown and
 * before credentials or the local_account row clear; an owner change landing
 * mid-call can never redirect the request to another account; a missing owner
 * SKIPS cancellation instead of cancelling anything globally; and a failing
 * operational cancellation failure is observable on [LogoutOutcome] while
 * every remaining teardown step still completes; CancellationException is
 * rethrown after bounded cleanup. The final test proves the REAL AppContainer
 * wiring (WorkManager-backed) cancels only the logged-out account's chains.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LogoutWorkCancellationTest {

    private val ownerA = UserId(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
    private val ownerB = UserId(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))

    // ---------------------------------------------------------------
    // Use-case level (fake recording ports).
    // ---------------------------------------------------------------

    private fun useCase(
        trace: MutableList<String>,
        liveRow: AtomicReference<UserId?>,
        cancelled: MutableList<UserId>,
        cancellationBehavior: ((UserId) -> Unit)? = null,
    ): LogoutUseCase = LogoutUseCase(
        serverLogout = ServerLogoutPort {
            trace += "server"
            AuthResult.Success(Unit, 204)
        },
        accessToken = { "bearer" },
        tokens = RecordingTokenPort(trace),
        credentialSink = RecordingSink(trace),
        accounts = {
            trace += "accounts"
            liveRow.set(null)
        },
        grants = { trace += "grants" },
        logoutOwnerSnapshot = {
            val row = liveRow.get()
                ?: error("no authenticated local account")
            trace += "snapshot:${row.toRestString().take(13)}"
            row
        },
        tempStorageCleanup = { _ -> trace += "cleanup" },
        workCancellation = { owner ->
            trace += "cancel:${if (owner == ownerA) "A" else "B"}"
            cancelled += owner
            cancellationBehavior?.invoke(owner)
        },
    )

    @Test
    fun cancellationRunsAfterSnapshotAndBeforeServerCredentialsAccountsGrants() = runBlocking {
        val trace = mutableListOf<String>()
        val cancelled = mutableListOf<UserId>()

        val outcome = useCase(trace, AtomicReference(ownerA), cancelled).logout()

        assertNull(outcome.workCancellationFailure)
        assertEquals(
            listOf(
                "snapshot:${ownerA.toRestString().take(13)}",
                "cancel:A",
                "server",
                "credentials",
                "tokens",
                "cleanup",
                "accounts",
                "grants",
            ),
            trace,
        )
        assertEquals(listOf(ownerA), cancelled)
    }

    @Test
    fun ownerChangeMidCallCannotRedirectTheCancellationTarget() = runBlocking {
        val trace = mutableListOf<String>()
        val cancelled = mutableListOf<UserId>()
        // THE prove-the-discipline read: every snapshot-like re-read AFTER
        // cancellation has run returns B - the use case must still have asked
        // for exactly A.
        val liveRow = AtomicReference<UserId?>(ownerA)

        val outcome =
            useCase(
                trace,
                liveRow,
                cancelled,
                cancellationBehavior = { owner ->
                    if (owner == ownerA) {
                        // Login-as-B lands the instant cancellation runs.
                        liveRow.set(ownerB)
                    }
                },
            ).logout()

        assertNull(outcome.workCancellationFailure)
        // One request, one target: the immutable snapshot of A.
        assertEquals(listOf(ownerA), cancelled)
        assertEquals(1, trace.count { it == "cancel:A" })
        assertEquals(0, trace.count { it == "cancel:B" })
        // Storage cleanup used the same frozen snapshot afterwards.
        org.junit.Assert.assertTrue(
            "storage cleanup must run after the cancellation with the same frozen target",
            trace.indexOf("cleanup") > trace.indexOf("cancel:A"),
        )
    }

    @Test
    fun missingOwnerNeverInvokesTheCancellationPort() = runBlocking {
        val trace = mutableListOf<String>()
        val cancelled = mutableListOf<UserId>()

        val outcome = useCase(trace, AtomicReference(null as UserId?), cancelled).logout()

        assertNull(outcome.workCancellationFailure)
        assertEquals(emptyList<UserId>(), cancelled)
        // Teardown completes fully without any guess or global cancellation.
        assertEquals(listOf("server", "credentials", "tokens", "accounts", "grants"), trace)
    }

    @Test
    fun cancellationFailureIsObservableAndTeardownStillCompletes() = runBlocking {
        val trace = mutableListOf<String>()
        val boom = IllegalStateException("WorkManager wedged")

        val outcome = LogoutUseCase(
            serverLogout = ServerLogoutPort {
                trace += "server"
                AuthResult.Success(Unit, 204)
            },
            accessToken = { "bearer" },
            tokens = RecordingTokenPort(trace),
            credentialSink = RecordingSink(trace),
            accounts = { trace += "accounts" },
            grants = { trace += "grants" },
            logoutOwnerSnapshot = { ownerA },
            tempStorageCleanup = { _ -> trace += "cleanup" },
            workCancellation = { _ -> throw boom },
        ).logout()

        assertEquals(boom, outcome.workCancellationFailure)
        assertEquals(listOf("server", "credentials", "tokens", "cleanup", "accounts", "grants"), trace)
    }

    @Test
    fun simultaneousStorageAndCancellationFailuresAreBothRecorded() = runBlocking {
        val storageBoom = RuntimeException("storage")
        val workBoom = RuntimeException("work")

        val outcome = LogoutUseCase(
            serverLogout = ServerLogoutPort { AuthResult.Success(Unit, 204) },
            accessToken = { "bearer" },
            tokens = RecordingTokenPort(),
            credentialSink = RecordingSink(),
            accounts = {},
            grants = {},
            logoutOwnerSnapshot = { ownerA },
            tempStorageCleanup = { _ -> throw storageBoom },
            workCancellation = { _ -> throw workBoom },
        ).logout()

        assertEquals(storageBoom, outcome.tempStorageCleanupFailure)
        assertEquals(workBoom, outcome.workCancellationFailure)
    }

    /**
     * M2-P05 review-fix regression: teardown steps cannot START while the
     * account-work cancellation is still being awaited. The controllably
     * suspended port holds the awaited operation open; while it pends no
     * server/credential/cleanup/account/grant step has fired; only after the
     * gate releases does the whole remaining sequence run, in order.
     */
    @org.junit.Test
    fun teardownWaitsForTheAwaitedCancellationToFinishFirst() = runTest {
        val observed = mutableListOf<String>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()

        val useCase = LogoutUseCase(
            serverLogout = ServerLogoutPort {
                observed += "server"
                AuthResult.Success(Unit, 204)
            },
            accessToken = { "bearer" },
            tokens = RecordingTokenPort(observed),
            credentialSink = RecordingSink(observed),
            accounts = { observed += "accounts" },
            grants = { observed += "grants" },
            logoutOwnerSnapshot = { ownerA },
            tempStorageCleanup = { _ -> observed += "cleanup" },
            workCancellation = { owner ->
                org.junit.Assert.assertEquals(ownerA, owner)
                observed += "cancel-start"
                gate.await() // the awaited WorkManager-operation boundary
                observed += "cancel-end"
            },
        )

        var outcome: LogoutOutcome? = null
        val job = launch {
            outcome = useCase.logout()
        }

        // Drive virtual time: the port ran and now suspends awaiting the
        // WorkManager operation. No teardown step may have started.
        advanceUntilIdle()
        assertEquals(listOf("cancel-start"), observed)

        // The awaited cancellation completes...
        gate.complete(Unit)
        advanceUntilIdle()
        job.join()

        // ...and ONLY THEN does the rest of teardown run, in order.
        assertNull(outcome?.workCancellationFailure)
        org.junit.Assert.assertEquals(
            listOf(
                "cancel-start",
                "cancel-end",
                "server",
                "credentials",
                "tokens",
                "cleanup",
                "accounts",
                "grants",
            ),
            observed,
        )
    }

    @Test
    fun callerCancellationDuringWorkWaitsForCleanupThenIsRethrown() = runTest {
        val observed = mutableListOf<String>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var thrown: CancellationException? = null

        val subject = LogoutUseCase(
            serverLogout = ServerLogoutPort {
                observed += "server"
                AuthResult.Success(Unit, 204)
            },
            accessToken = { "bearer" },
            tokens = RecordingTokenPort(observed),
            credentialSink = RecordingSink(observed),
            accounts = { observed += "accounts" },
            grants = { observed += "grants" },
            logoutOwnerSnapshot = { ownerA },
            tempStorageCleanup = { _ -> observed += "cleanup" },
            workCancellation = { owner ->
                assertEquals(ownerA, owner)
                observed += "cancel-start"
                gate.await()
                observed += "cancel-end"
            },
        )

        val job = launch {
            try {
                subject.logout()
            } catch (cancelled: CancellationException) {
                thrown = cancelled
            }
        }

        advanceUntilIdle()
        assertEquals(listOf("cancel-start"), observed)
        job.cancel(CancellationException("caller cancelled"))
        gate.complete(Unit)
        advanceUntilIdle()
        job.join()

        assertNotNull(thrown)
        assertEquals(
            listOf(
                "cancel-start",
                "cancel-end",
                "server",
                "credentials",
                "tokens",
                "cleanup",
                "accounts",
                "grants",
            ),
            observed,
        )
    }

    @Test
    fun cancellationFromWorkRunsLaterLocalStepsAndIsRethrown() {
        val trace = mutableListOf<String>()
        val cancellation = CancellationException("work cancellation interrupted")

        try {
            runBlocking {
                useCase(
                    trace,
                    AtomicReference(ownerA),
                    mutableListOf(),
                    cancellationBehavior = { throw cancellation },
                ).logout()
            }
            org.junit.Assert.fail("expected cancellation")
        } catch (expected: CancellationException) {
            org.junit.Assert.assertEquals(cancellation, expected)
        }

        assertEquals(
            listOf(
                "snapshot:${ownerA.toRestString().take(13)}",
                "cancel:A",
                "server",
                "credentials",
                "tokens",
                "cleanup",
                "accounts",
                "grants",
            ),
            trace,
        )
    }

    // ---------------------------------------------------------------
    // Production wiring (real AppContainer over the work-testing stack).
    // ---------------------------------------------------------------

    private lateinit var context: Context

    @Before
    fun setUpWiring() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDownWiring() {
        File(context.filesDir, "accounts").deleteRecursively()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun enqueueFor(
        workManager: WorkManager,
        userId: UserId,
        capsuleId: CapsuleId,
    ): UUID {
        val builder = OneTimeWorkRequestBuilder<NoOpWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setInitialState(WorkInfo.State.ENQUEUED)
        for (tag in AccountWorkIdentity.outbox(userId, capsuleId).tags) builder.addTag(tag)
        val request = builder.build()
        workManager.enqueue(request)
        return request.id
    }

    private fun assertState(wm: WorkManager, id: UUID, expected: WorkInfo.State) {
        val info = wm.getWorkInfoById(id).get()
        org.junit.Assert.assertNotNull("work $id missing from WorkManager", info)
        assertEquals(expected, info!!.state)
    }

    @Test
    fun containerLogoutCancelsOnlyTheLoggedOutAccountChains() = runBlocking {
        // Official work-testing artifact provides the WorkManager instance
        // the container resolves at logout time.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build(),
        )

        val container = AppContainer(context, kekBoundaryOverride = SoftwareKekBoundary())
        container.database.openHelper.writableDatabase // force open

        val owner = "9db5c67a-3a4e-45d1-8b0f-2f14a9bb1001"
        container.currentAccountStore.record(
            userId = owner,
            handle = "mykola",
            activeKeyBundleId = "00000000-0000-4000-8000-000000000001",
        )

        // Background chains: two for the logging-out owner, one for another.
        val wm = WorkManager.getInstance(context)
        val aIncoming = enqueueFor(wm, UserId(UUID.fromString(owner)), CapsuleId(UUID.randomUUID()))
        val aOutbox = enqueueFor(wm, UserId(UUID.fromString(owner)), CapsuleId(UUID.randomUUID()))
        val bChain = enqueueFor(wm, ownerB, CapsuleId(UUID.randomUUID()))

        // Live session credentials drive the bearer header; the logout must
        // still complete when the configured server endpoint is unreachable.
        container.authTokenHolder.updateTokens("access-token", "refresh-token")

        val outcome = container.logoutUseCase.logout()

        assertNull(outcome.tempStorageCleanupFailure)
        assertNull(outcome.workCancellationFailure)

        // Only THIS account's chains are gone; the other account is untouched.
        assertState(wm, aIncoming, WorkInfo.State.CANCELLED)
        assertState(wm, aOutbox, WorkInfo.State.CANCELLED)
        assertState(wm, bChain, WorkInfo.State.ENQUEUED)

        // Local teardown finished: the local_account row was cleared.
        assertEquals(null, container.currentAccountStore.load())
        container.database.close()
    }
}
