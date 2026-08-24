package app.postmark.memory.capture

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PreparedBackScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(gate: PreparedBackGate, requests: MutableList<Int>) {
        composeRule.setContent {
            PreparedBackScreen(gate = gate, onBackCaptureRequested = { requests += 1 })
        }
    }

    @Test
    fun lockedUntilEveryCheckboxIsConfirmedThenFiresOnce() {
        val gate = PreparedBackGate()
        val requests = mutableListOf<Int>()
        setContent(gate, requests)

        composeRule.onNodeWithTag("prepared_header").assertIsDisplayed()
        composeRule.onNodeWithTag("back_capture_action").assertIsNotEnabled()

        // Check all but the last item; the action stays locked.
        val items = PreparedBackItem.entries
        for (item in items.dropLast(1)) {
            composeRule.runOnIdle { gate.setChecked(item, true) }
            composeRule.onNodeWithTag("prepared_item_${item.name}").assertIsDisplayed()
            composeRule.onNodeWithTag("back_capture_action").assertIsNotEnabled()
        }

        composeRule.runOnIdle { gate.setChecked(items.last(), true) }

        composeRule.onNodeWithTag("prepared_status").assertIsDisplayed()
        composeRule.onNodeWithTag("back_capture_action")
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle { assertEquals(1, requests.size) }
    }

    @Test
    fun uncheckingReLocksTheAction() {
        val gate = PreparedBackGate()
        val requests = mutableListOf<Int>()
        setContent(gate, requests)

        composeRule.runOnIdle { PreparedBackItem.entries.forEach { gate.setChecked(it, true) } }

        composeRule.onNodeWithTag("back_capture_action").assertIsDisplayed()
        composeRule.runOnIdle { gate.setChecked(PreparedBackItem.ADDRESS_WRITTEN, false) }

        composeRule.onNodeWithTag("back_capture_action").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, requests.size) }
    }
}
