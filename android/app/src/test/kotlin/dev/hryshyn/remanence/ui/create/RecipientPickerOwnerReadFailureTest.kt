package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecipientPickerOwnerReadFailureTest {
    private val snapshot = ResolvedHandleSnapshot(
        userId = UserId.parseRest("1f0a1234-5678-4abc-9def-aabbccdd1001"),
        handle = NormalizedHandle.parse("mykola"),
        keyBundleId = KeyBundleId.parseRest("2f0a1234-5678-4abc-9def-aabbccdd2002"),
        suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocolVersion = 1,
        encryptionPublicKeysetB64Url = "CIenc",
        signingPublicKeysetB64Url = "CJsig",
        keyBundleStatus = "ACTIVE",
        directoryVersion = "9f1c0d2e",
    )

    private fun TestScope.scope() =
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    private fun TestScope.verifyRetryAfterOwnerFailure(failingRead: Int, recent: Boolean) {
        var reads = 0
        var directoryCalls = 0
        var fail = true
        val vm = RecipientPickerViewModel(
            directory = { directoryCalls++; DirectoryLookupResult.Found(snapshot) },
            accessTokenProvider = { "admission-token" },
            scope = scope(),
            sessionOwnerProvider = {
                reads++
                if (fail && reads == failingRead) throw IOException("private database details")
                "owner-a"
            },
        )
        vm.onHandleChange("mykola")
        fun lookup() {
            if (recent) vm.lookupRecent(snapshot.userId.toRestString()) else vm.lookup()
        }
        lookup()
        assertEquals(RecipientLookupUiState.Failed("Unexpected error. Try again later."), vm.state.value)
        assertTrue(vm.canLookup)
        assertEquals(if (failingRead == 1) 0 else 1, directoryCalls)

        fail = false
        lookup()
        assertEquals(RecipientLookupUiState.Resolved(snapshot), vm.state.value)
        assertEquals(if (failingRead == 1) 1 else 2, directoryCalls)
    }

    @Test fun manualPreflightFailureIsRedactedRetryableAndMakesNoDirectoryCall() = runTest {
        verifyRetryAfterOwnerFailure(1, recent = false)
    }

    @Test fun manualPostflightFailureNeverPublishesUnverifiedRecipientAndCanRetry() = runTest {
        verifyRetryAfterOwnerFailure(2, recent = false)
    }

    @Test fun recentPreflightFailureIsRedactedRetryableAndMakesNoDirectoryCall() = runTest {
        verifyRetryAfterOwnerFailure(1, recent = true)
    }

    @Test fun recentPostflightFailureNeverPublishesUnverifiedRecipientAndCanRetry() = runTest {
        verifyRetryAfterOwnerFailure(2, recent = true)
    }

    private fun TestScope.verifyStaleFailure(failingRead: Int, boundary: Boolean) {
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        var directoryCalls = 0
        var epoch = 0L
        val vm = RecipientPickerViewModel(
            directory = { directoryCalls++; DirectoryLookupResult.Found(snapshot) },
            accessTokenProvider = { "admission-token" },
            scope = scope(),
            sessionOwnerProvider = {
                reads++
                if (reads == failingRead) {
                    // A storage implementation can finish after cancellation.
                    withContext(NonCancellable) {
                        gate.await()
                        throw IOException("private database details")
                    }
                }
                "owner-a"
            },
            sessionBoundaryEpoch = { epoch },
        )
        vm.onHandleChange("mykola")
        vm.lookup()
        assertEquals(RecipientLookupUiState.LookingUp, vm.state.value)
        if (boundary) epoch++ else vm.onHandleChange("another")
        gate.complete(Unit)
        assertEquals(RecipientLookupUiState.Idle, vm.state.value)
        assertEquals(if (boundary) "mykola" else "another", vm.handle.value)
        assertEquals(if (failingRead == 1) 0 else 1, directoryCalls)
    }

    @Test fun editedInputCannotBeOverwrittenByLatePreflightFailure() = runTest {
        verifyStaleFailure(1, boundary = false)
    }

    @Test fun editedInputCannotBeOverwrittenByLatePostflightFailure() = runTest {
        verifyStaleFailure(2, boundary = false)
    }

    @Test fun retiredEpochCannotBeOverwrittenByLatePreflightFailure() = runTest {
        verifyStaleFailure(1, boundary = true)
    }

    @Test fun retiredEpochCannotBeOverwrittenByLatePostflightFailure() = runTest {
        verifyStaleFailure(2, boundary = true)
    }

    @Test fun staleDirectoryCompletionDoesNotReadOwnerAgain() = runTest {
        val gate = CompletableDeferred<DirectoryLookupResult>()
        var reads = 0
        val vm = RecipientPickerViewModel(
            directory = { withContext(NonCancellable) { gate.await() } },
            accessTokenProvider = { "admission-token" },
            scope = scope(),
            sessionOwnerProvider = { reads++; "owner-a" },
        )
        vm.onHandleChange("mykola")
        vm.lookup()
        vm.onHandleChange("another")
        gate.complete(DirectoryLookupResult.Found(snapshot))
        assertEquals(1, reads)
        assertEquals(RecipientLookupUiState.Idle, vm.state.value)
    }

    private fun TestScope.verifyOwnerCancellation(cancellingRead: Int) {
        val gate = CompletableDeferred<Unit>()
        val lookupScope = scope()
        var reads = 0
        var directoryCalls = 0
        val vm = RecipientPickerViewModel(
            directory = { directoryCalls++; DirectoryLookupResult.Found(snapshot) },
            accessTokenProvider = { "admission-token" },
            scope = lookupScope,
            sessionOwnerProvider = {
                reads++
                if (reads == cancellingRead) {
                    gate.await()
                    throw CancellationException("owner read cancelled")
                }
                "owner-a"
            },
        )
        vm.onHandleChange("mykola")
        vm.lookup()
        val lookupJob = lookupScope.coroutineContext[Job]!!.children.single()
        gate.complete(Unit)
        assertTrue(lookupJob.isCancelled)
        assertFalse(vm.state.value is RecipientLookupUiState.Failed)
        assertFalse(vm.state.value is RecipientLookupUiState.Resolved)
        assertEquals(if (cancellingRead == 1) 0 else 1, directoryCalls)
        vm.reset()
        assertEquals(RecipientLookupUiState.Idle, vm.state.value)
    }

    @Test fun preflightCancellationIsNotConvertedIntoLookupFailure() = runTest {
        verifyOwnerCancellation(1)
    }

    @Test fun postflightCancellationIsNotConvertedIntoLookupFailure() = runTest {
        verifyOwnerCancellation(2)
    }
}
