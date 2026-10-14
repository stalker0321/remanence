package dev.hryshyn.remanence.ui.capsule

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * T186 M14d-scan: route disposal and authoritative revocation release
 * caller-owned content (pending photo bytes) even on paths that never
 * reach onClose — disposal without release would leak plaintext past
 * the route lifetime. Normal back/close behavior is unchanged.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CapsuleRouteReleaseTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private class Source : CapsuleContentReader {
        override suspend fun photoCount(capsuleId: String): Int = 3

        override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto =
            DecryptedPhoto(ordinal, ByteArray(0))

        override suspend fun noteText(capsuleId: String): String? = null
    }

    @Test
    fun disposalReleasesContentWithoutClose() {
        var released = false
        var closed = false
        composeRule.setContent {
            MaterialTheme {
                CapsuleRoute(
                    grantId = "grant-1",
                    contentFactory = { CapsuleContentBinding("capsule-1", Source()) },
                    validateLiveGrant = {},
                    revocations = kotlinx.coroutines.flow.emptyFlow(),
                    onClose = { closed = true },
                    onReleaseContent = { released = true },
                )
            }
        }

        composeRule.onNodeWithTag("capsule_page_indicator").assertIsDisplayed()
        assertFalse(released)

        // Route removal without back/close still releases content.
        composeRule.setContent { }
        composeRule.waitForIdle()

        assertTrue(released)
        assertFalse(closed)
    }

    @Test
    fun revocationReleasesContent() {
        var released = false
        val revocations = MutableSharedFlow<String>()
        composeRule.setContent {
            MaterialTheme {
                CapsuleRoute(
                    grantId = "grant-1",
                    contentFactory = { CapsuleContentBinding("capsule-1", Source()) },
                    validateLiveGrant = {},
                    revocations = revocations,
                    onClose = {},
                    onReleaseContent = { released = true },
                )
            }
        }

        composeRule.onNodeWithTag("capsule_page_indicator").assertIsDisplayed()
        assertFalse(released)

        revocations.tryEmit("grant-1")
        composeRule.waitForIdle()

        assertTrue(released)
    }
}
