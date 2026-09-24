package dev.hryshyn.remanence.ui.create.music

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.data.network.MusicSearchPage
import dev.hryshyn.remanence.core.data.network.MusicSearchResult
import dev.hryshyn.remanence.core.data.network.MusicTrackHit
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * S4: the picker renders in a release-compatible composition (no DEBUG
 * flag anywhere in this path), shows an honest attached-on-publish
 * affordance, and supports select/replace/clear. The ViewModel bridge
 * (selection -> publish state) is covered by CreateMusicSelectionSessionTest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MusicPickerSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun hit(id: String, title: String) = MusicTrackHit(
        id = id,
        title = title,
        artists = listOf("Arctic Monkeys"),
        version = null,
        release = "Favourite Worst Nightmare",
        year = 2007,
        durationMs = 253000L,
        artworkAvailable = true,
    )

    private fun fakeSearch(first: MusicTrackHit, second: MusicTrackHit) =
        { query: String, _: Int, _: Int ->
            if (query.isBlank()) {
                MusicSearchResult.Hits(MusicSearchPage(emptyList(), 0, 0, 10))
            } else {
                MusicSearchResult.Hits(MusicSearchPage(listOf(first, second), 2, 0, 10))
            }
        }

    private fun setSectionContent(
        first: MusicTrackHit = hit("be30e36b-1111-4111-8111-000000000001", "505"),
        second: MusicTrackHit = hit("be30e36b-2222-4222-8222-000000000002", "Do I Wanna Know?"),
    ) {
        composeRule.setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val machine = remember {
                    MusicPickerStateMachine(fakeSearch(first, second), scope)
                }
                MusicPickerSection(state = machine)
            }
        }
    }

    @Test
    fun pickerVisibleWithoutDebugFlag() {
        setSectionContent()

        // No BuildConfig gate exists on this path anymore: the query field
        // renders in a plain release-compatible composition.
        composeRule.onNodeWithTag("music_section").assertIsDisplayed()
        composeRule.onNodeWithTag("music_query").assertIsDisplayed()
        composeRule.onNodeWithTag("music_idle").assertIsDisplayed()
    }

    @Test
    fun searchSelectShowsAttachedAffordance() {
        setSectionContent()

        composeRule.onNodeWithTag("music_query").performTextInput("505")
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("music_count").assertIsDisplayed()
        composeRule.onNodeWithTag("music_hit_be30e36b-1111-4111-8111-000000000001").performClick()

        composeRule.onNodeWithTag("music_selection").assertIsDisplayed()
        composeRule.onNodeWithText("Attached on publish: 505").assertIsDisplayed()
    }

    @Test
    fun selectingAnotherHitReplacesSelection() {
        setSectionContent()

        composeRule.onNodeWithTag("music_query").performTextInput("505")
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("music_hit_be30e36b-1111-4111-8111-000000000001").performClick()
        composeRule.onNodeWithText("Attached on publish: 505").assertIsDisplayed()
        composeRule.onNodeWithTag("music_hit_be30e36b-2222-4222-8222-000000000002").performClick()
        composeRule.onNodeWithText("Attached on publish: Do I Wanna Know?").assertIsDisplayed()
    }

    @Test
    fun clearRemovesSelection() {
        setSectionContent()

        composeRule.onNodeWithTag("music_query").performTextInput("505")
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("music_hit_be30e36b-1111-4111-8111-000000000001").performClick()
        composeRule.onNodeWithTag("music_selection").assertIsDisplayed()
        composeRule.onNodeWithTag("music_clear").performClick()

        assertTrue(composeRule.onAllNodesWithTag("music_selection").fetchSemanticsNodes().isEmpty())
    }
}
