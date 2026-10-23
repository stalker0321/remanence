package dev.hryshyn.remanence.ui.create.music

import dev.hryshyn.remanence.core.data.network.MusicSearchResult
import dev.hryshyn.remanence.core.data.network.MusicTrackHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * S1 music-picker state for the capsule creation flow.
 *
 * Pure state machine (no Android types): the query is debounced
 * ([debounceMs], production 400ms within the 300-500ms budget) and the
 * latest query always wins — an older in-flight search is cancelled and
 * can never overwrite newer results. Selection is explicit (select/clear)
 * and is never attached to any publish path in S1: there is deliberately
 * no bridge into [CapsulePublisher] until S2 defines the encrypted
 * attachment, so the release flow cannot claim a track is attached.
 */
sealed interface MusicPickerUiState {
    data object Idle : MusicPickerUiState

    data object Loading : MusicPickerUiState

    data class Results(
        val hits: List<MusicTrackHit>,
        val total: Int,
    ) : MusicPickerUiState

    data object Empty : MusicPickerUiState

    data class Unavailable(val retryable: Boolean) : MusicPickerUiState

    data object RateLimited : MusicPickerUiState

    data object AuthInvalid : MusicPickerUiState

    data object ValidationFailed : MusicPickerUiState

    data object Error : MusicPickerUiState
}

class MusicPickerStateMachine(
    private val search: suspend (query: String, limit: Int, offset: Int) -> MusicSearchResult,
    scope: CoroutineScope,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val pageLimit: Int = PAGE_LIMIT,
    /**
     * S4: seeds the display selection (e.g. the ViewModel-owned value on
     * UI recreation). The machine never writes back on its own — selection
     * reaches the owner only through explicit select/clear calls.
     */
    initialSelection: MusicTrackHit? = null,
) {
    private val queryFlow = MutableStateFlow("")
    val query: StateFlow<String> = queryFlow

    private data class SearchRequest(val query: String, val revision: Long)
    private val requests = MutableStateFlow(SearchRequest("", 0))

    private val mutableUiState = MutableStateFlow<MusicPickerUiState>(MusicPickerUiState.Idle)
    val uiState: StateFlow<MusicPickerUiState> = mutableUiState

    private val mutableSelection = MutableStateFlow<MusicTrackHit?>(initialSelection)
    val selection: StateFlow<MusicTrackHit?> = mutableSelection

    private val collector: Job = scope.launch {
        requests.collectLatest { request ->
            if (request.query.isBlank()) return@collectLatest
            // Delay the next request, not cancellation of the previous one.
            // Old results must never be selectable beneath a new query.
            delay(debounceMs)
            val result = executeSearch(request.query)
            currentCoroutineContext().ensureActive()
            if (requests.value == request) {
                mutableUiState.value = result
            }
        }
    }

    fun onQueryChange(query: String) {
        if (queryFlow.value == query) return
        queryFlow.value = query
        requestSearch(query)
    }

    fun onSelect(hit: MusicTrackHit) {
        mutableSelection.value = hit
    }

    fun onClearSelection() {
        mutableSelection.value = null
    }

    fun onClear() {
        onQueryChange("")
        mutableSelection.value = null
    }

    fun retry() {
        if (mutableUiState.value == MusicPickerUiState.Loading || queryFlow.value.isBlank()) return
        requestSearch(queryFlow.value)
    }

    private fun requestSearch(query: String) {
        mutableUiState.value = if (query.isBlank()) MusicPickerUiState.Idle else MusicPickerUiState.Loading
        requests.value = SearchRequest(query, requests.value.revision + 1)
    }

    fun close() {
        collector.cancel()
    }

    private suspend fun executeSearch(query: String): MusicPickerUiState =
        when (val result = search(query, pageLimit, 0)) {
            is MusicSearchResult.Hits ->
                if (result.page.hits.isEmpty()) {
                    MusicPickerUiState.Empty
                } else {
                    MusicPickerUiState.Results(result.page.hits, result.page.total)
                }
            is MusicSearchResult.Failure ->
                when (result.reason) {
                    dev.hryshyn.remanence.core.data.network.MusicSearchFailure.AUTH_INVALID ->
                        MusicPickerUiState.AuthInvalid
                    dev.hryshyn.remanence.core.data.network.MusicSearchFailure.VALIDATION_FAILED ->
                        MusicPickerUiState.ValidationFailed
                    dev.hryshyn.remanence.core.data.network.MusicSearchFailure.RATE_LIMITED ->
                        MusicPickerUiState.RateLimited
                    dev.hryshyn.remanence.core.data.network.MusicSearchFailure.UNAVAILABLE,
                    dev.hryshyn.remanence.core.data.network.MusicSearchFailure.NETWORK ->
                        MusicPickerUiState.Unavailable(result.retryable)
                    dev.hryshyn.remanence.core.data.network.MusicSearchFailure.HTTP,
                    dev.hryshyn.remanence.core.data.network.MusicSearchFailure.INVALID_RESPONSE,
                    dev.hryshyn.remanence.core.data.network.MusicSearchFailure.INTERNAL_ERROR ->
                        MusicPickerUiState.Error
                }
        }

    companion object {
        const val DEBOUNCE_MS = 400L
        const val PAGE_LIMIT = 10
    }
}
