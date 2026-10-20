package dev.hryshyn.remanence.ui.create

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.hryshyn.remanence.core.data.network.DirectoryFailure
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId

class RecipientPickerViewModelTest {

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

    private class FakeDirectory(
        private var result: DirectoryLookupResult,
    ) : RecipientDirectoryPort {
        val handles = mutableListOf<String>()

        override suspend fun lookup(rawHandle: String): DirectoryLookupResult {
            handles += rawHandle
            return result
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.buildVm(result: DirectoryLookupResult, token: String? = "pm_at_live"): Pair<RecipientPickerViewModel, FakeDirectory> {
        val fake = FakeDirectory(result)
        return RecipientPickerViewModel(fake, { token }, CoroutineScope(UnconfinedTestDispatcher(testScheduler))) to fake
    }

    @Test
    fun invalidHandleBlocksLookupWithoutNetworkCall() = runTest {
        val (vm, fake) = buildVm(DirectoryLookupResult.NotFound)
        vm.onHandleChange("ab")
        assertFalse(vm.canLookup)
        vm.lookup()
        assertEquals(0, fake.handles.size)
        assertTrue(vm.state.value is RecipientLookupUiState.Idle)
    }

    @Test
    fun missingAccessTokenFailsBeforeNetwork() = runTest {
        val (vm, fake) = buildVm(DirectoryLookupResult.NotFound, token = null)
        vm.onHandleChange("@mykola")
        assertTrue(vm.canLookup)
        vm.lookup()
        assertEquals(0, fake.handles.size)
        assertTrue(vm.state.value is RecipientLookupUiState.Failed)
    }

    @Test
    fun resolvedSnapshotIsHeldForConfirmation() = runTest {
        val (vm, fake) = buildVm(DirectoryLookupResult.Found(snapshot))
        vm.onHandleChange("@Mykola")
        vm.lookup()
        val state = vm.state.value
        assertTrue(state is RecipientLookupUiState.Resolved)
        assertEquals(snapshot.userId, (state as RecipientLookupUiState.Resolved).snapshot.userId)
        assertEquals(listOf("@Mykola"), fake.handles)
    }

    @Test
    fun unknownHandleSurfacesNotFound() = runTest {
        val (vm, _) = buildVm(DirectoryLookupResult.NotFound)
        vm.onHandleChange("nobody1")
        vm.lookup()
        assertEquals(RecipientLookupUiState.NotFound, vm.state.value)
    }

    @Test
    fun networkFailureShowsRedactedMessage() = runTest {
        val (vm, _) = buildVm(DirectoryLookupResult.Failure(DirectoryFailure.NETWORK))
        vm.onHandleChange("mykola")
        vm.lookup()
        val state = vm.state.value
        assertTrue(state is RecipientLookupUiState.Failed && "mykola" !in state.message)
    }

    @Test
    fun editingHandleAfterResolutionResetsToIdle() = runTest {
        val (vm, _) = buildVm(DirectoryLookupResult.Found(snapshot))
        vm.onHandleChange("mykola")
        vm.lookup()
        assertTrue(vm.state.value is RecipientLookupUiState.Resolved)
        vm.onHandleChange("mykol")
        assertEquals(RecipientLookupUiState.Idle, vm.state.value)
    }

    @Test
    fun accountReplacementWhileLookupIsSuspendedCannotPublishOldResult() = runTest {
        val result = CompletableDeferred<DirectoryLookupResult>()
        var owner: String? = "owner-a"
        val vm = RecipientPickerViewModel(
            directory = { _ -> result.await() },
            accessTokenProvider = { "admission-token" },
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            sessionOwnerProvider = { owner },
        )
        vm.onHandleChange("mykola")
        vm.lookup()

        owner = "owner-b"
        result.complete(DirectoryLookupResult.Found(snapshot))

        assertEquals(RecipientLookupUiState.Failed("Sign in first.", RecipientLookupUiState.Failure.SIGN_IN), vm.state.value)
    }

    @Test
    fun resetCancelsLookupAndLateNonCancellableCompletionCannotRepublish() = runTest {
        val result = CompletableDeferred<DirectoryLookupResult>()
        val vm = RecipientPickerViewModel(
            directory = { _ -> withContext(NonCancellable) { result.await() } },
            accessTokenProvider = { "admission-token" },
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            sessionOwnerProvider = { "owner-a" },
        )
        vm.onHandleChange("mykola")
        vm.lookup()

        vm.reset()
        result.complete(DirectoryLookupResult.Found(snapshot))

        assertEquals("", vm.handle.value)
        assertEquals(RecipientLookupUiState.Idle, vm.state.value)
    }

    @Test
    fun editingHandleCancelsLookupAndLateCompletionCannotRepublish() = runTest {
        val result = CompletableDeferred<DirectoryLookupResult>()
        val vm = RecipientPickerViewModel(
            directory = { _ -> withContext(NonCancellable) { result.await() } },
            accessTokenProvider = { "admission-token" },
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            sessionOwnerProvider = { "owner-a" },
        )
        vm.onHandleChange("mykola")
        vm.lookup()

        vm.onHandleChange("someoneelse")
        result.complete(DirectoryLookupResult.Found(snapshot))

        assertEquals("someoneelse", vm.handle.value)
        assertEquals(RecipientLookupUiState.Idle, vm.state.value)
    }

    @Test
    fun sessionBoundaryInvalidationCancelsSameOwnerLookupAndClearsState() = runTest {
        val result = CompletableDeferred<DirectoryLookupResult>()
        var epoch = 0L
        var listener: (() -> Unit)? = null
        val vm = RecipientPickerViewModel(
            directory = { _ -> withContext(NonCancellable) { result.await() } },
            accessTokenProvider = { "admission-token" },
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            sessionOwnerProvider = { "same-owner" },
            sessionBoundaryEpoch = { epoch },
            registerSessionBoundary = { callback ->
                listener = callback
                { listener = null }
            },
        )
        vm.onHandleChange("mykola")
        vm.lookup()

        epoch++
        listener?.invoke()
        result.complete(DirectoryLookupResult.Found(snapshot))

        assertEquals("", vm.handle.value)
        assertEquals(RecipientLookupUiState.Idle, vm.state.value)
    }

    @Test
    fun nullTokenRecoversThroughOneRefreshAndResolves() = runTest {
        var token: String? = null
        var refreshCalls = 0
        val fake = FakeDirectory(DirectoryLookupResult.Found(snapshot))
        val vm = RecipientPickerViewModel(
            fake,
            { token },
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            sessionRefresh = {
                refreshCalls++
                token = "pm_at_recovered"
                RecipientPickerViewModel.SessionRecovery.RECOVERED
            },
        )
        vm.onHandleChange("@mykola")
        vm.lookup()

        assertEquals(1, refreshCalls)
        assertEquals(listOf("@mykola"), fake.handles)
        val state = vm.state.value
        assertTrue(state is RecipientLookupUiState.Resolved)
        assertEquals(snapshot.userId, (state as RecipientLookupUiState.Resolved).snapshot.userId)
    }

    @Test
    fun nullTokenWithFailedRefreshReportsSignedOut() = runTest {
        var refreshCalls = 0
        val fake = FakeDirectory(DirectoryLookupResult.Found(snapshot))
        val vm = RecipientPickerViewModel(
            fake,
            { null },
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            sessionRefresh = {
                refreshCalls++
                RecipientPickerViewModel.SessionRecovery.NO_SESSION
            },
        )
        vm.onHandleChange("@mykola")
        vm.lookup()

        assertEquals(1, refreshCalls)
        assertEquals(0, fake.handles.size)
        assertEquals(RecipientLookupUiState.Failed("Sign in first.", RecipientLookupUiState.Failure.SIGN_IN), vm.state.value)
    }

    @Test
    fun nullTokenWithUnreachableRefreshReportsNetwork() = runTest {
        var refreshCalls = 0
        val fake = FakeDirectory(DirectoryLookupResult.Found(snapshot))
        val vm = RecipientPickerViewModel(
            fake,
            { null },
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            sessionRefresh = {
                refreshCalls++
                RecipientPickerViewModel.SessionRecovery.NETWORK_FAILED
            },
        )
        vm.onHandleChange("@mykola")
        vm.lookup()

        assertEquals(1, refreshCalls)
        assertEquals(0, fake.handles.size)
        assertEquals(
            RecipientLookupUiState.Failed("Network unreachable. Try again later.", RecipientLookupUiState.Failure.NETWORK),
            vm.state.value,
        )
    }

    @Test
    fun cancelDuringRefreshRecoveryCannotPublish() = runTest {
        val gate = CompletableDeferred<RecipientPickerViewModel.SessionRecovery>()
        var token: String? = null
        val fake = FakeDirectory(DirectoryLookupResult.Found(snapshot))
        val vm = RecipientPickerViewModel(
            fake,
            { token },
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            sessionRefresh = { gate.await() },
        )
        vm.onHandleChange("mykola")
        vm.lookup()

        vm.reset()
        token = "pm_at_late"
        gate.complete(RecipientPickerViewModel.SessionRecovery.RECOVERED)

        assertEquals("", vm.handle.value)
        assertEquals(RecipientLookupUiState.Idle, vm.state.value)
        assertEquals(0, fake.handles.size)
    }

    @Test
    fun ownerFlipDuringRefreshRecoveryResolvesFreshContext() = runTest {
        val gate = CompletableDeferred<RecipientPickerViewModel.SessionRecovery>()
        var token: String? = null
        var owner: String? = "owner-a"
        val fake = FakeDirectory(DirectoryLookupResult.Found(snapshot))
        val vm = RecipientPickerViewModel(
            fake,
            { token },
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            sessionOwnerProvider = { owner },
            sessionRefresh = { gate.await() },
        )
        vm.onHandleChange("mykola")
        vm.lookup()

        owner = "owner-b"
        token = "pm_at_b"
        gate.complete(RecipientPickerViewModel.SessionRecovery.RECOVERED)

        val state = vm.state.value
        assertTrue(state is RecipientLookupUiState.Resolved)
        assertEquals(listOf("mykola"), fake.handles)
    }

    @Test
    fun recoveryIsBusyAndRapidLookupDoesNotRestartIt() = runTest {
        val gate = CompletableDeferred<RecipientPickerViewModel.SessionRecovery>()
        var calls = 0
        val vm = RecipientPickerViewModel(
            { DirectoryLookupResult.NotFound }, { null },
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            sessionRefresh = { calls++; gate.await() },
        )
        vm.onHandleChange("mykola")
        vm.lookup()
        val busy = vm.state.value
        val allowed = vm.canLookup
        vm.lookup()
        gate.complete(RecipientPickerViewModel.SessionRecovery.NETWORK_FAILED)
        assertEquals(RecipientLookupUiState.LookingUp, busy)
        assertFalse(allowed)
        assertEquals(1, calls)
    }

    @Test
    fun invalidHandleDoesNotAttemptSessionRecovery() = runTest {
        var calls = 0
        val vm = RecipientPickerViewModel(
            { DirectoryLookupResult.NotFound }, { null },
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            sessionRefresh = { calls++; RecipientPickerViewModel.SessionRecovery.NO_SESSION },
        )
        vm.onHandleChange("ab")
        vm.lookup()
        assertEquals(0, calls)
        assertEquals(RecipientLookupUiState.Idle, vm.state.value)
    }

    @Test
    fun lateRecoveryFailureDoesNotReplaceEditedHandleState() = runTest {
        val gate = CompletableDeferred<RecipientPickerViewModel.SessionRecovery>()
        val vm = RecipientPickerViewModel(
            { DirectoryLookupResult.NotFound }, { null },
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            sessionRefresh = { withContext(NonCancellable) { gate.await() } },
        )
        vm.onHandleChange("mykola")
        vm.lookup()
        vm.onHandleChange("another")
        gate.complete(RecipientPickerViewModel.SessionRecovery.NETWORK_FAILED)
        assertEquals("another", vm.handle.value)
        assertEquals(RecipientLookupUiState.Idle, vm.state.value)
    }

    @Test
    fun recoveryAcrossRetiredSessionCannotStartDirectoryLookup() = runTest {
        val gate = CompletableDeferred<RecipientPickerViewModel.SessionRecovery>()
        var token: String? = null
        var epoch = 0L
        val fake = FakeDirectory(DirectoryLookupResult.Found(snapshot))
        val vm = RecipientPickerViewModel(
            fake, { token }, CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            sessionBoundaryEpoch = { epoch },
            sessionRefresh = { gate.await() },
        )
        vm.onHandleChange("mykola")
        vm.lookup()
        epoch++
        token = "pm_at_new_session"
        gate.complete(RecipientPickerViewModel.SessionRecovery.RECOVERED)
        assertTrue(fake.handles.isEmpty())
        assertFalse(vm.state.value is RecipientLookupUiState.Resolved)
    }
}
