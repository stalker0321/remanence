package dev.hryshyn.remanence.ui.create.music

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * S4 STOP regression: the old `LaunchedEffect(selection)` bridge wrote the
 * picker's recreated null back into ViewModel state, silently wiping a
 * chosen track on rotation/step re-entry/retry. The explicit-callback
 * wiring must preserve a non-null owner value across remounts (seeded
 * display, zero owner writes) while explicit clear still propagates.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MusicPickerContentTest {

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

    private val first = hit("be30e36b-1111-4111-8111-000000000001", "505")

    private fun fakeSearch(): suspend (String, Int, Int) -> MusicSearchResult =
        { query, _, _ ->
            if (query.isBlank()) {
                MusicSearchResult.Hits(MusicSearchPage(emptyList(), 0, 0, 10))
            } else {
                MusicSearchResult.Hits(MusicSearchPage(listOf(first), 1, 0, 10))
            }
        }

    @Test
    fun remountPreservesOwnerSelectionWithZeroOwnerWrites() {
        val ownerWrites = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                var show by remember { mutableStateOf(true) }
                var vmSelection by remember { mutableStateOf<MusicTrackHit?>(null) }
                TextButton(
                    onClick = { show = !show },
                    modifier = Modifier.testTag("music_test_toggle"),
                ) { Text("toggle") }
                if (show) {
                    MusicPickerContent(
                        search = fakeSearch(),
                        vmSelection = vmSelection,
                        onSelect = { ownerWrites.add("set:${it.id}"); vmSelection = it },
                        onClearSelection = { ownerWrites.add("clear"); vmSelection = null },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("music_query").performTextInput("505")
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("music_hit_${first.id}").performClick()
        composeRule.onNodeWithText("Attached on publish: 505").assertIsDisplayed()
        assertEquals(listOf("set:${first.id}"), ownerWrites)

        // Remount (rotation/step re-entry/retry): the display re-seeds from
        // the owner value and must not write anything back.
        composeRule.onNodeWithTag("music_test_toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("music_test_toggle").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Attached on publish: 505").assertIsDisplayed()
        assertEquals(
            "remount must not write the owner selection",
            listOf("set:${first.id}"),
            ownerWrites,
        )
    }

    @Test
    fun explicitClearPropagatesToOwner() {
        val ownerWrites = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                var vmSelection by remember { mutableStateOf<MusicTrackHit?>(null) }
                MusicPickerContent(
                    search = fakeSearch(),
                    vmSelection = vmSelection,
                    onSelect = { ownerWrites.add("set:${it.id}"); vmSelection = it },
                    onClearSelection = { ownerWrites.add("clear"); vmSelection = null },
                )
            }
        }

        composeRule.onNodeWithTag("music_query").performTextInput("505")
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("music_hit_${first.id}").performClick()
        composeRule.onNodeWithTag("music_clear").performClick()

        assertEquals(listOf("set:${first.id}", "clear"), ownerWrites)
        assertTrue(composeRule.onAllNodesWithTag("music_selection").fetchSemanticsNodes().isEmpty())
    }
}
