package app.postmark.memory.capture

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StillCaptureScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(shell: SingleStillCaptureShell, hooks: CaptureTestHooks?) {
        composeRule.setContent {
            MaterialTheme {
                StillCaptureScreen(
                    shell = shell,
                    onStillCaptured = {},
                    testHooks = hooks,
                )
            }
        }
    }

    private fun assertNodeAbsent(tag: String) {
        assertTrue(
            "expected no node with tag $tag",
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun fakeHooks(captures: MutableList<Int>): CaptureTestHooks = CaptureTestHooks(
        previewHost = { _, onBound, _ ->
            Button(
                onClick = { onBound() },
                modifier = Modifier.testTag("fake_bind_action"),
            ) {
                Text("bind")
            }
        },
        onCaptureRequested = { captures += 1 },
    )

    @Test
    fun unrequestedPermissionShowsRationaleAndRequestAction() {
        setContent(SingleStillCaptureShell(), null)

        composeRule.onNodeWithTag("capture_permission_rationale").assertIsDisplayed()
        composeRule.onNodeWithTag("capture_request_permission").assertIsDisplayed()
        assertNodeAbsent("capture_still_action")
    }

    @Test
    fun deniedOnceShowsAskAgainWithoutCaptureAction() {
        val shell = SingleStillCaptureShell()
        shell.onPermissionResult(granted = false, canAskAgain = true)
        setContent(shell, null)

        composeRule.onNodeWithTag("capture_denied_note").assertIsDisplayed()
        composeRule.onNodeWithTag("capture_request_permission").assertIsDisplayed()
        assertNodeAbsent("capture_still_action")
    }

    @Test
    fun permanentlyDeniedPointsToSettingsOnly() {
        val shell = SingleStillCaptureShell()
        shell.onPermissionResult(granted = false, canAskAgain = false)
        var settingsOpened = 0
        composeRule.setContent {
            MaterialTheme {
                StillCaptureScreen(
                    shell = shell,
                    onStillCaptured = {},
                    onOpenAppSettings = { settingsOpened += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("capture_blocked_note").assertIsDisplayed()
        composeRule.onNodeWithTag("capture_open_settings")
            .performClick()
            .assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, settingsOpened) }
        assertNodeAbsent("capture_request_permission")
    }

    @Test
    fun grantedFlowAdvancesBindingToReadyToCapturingThroughHooks() {
        val shell = SingleStillCaptureShell()
        shell.onPermissionResult(granted = true, canAskAgain = true)
        val captures = mutableListOf<Int>()
        setContent(shell, fakeHooks(captures))

        composeRule.onNodeWithTag("capture_binding_status").assertIsDisplayed()

        composeRule.onNodeWithTag("fake_bind_action").performClick()

        composeRule.onNodeWithTag("capture_still_action")
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithTag("capture_capturing_status").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, captures.size) }
    }
}
