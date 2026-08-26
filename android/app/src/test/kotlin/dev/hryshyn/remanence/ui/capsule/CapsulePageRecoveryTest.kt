package dev.hryshyn.remanence.ui.capsule

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private fun decodableJpeg(color: Long): ByteArray {
    val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(color.toInt())
    val output = java.io.ByteArrayOutputStream()
    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)
    bitmap.recycle()
    return output.toByteArray()
}

/**
 * FIX-STATE-07 regression: a failed page decode is visible AND actionable -
 * Retry re-runs the bounded decode from fresh plaintext without ever leaving
 * the capsule surface, and Close remains available throughout.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CapsulePageRecoveryTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun failedDecodeShowsErrorAndRetryRecoversThePage() {
        var poisoned = true
        var loads = 0
        val state = CapsulePresentationState(
            photoLoader = { ordinal ->
                loads += 1
                if (poisoned && ordinal == 0) {
                    // Simulates a broken artifact: budget ok, content unusable.
                    throw IllegalStateException("page ciphertext is corrupt")
                }
                DecryptedPhoto(ordinal, decodableJpeg(0xFF669933L + ordinal))
            },
        )
        state.open(3, note = null)

        composeRule.setContent {
            MaterialTheme {
                CapsuleScreen(state) { }
            }
        }

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("capsule_page_error").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("capsule_page_error").assertIsDisplayed()
        assertEquals(1, loads)

        // The poisoned bytes are evicted from the cache, then retried.
        poisoned = false
        composeRule.onNodeWithTag("capsule_page_retry").performClick()

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("capsule_page_0").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("capsule_page_0").assertIsDisplayed()
        assertEquals(2, loads)
    }
}
