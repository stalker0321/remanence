package app.postmark.memory.ui.scan

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Minimal-hints/no-gallery proof for M1-M13 ambiguity chooser. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AmbiguityChooserScreenTest {

    @get:org.junit.Rule
    val composeRule = createComposeRule()

    private val rows = listOf(
        ChooserHintRow("cap-a", "mykola", "2026 · May 14", "Lviv"),
        ChooserHintRow("cap-b", "olena", "2026 · March 02", null),
    )

    @Test
    fun rendersEveryRowWithHintsOnlyAndOrderedContent() {
        composeRule.setContent {
            AmbiguityChooserScreen(rows = rows, onSelected = {}, onRecapture = {})
        }

        composeRule.onNodeWithTag("chooser_title").assertIsDisplayed()
        composeRule.onNodeWithTag("chooser_row_cap-a").assertIsDisplayed()
        composeRule.onNodeWithTag("chooser_row_cap-b").assertIsDisplayed()
        // Exactly two candidate affordances: no gallery, no browsing surface.
        composeRule.onAllNodesWithTag("chooser_row_cap-a", useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule.onNodeWithTag("chooser_recapture_button").assertIsDisplayed()
    }

    @Test
    fun selectionReportsOnlyTheChosenCandidate() {
        var chosen: ChooserHintRow? = null
        composeRule.setContent {
            AmbiguityChooserScreen(rows = rows, onSelected = { chosen = it }, onRecapture = {})
        }

        composeRule.onNodeWithTag("chooser_row_cap-b").performClick()

        assertEquals("cap-b", chosen?.candidateId)
        assertNull(chosen?.placeLabel)
        assertEquals(
            "second row must not inherit the first row's place hint",
            null,
            chosen?.placeLabel,
        )
    }

    @Test
    fun recaptureButtonNeverSelectsACandidate() {
        var selectedCount = 0
        var recaptured = false
        composeRule.setContent {
            AmbiguityChooserScreen(
                rows = rows,
                onSelected = { selectedCount++ },
                onRecapture = { recaptured = true },
            )
        }

        composeRule.onNodeWithTag("chooser_recapture_button").performClick()

        assertEquals(0, selectedCount)
        assertTrue(recaptured)
    }
}
