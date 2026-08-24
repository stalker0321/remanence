package app.postmark.memory.capture

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import postmark.core.recognition.QualityReason

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class QualityFailureScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(reasons: Set<QualityReason>, recaptures: MutableList<Int>) {
        composeRule.setContent {
            MaterialTheme {
                QualityFailureScreen(
                    reasons = reasons,
                    onRecapture = { recaptures += 1 },
                )
            }
        }
    }

    /** Parameterized over every documented reason code (M1-R16 verification). */
    @Test
    fun everyReasonCodeRendersItsSpecificGuidance() {
        val recaptures = mutableListOf<Int>()
        val reasonsState = androidx.compose.runtime.mutableStateOf(setOf(QualityReason.entries.first()))
        composeRule.setContent {
            MaterialTheme {
                QualityFailureScreen(
                    reasons = reasonsState.value,
                    onRecapture = { recaptures += 1 },
                )
            }
        }

        for (reason in QualityReason.entries) {
            composeRule.runOnIdle { reasonsState.value = setOf(reason) }

            composeRule.onNodeWithTag("quality_failure_header").assertIsDisplayed()
            composeRule.onNodeWithTag("quality_reason_${reason.name}").assertIsDisplayed()
            composeRule.onNodeWithTag("quality_recapture_action")
                .assertIsDisplayed()
                .performClick()
        }

        composeRule.runOnIdle { assertEquals(QualityReason.entries.size, recaptures.size) }
    }

    @Test
    fun multipleFailuresShowEveryApplicableInstruction() {
        setContent(
            setOf(QualityReason.TOO_DARK, QualityReason.GLARE_EXCESSIVE),
            mutableListOf(),
        )

        composeRule.onNodeWithTag("quality_reason_TOO_DARK").assertIsDisplayed()
        composeRule.onNodeWithTag("quality_reason_GLARE_EXCESSIVE").assertIsDisplayed()
    }

    @Test
    fun emptyFailureSetShowsAcceptedStatusWithoutRecapture() {
        setContent(emptySet(), mutableListOf())

        composeRule.onNodeWithTag("quality_passed_status").assertIsDisplayed()
    }
}
