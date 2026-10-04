package dev.hryshyn.remanence.ui.create.music

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.data.network.MusicSearchPage
import dev.hryshyn.remanence.core.data.network.MusicSearchResult
import dev.hryshyn.remanence.core.data.network.MusicTrackHit
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * T150 music selection UX: selecting collapses the query/results into a
 * visible title/artist card with change/remove; change starts one explicit
 * new search; remove clears; late results never undo the selection.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MusicPickerSectionFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun hit(id: String, title: String = "Is This Love (live)") = MusicTrackHit(
        id = id,
        title = title,
        artists = listOf("Whitesnake"),
        version = null,
        release = "1987",
        year = 1987,
        durationMs = 283000L,
        artworkAvailable = true,
        recordingMbid = null,
    )

    private fun page(vararg hits: MusicTrackHit, total: Int = hits.size) =
        MusicSearchResult.Hits(MusicSearchPage(hits.toList(), total, 0, 10))

    private fun show(
        search: suspend (String, Int, Int) -> MusicSearchResult,
        holder: Array<MusicPickerStateMachine?>,
    ) {
        composeRule.setContent {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val machine = androidx.compose.runtime.remember {
                MusicPickerStateMachine(search, scope).also { holder[0] = it }
            }
            MusicPickerSection(
                state = machine,
                onSelect = machine::onSelect,
                onClearSelection = machine::onClearSelection,
            )
        }
    }

    @Test
    fun selectCollapsesResultsIntoTitleArtistCard() {
        val holder = arrayOfNulls<MusicPickerStateMachine>(1)
        show({ _, _, _ -> page(hit("id-1"), hit("id-2")) }, holder)

        composeRule.onNodeWithTag("music_add").performClick()
        composeRule.onNodeWithTag("music_query").performTextInput("is this love")
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.onNodeWithTag("music_hit_id-1").performClick()

        composeRule.onNodeWithTag("music_query").assertDoesNotExist()
        composeRule.onNodeWithTag("music_hit_id-1").assertDoesNotExist()
        composeRule.onNodeWithTag("music_selection").assertExists()
        composeRule.onNodeWithTag("music_selection_artist").assertExists()
        composeRule.onNodeWithTag("music_change").assertExists()
        composeRule.onNodeWithTag("music_clear").assertExists()
        assertEquals("id-1", holder[0]?.selection?.value?.id)
    }

    @Test
    fun changeRevealsExplicitSearchAndRemoveClears() {
        val holder = arrayOfNulls<MusicPickerStateMachine>(1)
        show({ _, _, _ -> page(hit("id-1")) }, holder)

        composeRule.onNodeWithTag("music_add").performClick()
        composeRule.onNodeWithTag("music_query").performTextInput("is this love")
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.onNodeWithTag("music_hit_id-1").performClick()
        composeRule.onNodeWithTag("music_change").performClick()

        composeRule.onNodeWithTag("music_query").assertExists()
        composeRule.onNodeWithTag("music_search_close").performClick()
        assertEquals("id-1", holder[0]?.selection?.value?.id)
        composeRule.onNodeWithTag("music_clear").performClick()

        composeRule.onNodeWithTag("music_query").assertDoesNotExist()
        composeRule.onNodeWithTag("music_add").assertExists()
        composeRule.onNodeWithTag("music_selection").assertDoesNotExist()
        assertEquals(null, holder[0]?.selection?.value)
    }

    @Test
    fun lateResultsNeverUndoSelection() {
        val holder = arrayOfNulls<MusicPickerStateMachine>(1)
        val gate = CompletableDeferred<MusicSearchResult>()
        show({ query, _, _ ->
            if (query == "slow") gate.await() else page(hit("id-2"))
        }, holder)

        composeRule.onNodeWithTag("music_add").performClick()
        composeRule.onNodeWithTag("music_query").performTextInput("slow")
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.onNodeWithTag("music_query").performTextClearance()
        composeRule.onNodeWithTag("music_query").performTextInput("fast")
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.onNodeWithTag("music_hit_id-2").performClick()
        gate.complete(page(hit("id-1")))
        composeRule.mainClock.advanceTimeBy(1000)

        assertEquals("id-2", holder[0]?.selection?.value?.id)
        composeRule.onNodeWithTag("music_selection").assertExists()
    }
}
