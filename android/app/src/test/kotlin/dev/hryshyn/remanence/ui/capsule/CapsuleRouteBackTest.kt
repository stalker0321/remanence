package dev.hryshyn.remanence.ui.capsule

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Capsule Back closes its plaintext state before invoking route navigation. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CapsuleRouteBackTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private class Source : CapsuleContentReader {
        override suspend fun photoCount(capsuleId: String): Int = 3

        override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto =
            DecryptedPhoto(ordinal, ByteArray(0))

        override suspend fun noteText(capsuleId: String): String? = null
    }

    @Test
    fun backClosesReadyPresentationBeforeCallingRouteClose() {
        var closed = false
        composeRule.setContent {
            MaterialTheme {
                CapsuleRoute(
                    grantId = "grant-1",
                    contentFactory = { CapsuleContentBinding("capsule-1", Source()) },
                    validateLiveGrant = {},
                    revocations = kotlinx.coroutines.flow.emptyFlow(),
                    onClose = { closed = true },
                )
            }
        }

        composeRule.onNodeWithTag("capsule_page_indicator").assertIsDisplayed()
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()

        assertTrue(closed)
        // The route stays mounted in this isolated test, so a zero count is
        // an observable proof that Back closed the presentation first.
        composeRule.onNodeWithText("Photo 1 of 0").assertIsDisplayed()
    }
}
