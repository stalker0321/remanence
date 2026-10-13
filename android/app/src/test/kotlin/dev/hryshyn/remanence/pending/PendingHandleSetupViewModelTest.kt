package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.core.data.network.HandleCommitFailure
import dev.hryshyn.remanence.core.data.network.HandleCommitPort
import dev.hryshyn.remanence.core.data.network.HandleCommitResult
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.session.PersistedAccountSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T186 M7a: R17 commit fencing — same UUID/keys, live-lease checks around
 * the await, owner-fenced Room update, generic failures. No Gradle/ADB.
 */
class PendingHandleSetupViewModelTest {

    private val owner = UserId.parseRest("11111111-2222-3333-4444-555555555555")
    private val lease = SessionRequestLease(owner, 1L)

    private data class Calls(
        var commits: Int = 0,
        var records: MutableList<Pair<String, String>> = mutableListOf(),
    )

    private fun handleless() = PersistedAccountSummary(owner.toRestString(), null, "bundle-1")

    @Test
    fun successUpdatesOnlyHandleColumn() = runTest {
        val calls = Calls()
        val viewModel = vm(this, handleless(), calls = calls)
        viewModel.onFieldChange("Taken")
        viewModel.save()
        // Normalized (lowercased) handle committed; UUID/keys/tokens
        // untouched — the ONLY write is the fenced handle update.
        assertEquals(
            PendingHandleSetupViewModel.SetupState.Committed("taken"),
            viewModel.state.value,
        )
        assertEquals(listOf(owner.toRestString() to "taken"), calls.records)
        assertEquals(1, calls.commits)
    }

    @Test
    fun deadLeaseAfterNetworkFailsWithoutWrite() = runTest {
        val calls = Calls()
        val viewModel = vm(this, handleless(), liveAfter = false, calls = calls)
        viewModel.onFieldChange("taken")
        viewModel.save()
        assertEquals(
            PendingHandleSetupViewModel.SetupState.Failed(
                PendingHandleSetupViewModel.PendingHandleSetupMessage.SESSION_CHANGED,
            ),
            viewModel.state.value,
        )
        assertEquals(1, calls.commits)
        assertTrue(calls.records.isEmpty())
    }

    @Test
    fun responseUserMismatchFailsWithoutWrite() = runTest {
        val calls = Calls()
        val viewModel = vm(
            this, handleless(), calls = calls,
            response = { handle -> HandleCommitResult.Committed("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", handle, 200) },
        )
        viewModel.onFieldChange("taken")
        viewModel.save()
        assertEquals(
            PendingHandleSetupViewModel.SetupState.Failed(
                PendingHandleSetupViewModel.PendingHandleSetupMessage.SESSION_CHANGED,
            ),
            viewModel.state.value,
        )
        assertTrue(calls.records.isEmpty())
    }

    @Test
    fun conflictIsGenericWithoutWrite() = runTest {
        val calls = Calls()
        val viewModel = vm(
            this, handleless(), calls = calls,
            response = { HandleCommitResult.Failure(HandleCommitFailure.HANDLE_IMMUTABLE, 409, false) },
        )
        viewModel.onFieldChange("taken")
        viewModel.save()
        assertEquals(
            PendingHandleSetupViewModel.SetupState.Failed(
                PendingHandleSetupViewModel.PendingHandleSetupMessage.CONFLICT,
            ),
            viewModel.state.value,
        )
        assertTrue(calls.records.isEmpty())
    }

    @Test
    fun blankHandleNeverCallsNetwork() = runTest {
        val calls = Calls()
        val viewModel = vm(this, handleless(), calls = calls)
        viewModel.onFieldChange("  ")
        viewModel.save()
        assertEquals(
            PendingHandleSetupViewModel.SetupState.Failed(
                PendingHandleSetupViewModel.PendingHandleSetupMessage.INVALID_HANDLE,
            ),
            viewModel.state.value,
        )
        assertEquals(0, calls.commits)
    }

    @Test
    fun identicalReplaySucceedsWithoutNetwork() = runTest {
        val calls = Calls()
        val bound = PersistedAccountSummary(owner.toRestString(), "taken", "bundle-1")
        val viewModel = vm(this, bound, calls = calls)
        viewModel.onFieldChange("taken")
        viewModel.save()
        assertEquals(
            PendingHandleSetupViewModel.SetupState.Committed("taken"),
            viewModel.state.value,
        )
        assertEquals(0, calls.commits)
    }

    @Test
    fun differentBoundHandleConflictsWithoutNetwork() = runTest {
        val calls = Calls()
        val bound = PersistedAccountSummary(owner.toRestString(), "taken", "bundle-1")
        val viewModel = vm(this, bound, calls = calls)
        viewModel.onFieldChange("other")
        viewModel.save()
        assertEquals(
            PendingHandleSetupViewModel.SetupState.Failed(
                PendingHandleSetupViewModel.PendingHandleSetupMessage.CONFLICT,
            ),
            viewModel.state.value,
        )
        assertEquals(0, calls.commits)
    }

    @Test
    fun missingAccountFailsWithoutNetwork() = runTest {
        val calls = Calls()
        val viewModel = vm(this, null, calls = calls)
        viewModel.onFieldChange("taken")
        viewModel.save()
        assertEquals(
            PendingHandleSetupViewModel.SetupState.Failed(
                PendingHandleSetupViewModel.PendingHandleSetupMessage.SESSION_CHANGED,
            ),
            viewModel.state.value,
        )
        assertEquals(0, calls.commits)
    }

    private fun vm(
        scope: CoroutineScope,
        account: PersistedAccountSummary?,
        liveAfter: Boolean = true,
        response: (String) -> HandleCommitResult = { handle ->
            HandleCommitResult.Committed(account!!.userId, handle, 200)
        },
        calls: Calls,
        token: String? = "token",
    ): PendingHandleSetupViewModel = PendingHandleSetupViewModel(
        r17 = HandleCommitPort { handle, _ ->
            calls.commits += 1
            response(handle)
        },
        loadAccount = { account },
        captureLease = { lease },
        tokenFor = { token },
        leaseLive = { liveAfter },
        recordHandle = { userId, handle -> calls.records += userId to handle },
        scope = scope,
    )
}
