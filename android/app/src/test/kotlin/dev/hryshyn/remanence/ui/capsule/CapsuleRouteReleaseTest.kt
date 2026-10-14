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

    /**
     * T186 M14d-scan: revocation while still Loading (content already
     * acquired, photo load gated) aborts before publishing Ready —
     * content is released, owned bytes are zeroed, and neither Ready
     * nor retire ever fires.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun revocationWhileLoadingReleasesAndNeverPresents() {
        var released = false
        var retired = false
        val owned = ByteArray(16) { 7 }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val revocations = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val gated = object : CapsuleContentReader {
            override suspend fun photoCount(capsuleId: String): Int {
                gate.await()
                return 3
            }

            override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto =
                DecryptedPhoto(ordinal, realJpegStatic(0xFF336699L + ordinal))

            override suspend fun noteText(capsuleId: String): String? = null
        }
        composeRule.setContent {
            MaterialTheme {
                CapsuleRoute(
                    grantId = "grant-1",
                    contentFactory = { CapsuleContentBinding("capsule-1", gated) },
                    validateLiveGrant = {},
                    revocations = revocations,
                    onClose = {},
                    onReleaseContent = {
                        released = true
                        owned.fill(0)
                    },
                    onSupportedReady = { retired = true },
                )
            }
        }

        composeRule.onNodeWithTag("capsule_route_loading").assertIsDisplayed()
        assertFalse(released)

        // Revoke mid-load, then let the load resume: it must abort
        // before publishing Ready and release what it acquired.
        revocations.tryEmit("grant-1")
        composeRule.runOnIdle { gate.complete(Unit) }
        composeRule.waitForIdle()

        assertTrue(released)
        assertTrue(owned.all { it == 0.toByte() })
        composeRule.onNodeWithTag("capsule_route_failed_header").assertIsDisplayed()
        composeRule.onNodeWithTag("capsule_page_indicator").assertDoesNotExist()
        assertFalse("revoked load must never retire", retired)
    }

    /**
     * T186 M14d-scan: disposal while still Loading releases the
     * acquired content through the canceled-load branch (the
     * Ready-guarded disposal alone cannot wipe — state never reached
     * Ready).
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun disposalWhileLoadingReleasesContent() {
        var released = false
        val owned = ByteArray(16) { 7 }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val showRoute = androidx.compose.runtime.mutableStateOf(true)
        val gated = object : CapsuleContentReader {
            override suspend fun photoCount(capsuleId: String): Int {
                gate.await()
                return 3
            }

            override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto =
                DecryptedPhoto(ordinal, realJpegStatic(0xFF336699L + ordinal))

            override suspend fun noteText(capsuleId: String): String? = null
        }
        composeRule.setContent {
            MaterialTheme {
                if (showRoute.value) {
                    CapsuleRoute(
                        grantId = "grant-1",
                        contentFactory = { CapsuleContentBinding("capsule-1", gated) },
                        validateLiveGrant = {},
                        revocations = kotlinx.coroutines.flow.emptyFlow(),
                        onClose = {},
                        onReleaseContent = {
                            released = true
                            owned.fill(0)
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("capsule_route_loading").assertIsDisplayed()
        assertFalse(released)

        showRoute.value = false
        composeRule.waitForIdle()

        assertTrue(released)
        assertTrue(owned.all { it == 0.toByte() })
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
