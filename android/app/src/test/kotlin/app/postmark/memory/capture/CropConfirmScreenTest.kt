package app.postmark.memory.capture

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import postmark.core.recognition.ManualCropQuad
import postmark.core.recognition.PointD

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CropConfirmScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun proposal(): ManualCropQuad = ManualCropQuad(
        corners = listOf(
            PointD(100.0, 100.0),
            PointD(900.0, 100.0),
            PointD(900.0, 600.0),
            PointD(100.0, 600.0),
        ),
        frameWidth = 1000,
        frameHeight = 800,
    )

    private fun setContent(shell: CropConfirmationShell, confirmed: MutableList<List<PointD>>, recaptures: MutableList<Int>) {
        composeRule.setContent {
            CropConfirmScreen(
                shell = shell,
                onConfirmed = { confirmed += it },
                onRecapture = { recaptures += 1 },
            )
        }
    }

    @Test
    fun proposedSurfaceOffersConfirmAdjustAndRecapture() {
        val recaptures = mutableListOf<Int>()
        setContent(CropConfirmationShell(proposal()), mutableListOf(), recaptures)

        composeRule.onNodeWithTag("crop_proposed_note").assertIsDisplayed()
        composeRule.onNodeWithTag("crop_quad_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("crop_confirm_action").assertIsDisplayed()
        composeRule.onNodeWithTag("crop_adjust_action").assertIsDisplayed()
        composeRule.onNodeWithTag("crop_recapture_action").assertIsDisplayed()

        composeRule.onNodeWithTag("crop_recapture_action").performClick()

        composeRule.runOnIdle { assertEquals(1, recaptures.size) }
        composeRule.onNodeWithTag("crop_recapture_status").assertIsDisplayed()
    }

    @Test
    fun confirmFromProposalDeliversOrderedCorners() {
        val confirmed = mutableListOf<List<PointD>>()
        setContent(CropConfirmationShell(proposal()), confirmed, mutableListOf())

        composeRule.onNodeWithTag("crop_confirm_action").performClick()

        composeRule.runOnIdle {
            assertEquals(1, confirmed.size)
            assertEquals(proposal().corners, confirmed.first())
        }
        composeRule.onNodeWithTag("crop_confirmed_status").assertIsDisplayed()
    }

    @Test
    fun invalidManualCornerDisablesConfirmAndShowsReason() {
        val shell = CropConfirmationShell(proposal())
        val confirmed = mutableListOf<List<PointD>>()
        setContent(shell, confirmed, mutableListOf())

        composeRule.onNodeWithTag("crop_adjust_action").performClick()

        composeRule.runOnIdle { shell.updateCorner(0, PointD(-40.0, 100.0)) }

        composeRule.onNodeWithTag("crop_invalid_reason").assertIsDisplayed()
        composeRule.onNodeWithTag("crop_confirm_action").assertIsNotEnabled()

        composeRule.runOnIdle { assertTrue(confirmed.isEmpty()) }
    }
}
