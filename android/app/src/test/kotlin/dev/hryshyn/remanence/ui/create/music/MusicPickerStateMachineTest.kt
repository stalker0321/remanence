package dev.hryshyn.remanence.ui.create.music

import dev.hryshyn.remanence.core.data.network.MusicSearchFailure
import dev.hryshyn.remanence.core.data.network.MusicSearchPage
import dev.hryshyn.remanence.core.data.network.MusicSearchResult
import dev.hryshyn.remanence.core.data.network.MusicTrackHit
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicPickerStateMachineTest {

    private fun hit(id: String = "be30e36b-1111-4111-8111-000000000001"): MusicTrackHit =
        MusicTrackHit(
            id = id,
            title = "505",
            artists = listOf("Arctic Monkeys"),
            version = null,
            release = "Favourite Worst Nightmare",
            year = 2007,
            durationMs = 253000L,
            artworkAvailable = true,
        )

    private fun hitsResult(total: Int = 2): MusicSearchResult.Hits =
        MusicSearchResult.Hits(MusicSearchPage(listOf(hit()), total, 0, 10))

    private fun TestScope.machine(
        search: suspend (String, Int, Int) -> MusicSearchResult = { _, _, _ -> hitsResult() },
        debounceMs: Long = 400L,
    ): MusicPickerStateMachine =
        // backgroundScope: the machine's collector is an infinite debounced
        // flow and must not outlive the test body (else runTest fails with
        // UncompletedCoroutinesError). Production passes a UI-bound scope
        // (rememberCoroutineScope, cancelled on dispose) plus close().
        MusicPickerStateMachine(search = search, scope = backgroundScope, debounceMs = debounceMs)

    @Test
    fun rapidTypingDebouncesToSingleLatestQuery() = runTest {
        val seen = CopyOnWriteArrayList<String>()
        val state = machine(search = { query, _, _ ->
            seen.add(query)
            hitsResult()
        })
        state.onQueryChange("5")
        state.onQueryChange("50")
        state.onQueryChange("505")
        assertEquals(listOf<String>(), seen)
        advanceTimeBy(399L)
        runCurrent()
        assertEquals(listOf<String>(), seen)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf("505"), seen)
        (state.uiState.value as? MusicPickerUiState.Results ?: error("expected Results"))
    }

    @Test
    fun blankQueryNeverCallsBackendAndStaysIdle() = runTest {
        var calls = 0
        val state = machine(search = { _, _, _ ->
            calls += 1
            hitsResult()
        })
        state.onQueryChange("   ")
        advanceTimeBy(1000L)
        runCurrent()
        assertEquals(0, calls)
        assertTrue(state.uiState.value is MusicPickerUiState.Idle)
    }

    @Test
    fun emptyResultsMapToEmptyState() = runTest {
        val state = machine(search = { _, _, _ ->
            MusicSearchResult.Hits(MusicSearchPage(emptyList(), 0, 0, 10))
        })
        state.onQueryChange("zzz-no-match")
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(state.uiState.value is MusicPickerUiState.Empty)
    }

    @Test
    fun failuresMapToTypedStates() = runTest {
        val cases = mapOf(
            MusicSearchFailure.AUTH_INVALID to MusicPickerUiState.AuthInvalid,
            MusicSearchFailure.VALIDATION_FAILED to MusicPickerUiState.ValidationFailed,
            MusicSearchFailure.RATE_LIMITED to MusicPickerUiState.RateLimited,
            MusicSearchFailure.UNAVAILABLE to MusicPickerUiState.Unavailable(true),
            MusicSearchFailure.NETWORK to MusicPickerUiState.Unavailable(true),
            MusicSearchFailure.HTTP to MusicPickerUiState.Error,
            MusicSearchFailure.INVALID_RESPONSE to MusicPickerUiState.Error,
            MusicSearchFailure.INTERNAL_ERROR to MusicPickerUiState.Error,
        )
        for ((reason, expected) in cases) {
            val state = machine(search = { _, _, _ ->
                MusicSearchResult.Failure(reason, retryable = reason != MusicSearchFailure.AUTH_INVALID)
            })
            state.onQueryChange("505")
            advanceTimeBy(500L)
            runCurrent()
            assertEquals(reason.name, expected, state.uiState.value)
            state.close()
        }
    }

    @Test
    fun latestQueryWinsOverSlowInFlightSearch() = runTest {
        val state = machine(search = { query, _, _ ->
            if (query == "slow") {
                delay(5000L)
                hitsResult(total = 1)
            } else {
                hitsResult(total = 2)
            }
        })
        state.onQueryChange("slow")
        advanceTimeBy(400L)
        runCurrent()
        state.onQueryChange("fast")
        advanceTimeBy(400L)
        runCurrent()
        val current = (state.uiState.value as? MusicPickerUiState.Results ?: error("expected Results"))
        assertEquals(2, current.total)
    }

    @Test
    fun selectAndClearSelectionRoundTrip() = runTest {
        val state = machine()
        state.onQueryChange("505")
        advanceTimeBy(500L)
        runCurrent()
        val selected = (state.uiState.value as? MusicPickerUiState.Results ?: error("expected Results")).hits.first()
        state.onSelect(selected)
        assertEquals(selected, state.selection.value)
        state.onClearSelection()
        assertNull(state.selection.value)
    }

    @Test
    fun clearResetsQueryAndState() = runTest {
        val state = machine()
        state.onQueryChange("505")
        advanceTimeBy(500L)
        runCurrent()
        (state.uiState.value as? MusicPickerUiState.Results ?: error("expected Results"))
        state.onSelect(hit())
        state.onClear()
        advanceTimeBy(500L)
        runCurrent()
        assertEquals("", state.query.value)
        assertNull(state.selection.value)
        assertTrue(state.uiState.value is MusicPickerUiState.Idle)
    }

    @Test
    fun retryReissuesSearchAfterFailure() = runTest {
        var calls = 0
        val state = machine(search = { _, _, _ ->
            calls += 1
            if (calls == 1) {
                MusicSearchResult.Failure(MusicSearchFailure.UNAVAILABLE, retryable = true)
            } else {
                hitsResult()
            }
        })
        state.onQueryChange("505")
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(state.uiState.value is MusicPickerUiState.Unavailable)
        state.retry()
        advanceTimeBy(500L)
        runCurrent()
        (state.uiState.value as? MusicPickerUiState.Results ?: error("expected Results"))
        assertEquals(2, calls)
    }
}
