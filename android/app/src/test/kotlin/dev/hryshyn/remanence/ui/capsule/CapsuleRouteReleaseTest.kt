package dev.hryshyn.remanence.ui.capsule

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T186 M14d-scan: route disposal and authoritative revocation release
 * caller-owned content (pending photo bytes) even on paths that never
 * reach onClose — disposal without release would leak plaintext past
 * the route lifetime. One setContent with state-toggled presence;
 * genuinely decodable JPEGs (NATIVE) so Ready is real; buffered flow
 * revocation delivered to the awaiting collector. Normal back/close
 * behavior is unchanged.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CapsuleRouteReleaseTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class Source : CapsuleContentReader {
        override suspend fun photoCount(capsuleId: String): Int = 3

        override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto =
            DecryptedPhoto(ordinal, realJpegStatic(0xFF336699L + ordinal))

        override suspend fun noteText(capsuleId: String): String? = null
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun disposalReleasesContentWithoutClose() {
        var released = false
        var closed = false
        val showRoute = mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                if (showRoute.value) {
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
        }

        composeRule.onNodeWithTag("capsule_page_indicator").assertIsDisplayed()
        assertFalse(released)

        // Route removal without back/close still releases content.
        showRoute.value = false
        composeRule.waitForIdle()

        assertTrue(released)
        assertFalse(closed)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun revocationReleasesContent() {
        var released = false
        // Buffered delivery to the collector awaiting in LaunchedEffect.
        val revocations = MutableSharedFlow<String>(extraBufferCapacity = 8)
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
        composeRule.onNodeWithTag("capsule_route_failed_header").assertIsDisplayed()
    }

    companion object {
        private fun realJpegStatic(color: Long): ByteArray {
            val bitmap = android.graphics.Bitmap.createBitmap(
                8, 8, android.graphics.Bitmap.Config.ARGB_8888,
            )
            bitmap.eraseColor(color.toInt())
            val output = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)
            bitmap.recycle()
            return output.toByteArray()
        }
    }
}
