package dev.hryshyn.remanence.ui.create

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

        override suspend fun lookup(rawHandle: String, accessToken: String): DirectoryLookupResult {
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
}
