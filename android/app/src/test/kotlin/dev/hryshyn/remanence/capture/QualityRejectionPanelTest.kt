package dev.hryshyn.remanence.capture

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
import dev.hryshyn.remanence.core.recognition.QualityReason

/**
 * FIX-STATE-04 regression: THE production rejection panel renders one
 * actionable instruction per documented reason code (M1-R16) and its Retake
 * action is real - there is no default no-op path.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class QualityRejectionPanelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun everyReasonCodeRendersItsSpecificGuidanceAndWorkingRetake() {
        val recaptures = mutableListOf<Int>()
        val reasonsState = androidx.compose.runtime.mutableStateOf(setOf(QualityReason.entries.first()))
        composeRule.setContent {
            MaterialTheme {
                QualityRejectionPanel(
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
        composeRule.setContent {
            MaterialTheme {
                QualityRejectionPanel(
                    reasons = setOf(
                        QualityReason.ANGLE_UNCERTAIN,
                        QualityReason.RESOLUTION_INSUFFICIENT,
                        QualityReason.TOO_DARK,
                        QualityReason.GLARE_EXCESSIVE,
                    ),
                    onRecapture = {},
                )
            }
        }

        composeRule.onNodeWithTag("quality_reason_ANGLE_UNCERTAIN").assertIsDisplayed()
        composeRule.onNodeWithTag("quality_reason_RESOLUTION_INSUFFICIENT").assertIsDisplayed()
        composeRule.onNodeWithTag("quality_reason_TOO_DARK").assertIsDisplayed()
        composeRule.onNodeWithTag("quality_reason_GLARE_EXCESSIVE").assertIsDisplayed()
    }

    @Test
    fun guidanceUsesTheDocumentedActionableCopy() {
        assertEquals(
            mapOf(
                QualityReason.CARD_TOO_SMALL to
                    "Move closer while keeping all four postcard edges visible.",
                QualityReason.CROP_UNCERTAIN to
                    "Show all four postcard corners and edges; remove any occlusion.",
                QualityReason.ANGLE_UNCERTAIN to
                    "Hold the phone parallel to the postcard and align its edges.",
                QualityReason.RESOLUTION_INSUFFICIENT to
                    "Move closer, use the highest still resolution, and keep the full card visible.",
                QualityReason.TOO_BLURRY to "Hold still and tap to focus.",
                QualityReason.TOO_DARK to "Use brighter, even light and remove shadows.",
                QualityReason.GLARE_EXCESSIVE to "Tilt the postcard or move the light to remove glare.",
                QualityReason.FEATURES_INSUFFICIENT to
                    "Use printed detail, focus, good light, and keep the full card inside the outline.",
            ),
            QualityReason.entries.associateWith(::guidanceFor),
        )
    }

    /** Capture surfaces pass their own retake tag through to the panel. */
    @Test
    fun customRetakeTagIsHonoredForSurfaceWiring() {
        var retakes = 0
        composeRule.setContent {
            MaterialTheme {
                QualityRejectionPanel(
                    reasons = setOf(QualityReason.TOO_BLURRY),
                    onRecapture = { retakes += 1 },
                    recaptureTag = "capture_retake_front",
                )
            }
        }

        composeRule.onNodeWithTag("capture_retake_front")
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle { assertEquals(1, retakes) }
    }
}
